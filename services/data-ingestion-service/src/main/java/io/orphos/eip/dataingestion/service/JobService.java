package io.orphos.eip.dataingestion.service;

import io.orphos.eip.dataingestion.component.JobExecutionEngine;
import io.orphos.eip.dataingestion.repository.JobRepository;
import io.orphos.eip.dataingestion.repository.JobRunRepository;
import io.orphos.eip.dataingestion.vo.JobDtos.JobView;
import io.orphos.eip.dataingestion.vo.JobDtos.LoadMode;
import io.orphos.eip.dataingestion.vo.JobDtos.MergeStrategy;
import io.orphos.eip.dataingestion.vo.JobDtos.RunListResponse;
import io.orphos.eip.dataingestion.vo.JobDtos.RunView;
import io.orphos.eip.dataingestion.vo.JobDtos.SaveJobRequest;
import io.orphos.eip.dataingestion.vo.JobDtos.ScheduleType;
import io.orphos.eip.dataingestion.vo.JobDtos.TriggerResponse;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.component.MappingValidator;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.MappingDtos.SaveMappingRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.ValidationReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Save/trigger/pause orchestration for batch jobs (requirements 4 and 5).
 *
 * <p>Schedule expressions are validated at save time with Spring's
 * {@link CronExpression} (req 4.1) — no Quartz; {@code nextRunAt} is computed
 * here and the scheduler simply fires whatever is due. All cron evaluation is
 * UTC so containers and tests agree on the answer.</p>
 */
@Service
public class JobService {

    private static final Logger log = LogManager.getLogger(JobService.class);

    private final JobRepository repo;
    private final JobRunRepository runs;
    private final MappingRepository mappings;
    private final MappingValidator validator;
    private final JobExecutionEngine engine;

    public JobService(JobRepository repo, JobRunRepository runs, MappingRepository mappings,
                      MappingValidator validator, JobExecutionEngine engine) {
        this.repo = repo;
        this.runs = runs;
        this.mappings = mappings;
        this.validator = validator;
        this.engine = engine;
    }

    // --- CRUD ---------------------------------------------------------------

    public List<JobView> list() {
        return repo.list().stream().map(this::toView).toList();
    }

    public JobView create(SaveJobRequest req) {
        Map<String, Object> fields = validateAndResolve(req, null);
        if (repo.findByName(req.name().trim()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A job named '" + req.name().trim() + "' already exists");
        }
        String id = repo.insert(fields);
        log.info("Created ingest job {} ({})", id, req.name());
        return toView(repo.findById(id));
    }

    public JobView update(String id, SaveJobRequest req) {
        require(id);
        Map<String, Object> fields = validateAndResolve(req, id);
        Map<String, Object> byName = repo.findByName(req.name().trim());
        if (byName != null && !id.equals(String.valueOf(byName.get("id")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A job named '" + req.name().trim() + "' already exists");
        }
        repo.update(id, fields);
        return toView(repo.findById(id));
    }

    public void delete(String id) {
        require(id);
        repo.delete(id);
        log.info("Deleted ingest job {}", id);
    }

    public JobView get(String id) {
        return toView(require(id));
    }

    // --- triggering / lifecycle ----------------------------------------------

    /**
     * Requirement 5.1/5.2 — trigger an immediate asynchronous run. The mapping
     * configuration is re-validated first; a mapping that has gone stale since
     * it was saved (dropped column, renamed table) rejects with 422 and no run
     * is created.
     */
    public TriggerResponse runNow(String id) {
        Map<String, Object> job = require(id);
        Map<String, Object> mappingRow = mappings.findById(str(job, "mappingId"));
        if (mappingRow == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The job's mapping " + str(job, "mappingId") + " no longer exists");
        }
        MappingView mapping = mappings.toView(mappingRow);
        ValidationReport report = validator.validate(new SaveMappingRequest(
                mapping.name(), mapping.dataSourceId(), mapping.sourceSchema(), mapping.sourceTable(),
                mapping.targetClass(), mapping.targetKind(), mapping.keyTarget(), mapping.incrementalColumn(),
                mapping.mappings(), mapping.edges(), mapping.enabled()));
        if (!report.valid()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Mapping validation failed: " + String.join("; ", report.errors()));
        }
        String runId = engine.trigger(job, "MANUAL");
        return new TriggerResponse(runId, "QUEUED",
                "Run queued for immediate execution — poll GET /api/data-ingestion/admin/runs/" + runId);
    }

    /** Requirement 4.8 — pause keeps the configuration; the scheduler skips paused jobs. */
    public JobView pause(String id) {
        require(id);
        repo.setPaused(id, true);
        return toView(repo.findById(id));
    }

    /**
     * Requirement 4.8 — resume. CRON schedules get a fresh {@code nextRunAt}
     * from now, so a long pause doesn't fire a backlog of missed ticks.
     */
    public JobView resume(String id) {
        Map<String, Object> job = require(id);
        repo.setPaused(id, false);
        if (ScheduleType.CRON.name().equals(str(job, "scheduleType"))) {
            repo.updateNextRunAt(id, computeNextRunAt(ScheduleType.CRON, str(job, "cron"), "", Instant.now()));
        }
        return toView(repo.findById(id));
    }

    public RunListResponse listRuns(String jobId, int limit) {
        require(jobId);
        return new RunListResponse(runs.listForJob(jobId, limit).stream().map(runs::toView).toList());
    }

    /** Requirement 5.4 — poll a run's live progress / final summary. */
    public RunView getRun(String runId) {
        Map<String, Object> row = runs.findById(runId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
        return runs.toView(row);
    }

    /** Requirement 5.5 — request cancellation of a queued or running run. */
    public TriggerResponse cancelRun(String runId) {
        Map<String, Object> row = runs.findById(runId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
        String status = str(row, "status");
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run " + runId + " already finished (" + status + ")");
        }
        boolean flagged = engine.cancel(runId);
        if (!flagged && "QUEUED".equals(status)) {
            runs.cancelQueued(runId); // a persisted retry not yet claimed by any worker
        }
        return new TriggerResponse(runId, str(runs.findById(runId), "status"),
                "Cancellation requested — the run stops at the next row boundary");
    }

    // --- validation / resolution ----------------------------------------------

    /**
     * Cross-field validation (422 on problems, mirroring MappingController)
     * and resolution to the persisted field map, including the computed
     * {@code nextRunAt}.
     */
    private Map<String, Object> validateAndResolve(SaveJobRequest req, String selfId) {
        ScheduleType scheduleType = parseScheduleType(req.scheduleType());
        String cron = nz(req.cron());
        String runAt = nz(req.runAt());
        if (scheduleType == ScheduleType.CRON) {
            if (cron.isBlank()) {
                throw unprocessable("cron is required for CRON schedules");
            }
            if (!CronExpression.isValidExpression(cron)) {         // req 4.1
                throw unprocessable("Invalid cron expression '" + cron
                        + "' — expected Spring's six-field format, e.g. '0 0 2 * * *'");
            }
        }
        if (scheduleType == ScheduleType.ONE_TIME) {
            if (runAt.isBlank()) {
                throw unprocessable("runAt is required for ONE_TIME schedules");
            }
            try {
                runAt = Instant.parse(runAt).toString();           // req 4.2 — normalised ISO instant
            } catch (DateTimeParseException e) {
                throw unprocessable("runAt must be an ISO-8601 instant, e.g. '2026-08-01T02:00:00Z'");
            }
        }

        Map<String, Object> mappingRow = mappings.findById(req.mappingId());
        if (mappingRow == null) {
            throw unprocessable("Unknown mapping: " + req.mappingId());
        }
        MappingView mapping = mappings.toView(mappingRow);

        LoadMode loadMode = parseLoadMode(req.loadMode());
        if (loadMode == LoadMode.INCREMENTAL && mapping.incrementalColumn().isBlank()) {
            throw unprocessable("INCREMENTAL load mode requires the mapping to define an incrementalColumn");
        }
        MergeStrategy merge = resolveMergeStrategy(req.mergeStrategy(), mapping);
        if (merge == MergeStrategy.UPSERT && mapping.keyTarget().isBlank()) {
            throw unprocessable("UPSERT merge strategy requires the mapping to define a keyTarget");
        }

        String dependsOn = nz(req.dependsOnJobId());
        if (!dependsOn.isBlank()) {                                // req 4.5
            if (dependsOn.equals(selfId)) {
                throw unprocessable("A job cannot depend on itself");
            }
            if (repo.findById(dependsOn) == null) {
                throw unprocessable("Unknown dependsOnJobId: " + dependsOn);
            }
        }

        Map<String, Object> f = new HashMap<>();
        f.put("name", req.name().trim());
        f.put("mappingId", req.mappingId());
        f.put("scheduleType", scheduleType.name());
        f.put("cron", cron);
        f.put("runAt", runAt);
        f.put("loadMode", loadMode.name());
        f.put("mergeStrategy", merge.name());
        f.put("batchSize", req.batchSize() == null ? 0 : req.batchSize());     // 0 = service default
        f.put("maxRetries", req.maxRetries() == null ? -1 : req.maxRetries()); // -1 = service default
        f.put("dependsOnJobId", dependsOn);
        f.put("enabled", req.enabled());
        f.put("nextRunAt", computeNextRunAt(scheduleType, cron, runAt, Instant.now()));
        return f;
    }

    /**
     * The next moment a job's schedule is due, as an ISO-8601 UTC instant —
     * or {@code ""} for schedule-less jobs. Static and clock-injected so the
     * cron arithmetic is directly unit-testable (req 4.1/4.2).
     */
    public static String computeNextRunAt(ScheduleType type, String cron, String runAt, Instant from) {
        return switch (type) {
            case NONE -> "";
            case ONE_TIME -> Instant.parse(runAt).toString();
            case CRON -> {
                OffsetDateTime next = CronExpression.parse(cron)
                        .next(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
                yield next == null ? "" : next.toInstant().toString();
            }
        };
    }

    public Map<String, Object> require(String id) {
        Map<String, Object> row = repo.findById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job: " + id);
        }
        return row;
    }

    public JobView toView(Map<String, Object> row) {
        Map<String, Object> lastRun = runs.latestForJob(str(row, "id"));
        Integer batchSize = positiveOrNull(row.get("batchSize"));
        Object retries = row.get("maxRetries");
        Integer maxRetries = retries instanceof Number n && n.intValue() >= 0 ? n.intValue() : null;
        return new JobView(
                str(row, "id"),
                str(row, "name"),
                str(row, "mappingId"),
                str(row, "scheduleType"),
                str(row, "cron"),
                str(row, "runAt"),
                str(row, "loadMode"),
                str(row, "mergeStrategy"),
                batchSize,
                maxRetries,
                str(row, "dependsOnJobId"),
                Boolean.TRUE.equals(row.get("enabled")),
                Boolean.TRUE.equals(row.get("paused")),
                str(row, "watermark"),
                str(row, "nextRunAt"),
                lastRun == null ? "" : str(lastRun, "status"),
                lastRun == null ? "" : str(lastRun, "startedAt"),
                str(row, "createdAt"),
                str(row, "updatedAt"));
    }

    // --- parsing helpers -------------------------------------------------------

    public static ScheduleType parseScheduleType(String raw) {
        if (raw == null || raw.isBlank()) return ScheduleType.NONE;
        try {
            return ScheduleType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown schedule type '" + raw + "' — expected NONE, CRON or ONE_TIME");
        }
    }

    public static LoadMode parseLoadMode(String raw) {
        if (raw == null || raw.isBlank()) return LoadMode.FULL;
        try {
            return LoadMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown load mode '" + raw + "' — expected FULL or INCREMENTAL");
        }
    }

    /** Blank merge strategy derives from the mapping: keyed mappings upsert, keyless insert. */
    private static MergeStrategy resolveMergeStrategy(String raw, MappingView mapping) {
        if (raw == null || raw.isBlank()) {
            return mapping.keyTarget().isBlank() ? MergeStrategy.INSERT : MergeStrategy.UPSERT;
        }
        try {
            return MergeStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown merge strategy '" + raw + "' — expected UPSERT or INSERT");
        }
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static Integer positiveOrNull(Object v) {
        return v instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
