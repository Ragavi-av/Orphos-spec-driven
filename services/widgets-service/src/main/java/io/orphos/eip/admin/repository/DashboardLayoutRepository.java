package io.orphos.eip.admin.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for tenant-scoped dashboard layouts.
 *
 * <p>Two document types:
 * <ul>
 *   <li>{@code DashboardLayoutDefault} — singleton per tenant; the layout new
 *       users in that tenant see on first load. Edited by tenant admins.</li>
 *   <li>{@code DashboardLayoutUser} — one per (tenantId, userId); a user's
 *       personal override once they customise.</li>
 * </ul>
 *
 * <p>Every method stamps {@code tenantId} on inserts and filters by it on
 * reads/updates/deletes via {@link TenantContextHolder#requireTenantId()}.
 * Mirrors the contract of {@code BrandingRepository}.</p>
 */
@Repository
public class DashboardLayoutRepository {

    private final ArcadeDbTemplate arcade;

    public DashboardLayoutRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ----- Tenant default -----

    public Optional<Map<String, Object>> findTenantDefault() {
        var rows = arcade.query(
                "SELECT FROM DashboardLayoutDefault WHERE tenantId = :tenantId LIMIT 1",
                Map.of("tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsertTenantDefault(List<String> visibleIds, List<String> order, String actorKey) {
        // NB: column is `widgetOrder`, not `order` — `order` is a SQL reserved
        // word (start of ORDER BY) and ArcadeDB's parser rejects it on the
        // left-hand side of a SET clause, silently failing the whole INSERT.
        String tenantId = currentTenantId();
        String now = Instant.now().toString();
        Map<String, Object> params = Map.of(
                "tenantId", tenantId,
                "visibleIds", visibleIds,
                "widgetOrder", order,
                "updatedAt", now,
                "updatedBy", actorKey == null ? "" : actorKey
        );
        if (findTenantDefault().isEmpty()) {
            arcade.commandNoResult(
                    "INSERT INTO DashboardLayoutDefault SET tenantId = :tenantId, " +
                            "visibleIds = :visibleIds, widgetOrder = :widgetOrder, " +
                            "updatedAt = :updatedAt, updatedBy = :updatedBy",
                    params);
        } else {
            arcade.commandNoResult(
                    "UPDATE DashboardLayoutDefault SET visibleIds = :visibleIds, widgetOrder = :widgetOrder, " +
                            "updatedAt = :updatedAt, updatedBy = :updatedBy " +
                            "WHERE tenantId = :tenantId",
                    params);
        }
    }

    public void deleteTenantDefault() {
        arcade.commandNoResult(
                "DELETE FROM DashboardLayoutDefault WHERE tenantId = :tenantId",
                Map.of("tenantId", currentTenantId()));
    }

    // ----- Per-user override -----

    public Optional<Map<String, Object>> findUserLayout(String userId) {
        var rows = arcade.query(
                "SELECT FROM DashboardLayoutUser WHERE tenantId = :tenantId AND userId = :userId LIMIT 1",
                Map.of("tenantId", currentTenantId(), "userId", userId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsertUserLayout(String userId, List<String> visibleIds, List<String> order) {
        // NB: column is `widgetOrder`, not `order` — see upsertTenantDefault().
        String tenantId = currentTenantId();
        String now = Instant.now().toString();
        Map<String, Object> params = Map.of(
                "tenantId", tenantId,
                "userId", userId,
                "visibleIds", visibleIds,
                "widgetOrder", order,
                "updatedAt", now
        );
        if (findUserLayout(userId).isEmpty()) {
            arcade.commandNoResult(
                    "INSERT INTO DashboardLayoutUser SET tenantId = :tenantId, userId = :userId, " +
                            "visibleIds = :visibleIds, widgetOrder = :widgetOrder, updatedAt = :updatedAt",
                    params);
        } else {
            arcade.commandNoResult(
                    "UPDATE DashboardLayoutUser SET visibleIds = :visibleIds, widgetOrder = :widgetOrder, " +
                            "updatedAt = :updatedAt " +
                            "WHERE tenantId = :tenantId AND userId = :userId",
                    params);
        }
    }

    public void deleteUserLayout(String userId) {
        arcade.commandNoResult(
                "DELETE FROM DashboardLayoutUser WHERE tenantId = :tenantId AND userId = :userId",
                Map.of("tenantId", currentTenantId(), "userId", userId));
    }

    // ----- Helpers -----

    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
