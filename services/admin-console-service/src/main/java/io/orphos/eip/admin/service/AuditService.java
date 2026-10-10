package io.orphos.eip.admin.service;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A8 - Admin console audit logging.
 *
 * <p>Writes to the same {@code AuthAuditLog} ArcadeDB document type that
 * platform-core-service's identical class writes to and reads back for its
 * audit-log viewer — the two services share the underlying ArcadeDB database
 * (as every service in this platform already does), so this is a
 * same-table, different-writer duplication rather than a network call.
 * Kept at the same package path as platform-core-service's AuditService so
 * branding/email call sites needed no changes when admin-service split.</p>
 *
 * <p>Only {@code record(...)} is duplicated here — admin-console-service's
 * branding/email code never reads the audit log back (that viewer lives in
 * platform-core-service's auth endpoints).</p>
 */
@Service
public class AuditService {

    private final ArcadeDbTemplate arcade;

    public AuditService(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    public void record(String actorKey, String action, String target, Map<String, Object> detail) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", java.util.UUID.randomUUID().toString());
        params.put("tenantId", TenantContextHolder.requireTenantId().toString());
        params.put("a", actorKey == null ? "system" : actorKey);
        params.put("ac", action);
        params.put("t", target == null ? "" : target);
        params.put("d", detail == null ? Map.of() : detail);
        params.put("now", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO AuthAuditLog SET id = :id, tenantId = :tenantId, actorKey = :a, " +
                        "action = :ac, target = :t, detail = :d, createdAt = :now",
                params);
    }
}
