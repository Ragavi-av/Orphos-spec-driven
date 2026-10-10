package io.orphos.eip.admin.roles.service;

import io.orphos.eip.common.security.CustomRoleBaseSource;
import io.orphos.eip.common.security.EipRoles;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who may grant, revoke or build on which role — the no-escalation rule behind user
 * management, role administration and the access map.
 *
 * <p>Without it, any holder of a role-management endpoint could hand out roles
 * stronger than their own: a {@code secops_admin} could PATCH itself (or an
 * accomplice) to {@code admin}, or create a custom role "based on admin" and assign
 * that. The rule:</p>
 * <ul>
 *   <li><b>Reserved roles</b> ({@code platform_admin}, {@code service_internal},
 *       {@code member}) are never manageable inside a tenant.</li>
 *   <li><b>{@code platform_admin} and {@code admin}</b> may manage every tenant role.</li>
 *   <li><b>{@code secops_admin}</b> owns user management but not administration rights:
 *       it may manage every role whose effective level is below the admin tier — the
 *       C-suite, {@code executive}, {@code viewer}, {@code guest}, and custom roles based
 *       on one of those (or on nothing). It may not manage {@code admin} or any scoped
 *       admin role, including its own, nor custom roles based on them.</li>
 *   <li>Everyone else may manage nothing.</li>
 * </ul>
 *
 * <p>A custom role's level is that of the built-in role it is based on, resolved through
 * {@link CustomRoleBaseSource#builtInBase}. The actor's roles include derived bases, so a
 * custom role based on {@code secops_admin} manages what secops manages.</p>
 */
public final class RoleAssignmentPolicy {

    private RoleAssignmentPolicy() {}

    /**
     * True if an actor holding {@code actorRoles} may grant or revoke {@code role}.
     *
     * @param actorRoles  the actor's role names (assigned plus derived), unexpanded
     * @param role        the role being granted, revoked, or used as a base
     * @param customBases the tenant's {@code customRole → basedOn} map, or {@code null} if unknown
     */
    public static boolean canManage(Collection<String> actorRoles, String role, Map<String, String> customBases) {
        if (role == null || role.isBlank()) return false;
        String r = role.trim();
        if (EipRoles.RESERVED.contains(r) || EipRoles.isKeycloakDefault(r)) return false;

        Set<String> actor = EipRoles.expand(actorRoles);
        if (actor.contains(EipRoles.ADMIN)) return true;          // admin, and platform_admin via the hierarchy
        if (!actor.contains(EipRoles.SECOPS_ADMIN)) return false;

        if (EipRoles.isBuiltIn(r)) return !EipRoles.ADMIN_TIER.contains(r);
        // Bases unknown (registry unreadable): the custom role might be admin-based, so refuse.
        if (customBases == null) return false;
        String level = CustomRoleBaseSource.builtInBase(r, customBases);
        // A custom role with no (resolvable) base carries no inherited privilege.
        return level == null || !EipRoles.ADMIN_TIER.contains(level);
    }

    /**
     * The roles in the change from {@code current} to {@code target} that the actor may
     * not manage — both additions and removals count (demoting an admin is as privileged
     * as promoting one). Keycloak bookkeeping roles are ignored. Empty means allowed.
     */
    public static Set<String> unmanageableChanges(Collection<String> actorRoles,
                                                  Collection<String> current, Collection<String> target,
                                                  Map<String, String> customBases) {
        Set<String> before = appRoles(current);
        Set<String> after = appRoles(target);
        Set<String> changed = new LinkedHashSet<>();
        for (String r : after) if (!before.contains(r)) changed.add(r);
        for (String r : before) if (!after.contains(r)) changed.add(r);

        Set<String> denied = new LinkedHashSet<>();
        for (String r : changed) {
            if (!canManage(actorRoles, r, customBases)) denied.add(r);
        }
        return denied;
    }

    /**
     * The roles a user holds that the actor may not manage. Non-empty means the actor may
     * not act on that user at all (disable, delete, change profile): secops cannot lock
     * out an admin.
     */
    public static Set<String> unmanageableHeld(Collection<String> actorRoles, Collection<String> held,
                                               Map<String, String> customBases) {
        Set<String> denied = new LinkedHashSet<>();
        for (String r : appRoles(held)) {
            if (!canManage(actorRoles, r, customBases)) denied.add(r);
        }
        return denied;
    }

    private static Set<String> appRoles(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles == null) return out;
        for (String r : roles) {
            if (r == null || r.isBlank() || EipRoles.isKeycloakDefault(r)) continue;
            out.add(r.trim());
        }
        return out;
    }
}
