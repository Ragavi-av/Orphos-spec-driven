package io.orphos.eip.admin.access.repo;

import io.orphos.eip.admin.common.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped storage for the RBAC access map: one {@code RoleResourceAccess}
 * row per {@code (tenantId, role, resourceKey)} carrying an {@code effect} of
 * {@code allow} or {@code deny}. Absence of a row means "inherit" — the effective
 * decision is resolved by walking up the resource hierarchy (see the frontend
 * {@code computeEffective} and the service guardrails).
 *
 * <p>Mirrors {@code AuthRepository}: every read/write filters by the tenant from
 * {@link TenantContextHolder}, and all SQL is parameterised.</p>
 */
@Repository
public class AccessControlRepository {

    private final ArcadeDbTemplate arcade;

    public AccessControlRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    /** All grants for the current tenant. */
    public List<Map<String, Object>> listGrants() {
        return arcade.query(
                "SELECT role, resourceKey, effect FROM RoleResourceAccess WHERE tenantId = :tenantId",
                Map.of("tenantId", currentTenantId()));
    }

    /**
     * Upserts a single {@code (role, resourceKey)} grant to {@code effect}
     * ({@code allow} or {@code deny}).
     */
    public void setGrant(String role, String resourceKey, String effect) {
        String tenantId = currentTenantId();
        boolean exists = !arcade.query(
                "SELECT FROM RoleResourceAccess WHERE tenantId = :tenantId AND role = :role " +
                        "AND resourceKey = :rk LIMIT 1",
                Map.of("tenantId", tenantId, "role", role, "rk", resourceKey)).isEmpty();
        String now = Instant.now().toString();
        if (exists) {
            arcade.commandNoResult(
                    "UPDATE RoleResourceAccess SET effect = :eff, updatedAt = :now " +
                            "WHERE tenantId = :tenantId AND role = :role AND resourceKey = :rk",
                    Map.of("eff", effect, "now", now, "tenantId", tenantId, "role", role, "rk", resourceKey));
        } else {
            arcade.commandNoResult(
                    "INSERT INTO RoleResourceAccess SET id = :id, tenantId = :tenantId, role = :role, " +
                            "resourceKey = :rk, effect = :eff, createdAt = :now, updatedAt = :now",
                    Map.of("id", Ids.newId(), "tenantId", tenantId, "role", role, "rk", resourceKey,
                            "eff", effect, "now", now));
        }
    }

    /** Removes an explicit grant, returning the {@code (role, resourceKey)} to "inherit". */
    public void clearGrant(String role, String resourceKey) {
        arcade.commandNoResult(
                "DELETE FROM RoleResourceAccess WHERE tenantId = :tenantId AND role = :role AND resourceKey = :rk",
                Map.of("tenantId", currentTenantId(), "role", role, "rk", resourceKey));
    }

    /** Removes every grant for a role (used when a custom role is deleted/reset). */
    public void clearRole(String role) {
        arcade.commandNoResult(
                "DELETE FROM RoleResourceAccess WHERE tenantId = :tenantId AND role = :role",
                Map.of("tenantId", currentTenantId(), "role", role));
    }

    /**
     * Clones every explicit grant from {@code fromRole} to {@code toRole} for the
     * current tenant, so a copied/derived role starts with the same access map.
     * Existing grants on {@code toRole} for the same resource are overwritten.
     */
    public void copyGrants(String fromRole, String toRole) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT resourceKey, effect FROM RoleResourceAccess WHERE tenantId = :tenantId AND role = :role",
                Map.of("tenantId", currentTenantId(), "role", fromRole));
        for (Map<String, Object> r : rows) {
            String rk = String.valueOf(r.get("resourceKey"));
            String eff = String.valueOf(r.get("effect"));
            if (rk == null || rk.isBlank() || eff == null || eff.isBlank()) continue;
            setGrant(toRole, rk, eff);
        }
    }

    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
