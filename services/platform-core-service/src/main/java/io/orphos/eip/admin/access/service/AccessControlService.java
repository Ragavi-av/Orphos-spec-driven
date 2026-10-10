package io.orphos.eip.admin.access.service;

import io.orphos.eip.admin.access.repo.AccessControlRepository;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import io.orphos.eip.common.access.AccessPolicy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the per-tenant RBAC access map (role → resource grants) that drives
 * which admin tabs and user dashboard sections each role can see.
 *
 * <p>Guardrails enforced here (independent of the UI, which mirrors them):
 * <ul>
 *   <li>{@code platform_admin} is immutable — it always has access to everything
 *       and its grants can never be set.</li>
 *   <li>The platform-only surface (Tenants and the rest of Security &amp;
 *       Operations / Platform groups) is never grantable to any other role.</li>
 *   <li>An effect must be {@code allow} or {@code deny}; anything else clears the
 *       grant back to "inherit".</li>
 * </ul>
 * Absence of a grant means "inherit" — the effective decision walks up the
 * resource hierarchy (resolved on the client via {@code computeEffective}).</p>
 */
@Service
public class AccessControlService {

    /** Roles whose access is fixed and cannot be edited (from the shared policy). */
    public static final Set<String> IMMUTABLE_ROLES = AccessPolicy.IMMUTABLE_ROLES;

    /**
     * Self-lockout guard: the tenant {@code admin} role can never be denied the
     * Access Control tab or its group, so an admin can always get back in and fix
     * a mistaken grant. (platform_admin is immutable and can always fix it too.)
     */
    private static final Set<String> LOCKOUT_GUARDED_RESOURCES = Set.of("access-control", "auth-authz");

    /**
     * Resource keys (groups and tabs) that are platform-admin-only and must never
     * be granted to another role. Covers the Security &amp; Operations and Platform
     * groups and their tabs — most importantly the Tenants management tab.
     */
    public static final Set<String> LOCKED_RESOURCES = Set.of(
            "security-ops", "platform",
            "tenants", "services", "db-connections", "bases", "logs",
            "plugins", "agents", "agent-approvals", "platform-branding");

    private final AccessControlRepository repo;
    private final AuthRepository audit;

    public AccessControlService(AccessControlRepository repo, AuthRepository audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** All grants for the current tenant as {role, resourceKey, effect} maps. */
    public List<Map<String, Object>> listGrants() {
        return repo.listGrants();
    }

    /**
     * Sets or clears a single grant. {@code effect} of {@code allow}/{@code deny}
     * upserts; anything else (e.g. {@code inherit}, blank) clears the grant.
     *
     * @throws IllegalArgumentException if the role is immutable or the resource is locked.
     */
    public void setGrant(String role, String resourceKey, String effect, String actorKey) {
        if (role == null || role.isBlank() || resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("role and resourceKey are required");
        }
        if (IMMUTABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Role '" + role + "' is immutable and always has full access");
        }
        if (LOCKED_RESOURCES.contains(resourceKey)) {
            throw new IllegalArgumentException("Resource '" + resourceKey
                    + "' is platform-admin only and cannot be granted to other roles");
        }

        String normalised = effect == null ? "" : effect.trim().toLowerCase();
        if ("admin".equals(role) && "deny".equals(normalised)
                && LOCKOUT_GUARDED_RESOURCES.contains(resourceKey)) {
            throw new IllegalArgumentException("The 'admin' role cannot be denied '" + resourceKey
                    + "' — that would lock admins out of Access Control. Use platform_admin if you must change it.");
        }
        if ("allow".equals(normalised) || "deny".equals(normalised)) {
            repo.setGrant(role, resourceKey, normalised);
        } else {
            repo.clearGrant(role, resourceKey);
            normalised = "inherit";
        }

        try {
            audit.appendAudit(actorKey, "ACCESS_GRANT_SET", role + ":" + resourceKey,
                    Map.of("role", role, "resourceKey", resourceKey, "effect", normalised));
        } catch (Exception ignored) {
            // Audit is best-effort — never block the grant on an audit write.
        }
    }

    /**
     * Clones every access grant from {@code fromRole} onto {@code toRole} — used
     * when a role is copied/derived so the new role starts with the same access
     * map. No-op for an immutable target.
     */
    public void copyGrants(String fromRole, String toRole) {
        if (fromRole == null || fromRole.isBlank() || toRole == null || toRole.isBlank()) return;
        if (IMMUTABLE_ROLES.contains(toRole)) return;
        repo.copyGrants(fromRole, toRole);
    }

    /** Removes every grant for a role — used when a custom role is deleted or reset. */
    public void clearRole(String role) {
        if (role == null || role.isBlank() || IMMUTABLE_ROLES.contains(role)) return;
        repo.clearRole(role);
    }
}
