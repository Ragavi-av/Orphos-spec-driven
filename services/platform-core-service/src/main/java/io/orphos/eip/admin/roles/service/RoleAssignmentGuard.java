package io.orphos.eip.admin.roles.service;

import io.orphos.eip.admin.roles.registry.RoleRegistry;
import io.orphos.eip.common.security.EipRoles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies {@link RoleAssignmentPolicy} to the current caller. Every check throws
 * {@link AccessDeniedException} (403) on refusal; callers must let it propagate past
 * their generic error handling.
 */
@Component
public class RoleAssignmentGuard {

    private static final Logger log = LogManager.getLogger(RoleAssignmentGuard.class);

    private final RoleRegistry registry;

    public RoleAssignmentGuard(RoleRegistry registry) {
        this.registry = registry;
    }

    /** The caller may grant/revoke {@code role}, or use it as the base of a new custom role. */
    public void requireCanManage(String role) {
        if (!RoleAssignmentPolicy.canManage(actorRoles(), role, customBases())) {
            deny("You cannot grant, revoke or build on the role '" + role + "'.");
        }
    }

    /**
     * The caller may change user {@code userId}'s roles from {@code current} to {@code target}:
     * not their own account, and every added or removed role manageable.
     */
    public void requireCanChangeRoles(String userId, Collection<String> current, Collection<String> target) {
        if (isSelf(userId)) {
            deny("You cannot change your own roles. Ask another administrator.");
        }
        Set<String> denied = RoleAssignmentPolicy.unmanageableChanges(actorRoles(), current, target, customBases());
        if (!denied.isEmpty()) {
            deny("You cannot grant or revoke: " + String.join(", ", denied) + ".");
        }
    }

    /** The caller may act on (disable, edit, delete) a user holding {@code held}. */
    public void requireCanActOnUser(String userId, Collection<String> held, boolean allowSelf) {
        if (!allowSelf && isSelf(userId)) {
            deny("You cannot perform this action on your own account.");
        }
        Set<String> denied = RoleAssignmentPolicy.unmanageableHeld(actorRoles(), held, customBases());
        if (!denied.isEmpty()) {
            deny("This user holds roles you cannot manage: " + String.join(", ", denied) + ".");
        }
    }

    private static void deny(String message) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        log.warn("Role management denied for {}: {}", a == null ? "anonymous" : a.getName(), message);
        throw new AccessDeniedException(message);
    }

    private static Set<String> actorRoles() {
        return EipRoles.roleNames(SecurityContextHolder.getContext().getAuthentication());
    }

    private static boolean isSelf(String userId) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof Jwt jwt
                && userId != null && Objects.equals(jwt.getSubject(), userId.trim());
    }

    /**
     * The current tenant's {@code customRole → basedOn} map, or {@code null} when it can't
     * be read — the policy then refuses every custom role to non-admins, since an unknown
     * base might be an admin role.
     */
    private Map<String, String> customBases() {
        Map<String, String> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = registry.list();
            for (Map<String, Object> row : rows) {
                Object key = row.get("roleKey");
                Object base = row.get("basedOn");
                if (key != null && base != null && !base.toString().isBlank()) {
                    out.put(key.toString(), base.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read custom role bases: {}", e.getMessage());
            return null;
        }
        return out;
    }
}
