package io.orphos.eip.dataingestion.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped persistence for {@code IngestAuditLog} — the user-action audit
 * trail of requirement 8.4. Append + newest-first read only; audit entries are
 * never updated or deleted through the API.
 */
@Repository
public class AuditLogRepository {

    /** Hard ceiling on a single audit page — keeps the read endpoint bounded. */
    static final int MAX_LIMIT = 500;

    private final ArcadeDbTemplate arcade;

    public AuditLogRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    /**
     * Append one audit entry. {@code detail} must already be credential-free —
     * callers pass method+path summaries, never request bodies.
     */
    public void insert(String tenantId, String actor, String action, String detail) {
        arcade.commandNoResult(
                "INSERT INTO IngestAuditLog SET id = :id, tenantId = :tenantId, actor = :actor, " +
                "action = :action, detail = :detail, at = :at",
                Map.of("id", "audit-" + UUID.randomUUID(),
                        "tenantId", tenantId,
                        "actor", actor == null ? "" : actor,
                        "action", action,
                        "detail", detail == null ? "" : detail,
                        "at", Instant.now().toString()));
    }

    /** The current tenant's newest audit entries, newest first. */
    public List<Map<String, Object>> listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        // LIMIT is interpolated (not bound) — it is a clamped int, never user text.
        return arcade.query(
                "SELECT * FROM IngestAuditLog WHERE tenantId = :tenantId ORDER BY at DESC LIMIT " + n,
                Map.of("tenantId", TenantContextHolder.requireTenantId().toString()));
    }
}
