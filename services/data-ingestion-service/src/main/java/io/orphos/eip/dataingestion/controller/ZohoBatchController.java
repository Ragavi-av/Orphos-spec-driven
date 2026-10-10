package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.repository.JobRepository;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API for the Zoho batch pipeline — deliberately separate from {@code JobController}
 * (which drives the existing {@code JobScheduler}/{@code JobExecutionEngine} pipeline): this is a
 * second, independent way to run a Zoho {@code IngestJob}, on demand only (no scheduler ties into
 * it). Config (job/mapping/data-source) is read from the same ArcadeDB-backed repositories the
 * rest of the admin API uses — read-only reuse, no separate bookkeeping for what a job IS, only
 * for how a Zoho run executes.
 *
 * <p>Run history for this pipeline lives only in Spring Batch's own embedded, in-memory
 * {@code JobRepository} (see {@code io.orphos.eip.dataingestion.config.ZohoBatchConfig}) — it
 * does not survive a service restart, unlike the existing engine's ArcadeDB-persisted run rows.
 * Note the {@code JobRepository} field below is THIS package's ArcadeDB-backed repository, not
 * Spring Batch's own same-named interface — no import collision here since this class never
 * needs Spring Batch's {@code JobRepository} directly.</p>
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/zoho-batch")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class ZohoBatchController {

    private final JobLauncher jobLauncher;
    private final Job zohoIngestJob;
    private final JobExplorer jobExplorer;
    private final JobRepository jobs;
    private final MappingRepository mappings;
    private final DataSourceRepository sources;

    public ZohoBatchController(JobLauncher jobLauncher, Job zohoIngestJob, JobExplorer jobExplorer,
                               JobRepository jobs, MappingRepository mappings, DataSourceRepository sources) {
        this.jobLauncher = jobLauncher;
        this.zohoIngestJob = zohoIngestJob;
        this.jobExplorer = jobExplorer;
        this.jobs = jobs;
        this.mappings = mappings;
        this.sources = sources;
    }

    @PostMapping("/jobs/{jobId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String jobId) {
        Map<String, Object> jobRow = jobs.findById(jobId);
        if (jobRow == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, Object> mappingRow = mappings.findById(str(jobRow, "mappingId"));
        if (mappingRow == null) {
            throw new IllegalStateException("Mapping " + str(jobRow, "mappingId") + " no longer exists");
        }
        MappingView mapping = mappings.toView(mappingRow);
        Map<String, Object> sourceRow = sources.findById(mapping.dataSourceId());
        if (sourceRow == null) {
            throw new IllegalStateException("Data source " + mapping.dataSourceId() + " no longer exists");
        }
        String kind = str(sourceRow, "kind");
        if (!SourceKind.ZOHO_ERP.name().equalsIgnoreCase(kind)) {
            throw new IllegalArgumentException(
                    "Job " + jobId + "'s data source is " + kind + ", not ZOHO_ERP — this endpoint is Zoho-only");
        }

        String tenantId = TenantContextHolder.requireTenantId().toString();
        boolean incremental = "INCREMENTAL".equalsIgnoreCase(str(jobRow, "loadMode"));
        String watermark = incremental ? str(jobRow, "watermark") : "";
        String mergeStrategy = str(jobRow, "mergeStrategy");

        JobParameters params = new JobParametersBuilder()
                .addString("jobId", jobId)
                .addString("mappingId", mapping.id())
                .addString("tenantId", tenantId)
                .addString("mergeStrategy", mergeStrategy)
                .addString("incremental", String.valueOf(incremental))
                .addString("watermark", watermark)
                // Spring Batch rejects re-running an identical parameter set; this makes every
                // manual trigger a fresh JobInstance rather than needing a "restart" call.
                .addLong("startedAt", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution;
        try {
            execution = jobLauncher.run(zohoIngestJob, params);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the Zoho batch run: " + e.getMessage(), e);
        }
        return ResponseEntity.accepted().body(Map.of(
                "executionId", execution.getId(),
                "status", execution.getStatus().name()));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long read = 0, written = 0, skipped = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
            skipped += step.getSkipCount();
        }
        return ResponseEntity.ok(Map.of(
                "executionId", execution.getId(),
                "status", execution.getStatus().name(),
                "exitCode", execution.getExitStatus().getExitCode(),
                "readCount", read,
                "writeCount", written,
                "skipCount", skipped,
                "startTime", String.valueOf(execution.getStartTime()),
                "endTime", String.valueOf(execution.getEndTime())));
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
