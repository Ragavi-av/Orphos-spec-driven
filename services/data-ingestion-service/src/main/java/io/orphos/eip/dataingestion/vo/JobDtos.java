package io.orphos.eip.dataingestion.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Request/response shapes for batch extract/load jobs (requirements 4 and 5). */
public final class JobDtos {

    private JobDtos() {}

    /**
     * How a job gets onto the schedule: CRON = recurring (req 4.1),
     * ONE_TIME = single future execution (req 4.2), NONE = manual or
     * dependency-triggered only (req 4.5).
     */
    public enum ScheduleType { NONE, CRON, ONE_TIME }

    /** Requirements 6.2/6.3 — how much of the source is extracted per run. */
    public enum LoadMode { FULL, INCREMENTAL }

    /** Requirement 6.4 — how extracted rows land in the target class. */
    public enum MergeStrategy { UPSERT, INSERT }

    public record SaveJobRequest(
            @NotBlank String name,
            @NotBlank String mappingId,
            String scheduleType,   // ScheduleType name; NONE when blank
            String cron,           // CRON only — Spring six-field cron expression
            String runAt,          // ONE_TIME only — ISO-8601 instant
            String loadMode,       // LoadMode name; FULL when blank
            String mergeStrategy,  // MergeStrategy name; derived from the mapping's keyTarget when blank
            Integer batchSize,     // rows per progress flush; service default when null (req 6.7)
            Integer maxRetries,    // retry ceiling; service default when null (req 4.6)
            String dependsOnJobId, // req 4.5 — triggered after this job's run succeeds
            boolean enabled) {}

    public record JobView(
            String id,
            String name,
            String mappingId,
            String scheduleType,
            String cron,
            String runAt,
            String loadMode,
            String mergeStrategy,
            Integer batchSize,     // null = service default
            Integer maxRetries,    // null = service default
            String dependsOnJobId,
            boolean enabled,
            boolean paused,        // req 4.8 — paused jobs keep their configuration
            String watermark,      // req 6.2 — last incremental high-water mark
            String nextRunAt,
            String lastRunStatus,
            String lastRunAt,
            String createdAt,
            String updatedAt) {}

    public record RunView(
            String id,
            String jobId,
            String jobName,
            int attempt,
            String status,       // QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
            String triggeredBy,  // SCHEDULE | MANUAL | RETRY | DEPENDENCY
            String startedAt,
            String finishedAt,
            long recordsRead,
            long recordsWritten,
            long recordsFailed,
            long totalRecords,   // -1 = unknown
            int progressPercent, // -1 = unknown
            String error) {}

    /** Requirement 5.1 — immediate-trigger acknowledgement; poll GET /runs/{runId} for progress. */
    public record TriggerResponse(String runId, String status, String message) {}

    public record JobListResponse(List<JobView> jobs) {}

    public record RunListResponse(List<RunView> runs) {}
}
