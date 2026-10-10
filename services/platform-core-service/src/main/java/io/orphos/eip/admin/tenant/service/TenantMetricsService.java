package io.orphos.eip.admin.tenant.service;

import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.tenant.dto.TenantDtos;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes operational metrics for a tenant — user count, per-doctype row
 * counts, last-activity timestamp, and reachability of the tenant's external
 * resources (dedicated ArcadeDB, Keycloak realm). Powers the tenant-detail
 * drawer in the admin dashboard.
 *
 * <p>All probes are best-effort: a failed reachability check returns
 * {@code false} (and {@code -1} for user count) rather than throwing, so a
 * partially-degraded tenant still produces a useful response.</p>
 */
@Service
public class TenantMetricsService {

    private static final Logger log = LogManager.getLogger(TenantMetricsService.class);

    /**
     * Tenant-scoped doc types whose row counts contribute to the metrics view.
     * Mirrors {@code DemoTenantBootstrap.TENANT_SCOPED_TYPES} and
     * {@code TenantService.SHARED_TENANT_SCOPED_TYPES}.
     */
    private static final List<String> TENANT_SCOPED_TYPES = List.of(
            "AuthMethod", "IdentityProvider", "LocalUser", "UserAuthBinding",
            "AuthEvent", "AuthAuditLog",
            "BrandingProfile", "BrandingAsset", "Theme",
            "EmailConfig", "EmailTemplate", "EmailEvent",
            "NotificationPref", "EmailConfigBackup");

    /**
     * Doc types that carry a per-event timestamp and so are useful for
     * computing last-activity. Picked from {@link #TENANT_SCOPED_TYPES}.
     */
    private static final List<String> ACTIVITY_TYPES = List.of(
            "AuthEvent", "AuthAuditLog", "EmailEvent");

    private final TenantService tenantService;
    private final ArcadeDbTemplate arcade;
    private final KeycloakProvisioningClient keycloak;
    private final ArcadeConnectionResolver resolver;

    public TenantMetricsService(TenantService tenantService,
                                ArcadeDbTemplate arcade,
                                KeycloakProvisioningClient keycloak,
                                ArcadeConnectionResolver resolver) {
        this.tenantService = tenantService;
        this.arcade = arcade;
        this.keycloak = keycloak;
        this.resolver = resolver;
    }

    /** Template routed to the tenant's ArcadeDB server (default for SHARED/bundled). */
    private ArcadeDbTemplate arcadeFor(Tenant t) {
        return resolver.templateFor(t.arcadeConnectionId());
    }

    public TenantDtos.TenantMetricsView compute(UUID tenantId) {
        Tenant t = tenantService.get(tenantId);

        Map<String, Long> docCounts = new LinkedHashMap<>();
        boolean dbReachable = true;
        long total = 0;
        for (String docType : TENANT_SCOPED_TYPES) {
            try {
                long n = countDocs(t, docType);
                docCounts.put(docType, n);
                total += n;
            } catch (Exception e) {
                log.debug("Doc-count failed for tenant={} type={}: {}", tenantId, docType, e.getMessage());
                docCounts.put(docType, -1L);
                dbReachable = false;
            }
        }

        boolean realmReachable = keycloak.realmReachable(t.keycloakRealm());
        int userCount = realmReachable ? keycloak.countUsers(t.keycloakRealm()) : -1;

        Instant lastActivity = computeLastActivity(t);

        return new TenantDtos.TenantMetricsView(
                t.id(),
                t.domain(),
                t.isolationMode(),
                t.arcadeDatabase(),
                t.keycloakRealm(),
                userCount,
                docCounts,
                total,
                dbReachable,
                realmReachable,
                lastActivity,
                Instant.now());
    }

    /**
     * Count rows for {@code docType} owned by {@code t}.
     *
     * <p>For SHARED-mode tenants, filter on {@code tenantId} in the shared DB.
     * For DEDICATED-mode tenants the whole database belongs to the tenant, so
     * a bare {@code count(*)} is correct and avoids requiring every dedicated
     * row to carry a redundant {@code tenantId} column.</p>
     */
    private long countDocs(Tenant t, String docType) {
        if (t.isolationMode() == IsolationMode.DEDICATED) {
            List<Map<String, Object>> rows = arcadeFor(t).queryOn(
                    t.arcadeDatabase(),
                    "SELECT count(*) AS c FROM " + docType,
                    Map.of());
            return extractCount(rows);
        }
        List<Map<String, Object>> rows = arcade.query(
                "SELECT count(*) AS c FROM " + docType + " WHERE tenantId = :tenantId",
                Map.of("tenantId", t.id().toString()));
        return extractCount(rows);
    }

    private Instant computeLastActivity(Tenant t) {
        Instant best = null;
        for (String docType : ACTIVITY_TYPES) {
            try {
                Instant i = mostRecentTimestamp(t, docType);
                if (i != null && (best == null || i.isAfter(best))) {
                    best = i;
                }
            } catch (Exception e) {
                log.debug("Last-activity probe failed for tenant={} type={}: {}",
                        t.id(), docType, e.getMessage());
            }
        }
        // Fall back to tenant.updatedAt so the UI never shows "no activity" for a
        // freshly-created tenant — the tenant row's own timestamp is the
        // earliest meaningful signal until events accrue.
        return best != null ? best : t.updatedAt();
    }

    private Instant mostRecentTimestamp(Tenant t, String docType) {
        // Each activity type has an "occurredAt" or "createdAt" — query the
        // larger-named one; fall back to createdAt. Both are ISO-8601 strings
        // so MAX() on a string field returns the lexicographically-latest, which
        // is also chronologically-latest for ISO-8601.
        String sql;
        Map<String, Object> params;
        if (t.isolationMode() == IsolationMode.DEDICATED) {
            sql = "SELECT max(coalesce(occurredAt, createdAt)) AS t FROM " + docType;
            params = Map.of();
            List<Map<String, Object>> rows = arcadeFor(t).queryOn(t.arcadeDatabase(), sql, params);
            return extractInstant(rows);
        }
        sql = "SELECT max(coalesce(occurredAt, createdAt)) AS t FROM " + docType
                + " WHERE tenantId = :tenantId";
        params = Map.of("tenantId", t.id().toString());
        List<Map<String, Object>> rows = arcade.query(sql, params);
        return extractInstant(rows);
    }

    private static long extractCount(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return 0L;
        Object c = rows.get(0).get("c");
        if (c instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(c)); } catch (Exception e) { return 0L; }
    }

    private static Instant extractInstant(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return null;
        Object v = rows.get(0).get("t");
        if (v == null) return null;
        try {
            return Instant.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
