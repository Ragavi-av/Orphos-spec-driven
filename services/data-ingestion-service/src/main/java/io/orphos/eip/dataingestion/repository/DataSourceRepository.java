package io.orphos.eip.dataingestion.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.dataingestion.component.SecretRefService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for {@code IngestDataSource} — one row per configured
 * enterprise data source. The credential itself is never stored here — only
 * the vault reference ({@code credentialRef}); callers resolve the actual
 * secret at use time via {@link io.orphos.eip.dataingestion.component.SecretRefService}.
 */
@Repository
public class DataSourceRepository {

    private final ArcadeDbTemplate arcade;

    public DataSourceRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    public List<Map<String, Object>> list() {
        return arcade.query(
                "SELECT * FROM IngestDataSource WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", tenantId()));
    }

    /** The current tenant's data source by id, or {@code null}. */
    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestDataSource WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The current tenant's data source by (unique) name, or {@code null}. */
    public Map<String, Object> findByName(String name) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestDataSource WHERE tenantId = :tenantId AND name = :name LIMIT 1",
                Map.of("tenantId", tenantId(), "name", name));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Insert a new data source; returns the generated id. */
    public String insert(Map<String, Object> fields, String credentialRef) {
        String id = "ds-" + UUID.randomUUID();
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>(fields);
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("credentialRef", credentialRef == null ? "" : credentialRef);
        p.put("lastTestAt", "");
        p.put("lastTestStatus", "NEVER_TESTED");
        p.put("lastTestError", "");
        p.put("createdAt", now);
        p.put("updatedAt", now);
        p.putIfAbsent("configJson", "");
        arcade.commandNoResult(
                "INSERT INTO IngestDataSource SET id = :id, tenantId = :tenantId, name = :name, " +
                "kind = :kind, jdbcUrl = :jdbcUrl, driverClass = :driverClass, username = :username, " +
                "credentialRef = :credentialRef, baseUrl = :baseUrl, authType = :authType, " +
                "authHeaderName = :authHeaderName, testPath = :testPath, configJson = :configJson, " +
                "maxPoolSize = :maxPoolSize, " +
                "enabled = :enabled, lastTestAt = :lastTestAt, lastTestStatus = :lastTestStatus, " +
                "lastTestError = :lastTestError, createdAt = :createdAt, updatedAt = :updatedAt", p);
        return id;
    }

    /**
     * Update an existing data source. {@code credentialRef} is preserved when
     * null (so re-saving the URL or pool size doesn't wipe a stored credential).
     */
    public void update(String id, Map<String, Object> fields, String credentialRef) {
        Map<String, Object> p = new HashMap<>(fields);
        p.put("tenantId", tenantId());
        p.put("id", id);
        p.put("updatedAt", Instant.now().toString());
        String secretClause = "";
        if (credentialRef != null) {
            p.put("credentialRef", credentialRef);
            secretClause = "credentialRef = :credentialRef, ";
        }
        p.putIfAbsent("configJson", "");
        arcade.commandNoResult(
                "UPDATE IngestDataSource SET name = :name, kind = :kind, jdbcUrl = :jdbcUrl, " +
                "driverClass = :driverClass, username = :username, " + secretClause +
                "baseUrl = :baseUrl, authType = :authType, authHeaderName = :authHeaderName, " +
                "testPath = :testPath, configJson = :configJson, maxPoolSize = :maxPoolSize, " +
                "enabled = :enabled, updatedAt = :updatedAt " +
                "WHERE tenantId = :tenantId AND id = :id", p);
    }

    public void delete(String id) {
        arcade.commandNoResult(
                "DELETE FROM IngestDataSource WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    public void recordTestResult(String id, boolean success, String errorMessage) {
        arcade.commandNoResult(
                "UPDATE IngestDataSource SET lastTestAt = :now, lastTestStatus = :status, lastTestError = :error " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "now", Instant.now().toString(),
                        "status", success ? "SUCCESS" : "FAILED",
                        "error", errorMessage == null ? "" : errorMessage));
    }
}
