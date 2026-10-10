package io.orphos.eip.ingest.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.component.SecretRefService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for {@code OkgConnection} — one row per (tenant, source),
 * currently only {@code source = "JIRA"}. The Jira API token itself is never
 * stored here — only the vault reference ({@code apiTokenRef}); callers resolve
 * the actual secret at request time via {@link io.orphos.eip.ingest.component.SecretRefService}.
 */
@Repository
public class OkgConnectionRepository {

    private final ArcadeDbTemplate arcade;

    public OkgConnectionRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    /** The current tenant's connection for {@code source}, or {@code null} if unconfigured. */
    public Map<String, Object> findConnection(String source) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM OkgConnection WHERE tenantId = :tenantId AND source = :source LIMIT 1",
                Map.of("tenantId", tenantId(), "source", source));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listConnections() {
        return arcade.query(
                "SELECT * FROM OkgConnection WHERE tenantId = :tenantId ORDER BY source",
                Map.of("tenantId", tenantId()));
    }

    /**
     * Upsert the connection for {@code source}. {@code apiTokenRef} is preserved
     * when null/blank on update (so re-saving base URL/project keys doesn't wipe
     * a previously-stored credential).
     */
    public void saveConnection(String source, String baseUrl, String email, String apiTokenRef,
                                String projectKeys, boolean enabled) {
        String tenant = tenantId();
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenant);
        p.put("source", source);
        p.put("baseUrl", baseUrl == null ? "" : baseUrl);
        p.put("email", email == null ? "" : email);
        p.put("projectKeys", projectKeys == null ? "" : projectKeys);
        p.put("enabled", enabled);
        p.put("updatedAt", now);

        boolean exists = findConnection(source) != null;
        if (exists) {
            String secretClause = "";
            if (apiTokenRef != null && !apiTokenRef.isBlank()) {
                p.put("apiTokenRef", apiTokenRef);
                secretClause = "apiTokenRef = :apiTokenRef, ";
            }
            arcade.commandNoResult(
                    "UPDATE OkgConnection SET baseUrl = :baseUrl, email = :email, " + secretClause +
                    "projectKeys = :projectKeys, enabled = :enabled, updatedAt = :updatedAt " +
                    "WHERE tenantId = :tenantId AND source = :source", p);
        } else {
            p.put("id", "okgc-" + UUID.randomUUID());
            p.put("apiTokenRef", apiTokenRef == null ? "" : apiTokenRef);
            p.put("createdAt", now);
            p.put("lastSyncAt", "");
            p.put("lastSyncStatus", "NEVER_RUN");
            p.put("lastSyncError", "");
            arcade.commandNoResult(
                    "INSERT INTO OkgConnection SET id = :id, tenantId = :tenantId, source = :source, " +
                    "baseUrl = :baseUrl, email = :email, apiTokenRef = :apiTokenRef, " +
                    "projectKeys = :projectKeys, enabled = :enabled, lastSyncAt = :lastSyncAt, " +
                    "lastSyncStatus = :lastSyncStatus, lastSyncError = :lastSyncError, " +
                    "createdAt = :createdAt, updatedAt = :updatedAt", p);
        }
    }

    public void recordSyncResult(String source, boolean success, String errorMessage) {
        arcade.commandNoResult(
                "UPDATE OkgConnection SET lastSyncAt = :now, lastSyncStatus = :status, lastSyncError = :error " +
                "WHERE tenantId = :tenantId AND source = :source",
                Map.of("tenantId", tenantId(), "source", source, "now", Instant.now().toString(),
                        "status", success ? "SUCCESS" : "FAILED", "error", errorMessage == null ? "" : errorMessage));
    }

    /** Every tenant with at least one enabled connection for {@code source} — used by the sync scheduler. */
    public List<UUID> tenantIdsWithEnabledConnection(String source) {
        // Cross-tenant scan: intentionally NOT filtered by TenantContextHolder —
        // the scheduler needs to know which tenants to iterate before it can
        // bind a tenant context. Safe because it returns only tenantId, no
        // per-tenant data, and platform-core-service's discovery endpoint plus
        // TenantTaskExecutor already gate which tenants are considered active.
        List<Map<String, Object>> rows = arcade.query(
                "SELECT tenantId FROM OkgConnection WHERE source = :source AND enabled = true",
                Map.of("source", source));
        return rows.stream()
                .map(r -> UUID.fromString(String.valueOf(r.get("tenantId"))))
                .distinct()
                .toList();
    }
}
