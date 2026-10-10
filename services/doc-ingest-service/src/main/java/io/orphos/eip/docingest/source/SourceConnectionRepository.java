package io.orphos.eip.docingest.source;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.docingest.arcade.DocArcadeClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for {@code DocSourceConnection} — one row per configured
 * remote folder source. The connector's credentials are never stored here; only
 * the vault reference ({@code credentialRef}) is persisted (see
 * {@link io.orphos.eip.docingest.secrets.SecretRefService}).
 *
 * <p>Mirrors okg-ingest-service's {@code OkgConnectionRepository}: per-tenant
 * queries via {@link TenantContextHolder}, plus one deliberate cross-tenant scan
 * ({@link #tenantIdsWithEnabledConnection()}) the scheduler uses to decide which
 * tenants to bind before crawling.</p>
 */
@Repository
public class SourceConnectionRepository {

    private final DocArcadeClient arcade;

    public SourceConnectionRepository(DocArcadeClient arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    public List<Map<String, Object>> list() {
        return arcade.query(
                "SELECT * FROM DocSourceConnection WHERE tenantId = :tenantId ORDER BY displayName",
                Map.of("tenantId", tenantId()));
    }

    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM DocSourceConnection WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listEnabledForCurrentTenant() {
        return arcade.query(
                "SELECT * FROM DocSourceConnection WHERE tenantId = :tenantId AND enabled = true",
                Map.of("tenantId", tenantId()));
    }

    /** Insert a new connection; returns the generated id. */
    public String insert(String kind, String displayName, String configJson, String credentialRef,
                         String defaultDepartment, String defaultCategory, String defaultFolder,
                         String documentType, boolean enabled) {
        String now = Instant.now().toString();
        String id = "dsc-" + UUID.randomUUID();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("kind", kind);
        p.put("displayName", nz(displayName));
        p.put("configJson", nz(configJson));
        p.put("credentialRef", nz(credentialRef));
        p.put("defaultDepartment", nz(defaultDepartment));
        p.put("defaultCategory", nz(defaultCategory));
        p.put("defaultFolder", nz(defaultFolder));
        p.put("documentType", nz(documentType));
        p.put("enabled", enabled);
        p.put("watermark", "");
        p.put("lastSyncAt", "");
        p.put("lastSyncStatus", "NEVER_RUN");
        p.put("lastSyncError", "");
        p.put("createdAt", now);
        p.put("updatedAt", now);
        arcade.commandNoResult(
                "INSERT INTO DocSourceConnection SET id = :id, tenantId = :tenantId, kind = :kind, " +
                "displayName = :displayName, configJson = :configJson, credentialRef = :credentialRef, " +
                "defaultDepartment = :defaultDepartment, defaultCategory = :defaultCategory, " +
                "defaultFolder = :defaultFolder, documentType = :documentType, enabled = :enabled, " +
                "watermark = :watermark, lastSyncAt = :lastSyncAt, lastSyncStatus = :lastSyncStatus, " +
                "lastSyncError = :lastSyncError, createdAt = :createdAt, updatedAt = :updatedAt", p);
        return id;
    }

    /**
     * Update mutable fields of an existing connection. {@code credentialRef} is
     * preserved when null (so editing config without re-entering the secret keeps
     * the stored credential).
     */
    public void update(String id, String displayName, String configJson, String credentialRef,
                       String defaultDepartment, String defaultCategory, String defaultFolder,
                       String documentType, boolean enabled) {
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId());
        p.put("id", id);
        p.put("displayName", nz(displayName));
        p.put("configJson", nz(configJson));
        p.put("defaultDepartment", nz(defaultDepartment));
        p.put("defaultCategory", nz(defaultCategory));
        p.put("defaultFolder", nz(defaultFolder));
        p.put("documentType", nz(documentType));
        p.put("enabled", enabled);
        p.put("updatedAt", Instant.now().toString());
        String secretClause = "";
        if (credentialRef != null && !credentialRef.isBlank()) {
            p.put("credentialRef", credentialRef);
            secretClause = "credentialRef = :credentialRef, ";
        }
        arcade.commandNoResult(
                "UPDATE DocSourceConnection SET displayName = :displayName, configJson = :configJson, " +
                secretClause +
                "defaultDepartment = :defaultDepartment, defaultCategory = :defaultCategory, " +
                "defaultFolder = :defaultFolder, documentType = :documentType, enabled = :enabled, " +
                "updatedAt = :updatedAt WHERE tenantId = :tenantId AND id = :id", p);
    }

    public void delete(String id) {
        arcade.commandNoResult(
                "DELETE FROM DocSourceConnection WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    /** Persist the high-water mark (max lastModified) after a successful crawl. */
    public void updateWatermark(String id, String watermark) {
        arcade.commandNoResult(
                "UPDATE DocSourceConnection SET watermark = :watermark WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "watermark", watermark == null ? "" : watermark));
    }

    public void recordSyncResult(String id, boolean success, String errorMessage) {
        arcade.commandNoResult(
                "UPDATE DocSourceConnection SET lastSyncAt = :now, lastSyncStatus = :status, " +
                "lastSyncError = :error WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "now", Instant.now().toString(),
                        "status", success ? "SUCCESS" : "FAILED",
                        "error", errorMessage == null ? "" : errorMessage));
    }

    /** Every tenant with at least one enabled connection — used by the crawl scheduler (cross-tenant). */
    public List<UUID> tenantIdsWithEnabledConnection() {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT tenantId FROM DocSourceConnection WHERE enabled = true", Map.of());
        return rows.stream()
                .map(r -> UUID.fromString(String.valueOf(r.get("tenantId"))))
                .distinct()
                .toList();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
