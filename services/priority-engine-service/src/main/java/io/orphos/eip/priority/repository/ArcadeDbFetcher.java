package io.orphos.eip.priority.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.common.tenant.TenantDataUnavailableException;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Thin ArcadeDB query wrapper — returns raw result rows for each entity type,
 * scoped to the current tenant (resolved from {@link TenantContextHolder}).
 *
 * <p>Returns an empty list (never throws) so the score service can degrade
 * gracefully when ArcadeDB is unreachable.</p>
 */
@Repository
public class ArcadeDbFetcher {

    private static final Logger log = LogManager.getLogger(ArcadeDbFetcher.class);

    private final ArcadeDbTemplate arcade;

    public ArcadeDbFetcher(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    public List<Map<String, Object>> fetchIncidents() {
        return query("""
            SELECT id, title, severity, status, domain, affectedSystem,
                   createdAt, estimatedResolution, description
            FROM Incident
            WHERE tenantId = :tenantId AND status <> 'RESOLVED'
            LIMIT 50
            """);
    }

    public List<Map<String, Object>> fetchAlerts() {
        return query("""
            SELECT id, title, severity, domain, status, message, createdAt, source
            FROM Alert
            WHERE tenantId = :tenantId AND status = 'ACTIVE'
            LIMIT 50
            """);
    }

    public List<Map<String, Object>> fetchBudgetItems() {
        return query("""
            SELECT id, title, amount, currency, status, dueDate, costCentre, requestedBy
            FROM BudgetItem
            WHERE tenantId = :tenantId AND status IN ['PENDING_APPROVAL', 'IN_REVIEW']
            LIMIT 50
            """);
    }

    public List<Map<String, Object>> fetchDecisions() {
        return query("""
            SELECT id, title, status, domain, rationale, decidedBy, decidedAt
            FROM Decision
            WHERE tenantId = :tenantId AND status = 'PENDING'
            LIMIT 50
            """);
    }

    public List<Map<String, Object>> fetchKeyResults() {
        return query("""
            SELECT id, title, metric, target, current, unit, status, owner,
                   objectiveId, quarter
            FROM KeyResult
            WHERE tenantId = :tenantId AND status IN ['BEHIND', 'AT_RISK']
            LIMIT 50
            """);
    }

    public List<Map<String, Object>> fetchProjects() {
        return query("""
            SELECT id, name, status, domain, owner, budget, spent, currency,
                   startDate, endDate, progress, description
            FROM Project
            WHERE tenantId = :tenantId AND status IN ['AT_RISK', 'DELAYED']
            LIMIT 50
            """);
    }

    /** SLA metrics for the SLA panel — worst status first. */
    public List<Map<String, Object>> fetchSlaMetrics() {
        return query("""
            SELECT id, system, metricName, target, actual, unit, status, measuredAt
            FROM SLAMetric
            WHERE tenantId = :tenantId
            ORDER BY status ASC
            LIMIT 20
            """);
    }

    /** Active alerts ranked by severity — backs the HeaderBar alert chips. */
    public List<Map<String, Object>> fetchActiveAlertsRanked() {
        return query("""
            SELECT id, title, severity, domain, status, message, createdAt, source
            FROM Alert
            WHERE tenantId = :tenantId AND status = 'ACTIVE'
            ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2
                     WHEN 'MEDIUM' THEN 3 ELSE 4 END ASC
            """);
    }

    // ── private helpers ────────────────────────────────────────────────────

    private List<Map<String, Object>> query(String sql) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        try {
            return arcade.query(sql.trim(), Map.of("tenantId", tenantId));
        } catch (TenantDataUnavailableException e) {
            // Propagate so the SPA's degraded-data banner fires. The dashboard
            // would otherwise just render empty widgets with no signal that
            // the tenant's DB is offline.
            throw e;
        } catch (Exception e) {
            log.warn("ArcadeDB query failed (tenant={}, sql='{}...'): {}",
                    tenantId, sql.strip().substring(0, Math.min(40, sql.length())), e.getMessage());
            return List.of();
        }
    }
}
