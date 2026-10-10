package io.orphos.eip.dataingestion.component;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.dataingestion.repository.JobRepository;
import io.orphos.eip.dataingestion.repository.JobRunRepository;
import io.orphos.eip.dataingestion.service.JobService;
import io.orphos.eip.dataingestion.vo.JobDtos.ScheduleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Requirement 4.3 — the scheduler tick. Every {@code scheduler-interval-ms}
 * it iterates the active tenants (same cross-tenant pattern as
 * okg-ingest-service's OkgIngestScheduler — tenant context bound per
 * iteration, per-tenant failures isolated) and, per tenant:
 *
 * <ol>
 *   <li>fires jobs whose {@code nextRunAt} is due — CRON jobs get their next
 *       occurrence computed BEFORE execution starts so a slow run can never
 *       double-fire; ONE_TIME jobs get their pointer cleared (req 4.1/4.2);</li>
 *   <li>picks up persisted retry rows whose backoff has elapsed (req 4.6).</li>
 * </ol>
 *
 * <p>Requirement 7.4: when {@link SystemLoadGuard} reports CPU or heap above
 * the throttle threshold the whole tick is skipped — due work stays queued
 * and is re-evaluated on the next tick.</p>
 *
 * <p>Requirement 7.6: a daily pass prunes terminal run-history rows older
 * than the retention window.</p>
 */
@Component
@ConditionalOnProperty(prefix = "ingestion.jobs", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class JobScheduler {

    private static final Logger log = LogManager.getLogger(JobScheduler.class);

    private final TenantTaskExecutor tenants;
    private final JobRepository jobs;
    private final JobRunRepository runs;
    private final JobExecutionEngine engine;
    private final SystemLoadGuard loadGuard;
    private final int retentionDays;

    public JobScheduler(TenantTaskExecutor tenants, JobRepository jobs, JobRunRepository runs,
                        JobExecutionEngine engine, SystemLoadGuard loadGuard,
                        @Value("${ingestion.jobs.run-retention-days:90}") int retentionDays) {
        this.tenants = tenants;
        this.jobs = jobs;
        this.runs = runs;
        this.engine = engine;
        this.loadGuard = loadGuard;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${ingestion.jobs.scheduler-interval-ms:15000}",
               initialDelayString = "${ingestion.jobs.initial-delay-ms:20000}")
    public void tick() {
        if (loadGuard.overloaded()) {
            return; // req 7.4 — the guard already logged; due jobs stay queued
        }
        tenants.forEachTenant(tenantId -> {
            String now = Instant.now().toString();
            fireDueJobs(now);
            fireDueRetries(now);
        });
    }

    private void fireDueJobs(String now) {
        for (Map<String, Object> job : jobs.findDue(now)) {
            String jobId = str(job, "id");
            // Advance the schedule pointer FIRST — a run outliving the tick
            // interval must not be triggered again by the next tick.
            String next = ScheduleType.CRON.name().equals(str(job, "scheduleType"))
                    ? JobService.computeNextRunAt(ScheduleType.CRON, str(job, "cron"), "", Instant.now())
                    : ""; // ONE_TIME fired — clear the pointer (req 4.2)
            jobs.updateNextRunAt(jobId, next);
            log.info("Schedule due for job '{}' — triggering (next occurrence: {})",
                    str(job, "name"), next.isBlank() ? "none" : next);
            engine.trigger(job, "SCHEDULE");
        }
    }

    private void fireDueRetries(String now) {
        for (Map<String, Object> run : runs.findDueRetries(now)) {
            Map<String, Object> job = jobs.findById(str(run, "jobId"));
            if (job == null) {
                runs.cancelQueued(str(run, "id")); // job deleted since the retry was queued
                continue;
            }
            if (!Boolean.TRUE.equals(job.get("enabled")) || Boolean.TRUE.equals(job.get("paused"))) {
                continue; // req 4.8 — paused/disabled jobs keep the retry queued for later
            }
            log.info("Retry due for job '{}' (run {}, attempt {})",
                    str(job, "name"), str(run, "id"), run.get("attempt"));
            engine.submitRetry(run, job);
        }
    }

    /** Requirement 7.6 — daily run-history retention (03:10 to dodge backup windows). */
    @Scheduled(cron = "${ingestion.jobs.prune-cron:0 10 3 * * *}")
    public void pruneRunHistory() {
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        tenants.forEachTenant(tenantId -> {
            runs.pruneFinishedBefore(cutoff);
            log.debug("Pruned ingest run history older than {} for tenant {}", cutoff, tenantId);
        });
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
