package io.orphos.eip.dataingestion.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for {@code IngestJob} — one row per configured batch
 * extract/load job (requirement 4). Scheduling state lives on the row too:
 * {@code nextRunAt} (blank = nothing due), {@code paused} (req 4.8 — skipped by
 * the scheduler but keeps its configuration) and {@code watermark} (req 6.2 —
 * the raw max incremental value seen by the last successful run).
 */
// Explicit name: the default "jobRepository" collides with Spring Batch's JobRepository bean.
@Repository("ingestJobRepository")
public class JobRepository {

    private final ArcadeDbTemplate arcade;

    public JobRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    public List<Map<String, Object>> list() {
        return arcade.query(
                "SELECT * FROM IngestJob WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", tenantId()));
    }

    /** The current tenant's job by id, or {@code null}. */
    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestJob WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The current tenant's job by (unique) name, or {@code null}. */
    public Map<String, Object> findByName(String name) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestJob WHERE tenantId = :tenantId AND name = :name LIMIT 1",
                Map.of("tenantId", tenantId(), "name", name));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Jobs whose schedule is due (req 4.3): enabled, not paused, with a
     * non-blank {@code nextRunAt} at or before {@code nowIso}. ISO-8601 UTC
     * instants compare correctly as strings.
     */
    public List<Map<String, Object>> findDue(String nowIso) {
        return arcade.query(
                "SELECT * FROM IngestJob WHERE tenantId = :tenantId AND enabled = true " +
                "AND paused = false AND nextRunAt <> '' AND nextRunAt <= :now",
                Map.of("tenantId", tenantId(), "now", nowIso));
    }

    /**
     * Requirement 4.5 — jobs to auto-trigger after {@code parentJobId}
     * succeeds: dependents must be schedule-less (NONE), enabled and not paused.
     */
    public List<Map<String, Object>> findDependents(String parentJobId) {
        return arcade.query(
                "SELECT * FROM IngestJob WHERE tenantId = :tenantId AND dependsOnJobId = :parent " +
                "AND scheduleType = 'NONE' AND enabled = true AND paused = false",
                Map.of("tenantId", tenantId(), "parent", parentJobId));
    }

    /** Insert a new job; returns the generated id. */
    public String insert(Map<String, Object> fields) {
        String id = "job-" + UUID.randomUUID();
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>(fields);
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("paused", false);
        p.put("watermark", "");
        p.put("createdAt", now);
        p.put("updatedAt", now);
        arcade.commandNoResult(
                "INSERT INTO IngestJob SET id = :id, tenantId = :tenantId, name = :name, " +
                "mappingId = :mappingId, scheduleType = :scheduleType, cron = :cron, runAt = :runAt, " +
                "loadMode = :loadMode, mergeStrategy = :mergeStrategy, batchSize = :batchSize, " +
                "maxRetries = :maxRetries, dependsOnJobId = :dependsOnJobId, enabled = :enabled, " +
                "paused = :paused, watermark = :watermark, nextRunAt = :nextRunAt, " +
                "createdAt = :createdAt, updatedAt = :updatedAt", p);
        return id;
    }

    /** Update job configuration. {@code paused} and {@code watermark} are preserved. */
    public void update(String id, Map<String, Object> fields) {
        Map<String, Object> p = new HashMap<>(fields);
        p.put("tenantId", tenantId());
        p.put("id", id);
        p.put("updatedAt", Instant.now().toString());
        arcade.commandNoResult(
                "UPDATE IngestJob SET name = :name, mappingId = :mappingId, " +
                "scheduleType = :scheduleType, cron = :cron, runAt = :runAt, loadMode = :loadMode, " +
                "mergeStrategy = :mergeStrategy, batchSize = :batchSize, maxRetries = :maxRetries, " +
                "dependsOnJobId = :dependsOnJobId, enabled = :enabled, nextRunAt = :nextRunAt, " +
                "updatedAt = :updatedAt WHERE tenantId = :tenantId AND id = :id", p);
    }

    public void delete(String id) {
        arcade.commandNoResult(
                "DELETE FROM IngestJob WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    /** Requirement 4.8 — pause/resume without touching the rest of the configuration. */
    public void setPaused(String id, boolean paused) {
        arcade.commandNoResult(
                "UPDATE IngestJob SET paused = :paused, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "paused", paused,
                        "now", Instant.now().toString()));
    }

    /** Advance (or clear, for fired ONE_TIME jobs) the schedule pointer. */
    public void updateNextRunAt(String id, String nextRunAt) {
        arcade.commandNoResult(
                "UPDATE IngestJob SET nextRunAt = :next, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "next", nextRunAt == null ? "" : nextRunAt,
                        "now", Instant.now().toString()));
    }

    /** Requirement 6.2 — persist the max incremental value seen by a successful run. */
    public void updateWatermark(String id, String watermark) {
        arcade.commandNoResult(
                "UPDATE IngestJob SET watermark = :wm, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "wm", watermark == null ? "" : watermark,
                        "now", Instant.now().toString()));
    }
}
