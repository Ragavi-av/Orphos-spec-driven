package io.orphos.eip.dataingestion.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.dataingestion.vo.JobDtos.RunView;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped persistence for {@code IngestJobRun} — one row per job
 * execution, updated as the run progresses. These rows are the execution
 * tracking of requirement 4.4, the pollable progress of requirement 5.4, the
 * completion summary of requirement 5.6 AND the persisted audit trail of
 * requirement 6.8 — a run row is never mutated after it reaches a terminal
 * status.
 *
 * <p>Queued retries (req 4.6) are also run rows: status {@code QUEUED} with a
 * non-blank {@code nextRetryAt}; the scheduler claims and executes them once
 * the backoff elapses, so retries survive a service restart.</p>
 */
@Repository
public class JobRunRepository {

    private final ArcadeDbTemplate arcade;

    public JobRunRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    /** Create a QUEUED run row. {@code nextRetryAt} is blank except for pending retries. */
    public void insert(String runId, String jobId, String jobName, int attempt,
                       String triggeredBy, String nextRetryAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", runId);
        p.put("tenantId", tenantId());
        p.put("jobId", jobId);
        p.put("jobName", jobName);
        p.put("attempt", attempt);
        p.put("status", "QUEUED");
        p.put("triggeredBy", triggeredBy);
        p.put("nextRetryAt", nextRetryAt == null ? "" : nextRetryAt);
        p.put("startedAt", "");
        p.put("finishedAt", "");
        p.put("recordsRead", 0L);
        p.put("recordsWritten", 0L);
        p.put("recordsFailed", 0L);
        p.put("totalRecords", -1L);
        p.put("progressPercent", -1);
        p.put("error", "");
        p.put("createdAt", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO IngestJobRun SET id = :id, tenantId = :tenantId, jobId = :jobId, " +
                "jobName = :jobName, attempt = :attempt, status = :status, triggeredBy = :triggeredBy, " +
                "nextRetryAt = :nextRetryAt, startedAt = :startedAt, finishedAt = :finishedAt, " +
                "recordsRead = :recordsRead, recordsWritten = :recordsWritten, " +
                "recordsFailed = :recordsFailed, totalRecords = :totalRecords, " +
                "progressPercent = :progressPercent, error = :error, createdAt = :createdAt", p);
    }

    public void markRunning(String runId) {
        arcade.commandNoResult(
                "UPDATE IngestJobRun SET status = 'RUNNING', startedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", runId, "now", Instant.now().toString()));
    }

    /** Requirement 5.4 — flushed once per batch so GET /runs/{id} shows live progress. */
    public void updateProgress(String runId, long read, long written, long failed,
                               long total, int progressPercent) {
        arcade.commandNoResult(
                "UPDATE IngestJobRun SET recordsRead = :read, recordsWritten = :written, " +
                "recordsFailed = :failed, totalRecords = :total, progressPercent = :pct " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", runId, "read", read, "written", written,
                        "failed", failed, "total", total, "pct", progressPercent));
    }

    /** Terminal update — the completion summary of requirement 5.6. */
    public void finish(String runId, String status, long read, long written, long failed,
                       long total, int progressPercent, String error) {
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId());
        p.put("id", runId);
        p.put("status", status);
        p.put("finishedAt", Instant.now().toString());
        p.put("read", read);
        p.put("written", written);
        p.put("failed", failed);
        p.put("total", total);
        p.put("pct", progressPercent);
        p.put("error", error == null ? "" : error);
        arcade.commandNoResult(
                "UPDATE IngestJobRun SET status = :status, finishedAt = :finishedAt, " +
                "recordsRead = :read, recordsWritten = :written, recordsFailed = :failed, " +
                "totalRecords = :total, progressPercent = :pct, error = :error " +
                "WHERE tenantId = :tenantId AND id = :id", p);
    }

    public Map<String, Object> findById(String runId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestJobRun WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", runId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Recent-first run history for one job (requirement 4.4). */
    public List<Map<String, Object>> listForJob(String jobId, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return arcade.query(
                "SELECT * FROM IngestJobRun WHERE tenantId = :tenantId AND jobId = :jobId " +
                "ORDER BY createdAt DESC LIMIT " + capped,
                Map.of("tenantId", tenantId(), "jobId", jobId));
    }

    /** The most recent run row for a job, or {@code null} — surfaced on JobView. */
    public Map<String, Object> latestForJob(String jobId) {
        List<Map<String, Object>> rows = listForJob(jobId, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Queued retries whose backoff has elapsed (req 4.6) — picked up by the scheduler tick. */
    public List<Map<String, Object>> findDueRetries(String nowIso) {
        return arcade.query(
                "SELECT * FROM IngestJobRun WHERE tenantId = :tenantId AND status = 'QUEUED' " +
                "AND nextRetryAt <> '' AND nextRetryAt <= :now",
                Map.of("tenantId", tenantId(), "now", nowIso));
    }

    /** Claim a due retry before submitting it, so the next tick can't double-execute it. */
    public void claim(String runId) {
        arcade.commandNoResult(
                "UPDATE IngestJobRun SET nextRetryAt = '' WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", runId));
    }

    /** Requirement 5.5 — cancel a run that is still queued (e.g. a pending retry). */
    public void cancelQueued(String runId) {
        arcade.commandNoResult(
                "UPDATE IngestJobRun SET status = 'CANCELLED', nextRetryAt = '', finishedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id AND status = 'QUEUED'",
                Map.of("tenantId", tenantId(), "id", runId, "now", Instant.now().toString()));
    }

    /** Requirement 7.6 — prune terminal run history older than the retention window. */
    public void pruneFinishedBefore(String cutoffIso) {
        arcade.commandNoResult(
                "DELETE FROM IngestJobRun WHERE tenantId = :tenantId AND createdAt < :cutoff " +
                "AND status <> 'RUNNING' AND status <> 'QUEUED'",
                Map.of("tenantId", tenantId(), "cutoff", cutoffIso));
    }

    public RunView toView(Map<String, Object> row) {
        return new RunView(
                str(row, "id"),
                str(row, "jobId"),
                str(row, "jobName"),
                intOf(row, "attempt", 0),
                str(row, "status"),
                str(row, "triggeredBy"),
                str(row, "startedAt"),
                str(row, "finishedAt"),
                longOf(row, "recordsRead", 0L),
                longOf(row, "recordsWritten", 0L),
                longOf(row, "recordsFailed", 0L),
                longOf(row, "totalRecords", -1L),
                intOf(row, "progressPercent", -1),
                str(row, "error"));
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    private static long longOf(Map<String, Object> row, String field, long def) {
        return row.get(field) instanceof Number n ? n.longValue() : def;
    }

    private static int intOf(Map<String, Object> row, String field, int def) {
        return row.get(field) instanceof Number n ? n.intValue() : def;
    }
}
