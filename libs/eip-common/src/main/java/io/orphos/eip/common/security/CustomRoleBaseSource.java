package io.orphos.eip.common.security;

import java.util.Map;
import java.util.UUID;

/**
 * Supplies a tenant's custom roles and the role each was based on, so a custom role
 * inherits its base's server-side privileges (a role "based on cfo" passes every
 * {@code hasRole('cfo')} / {@code hasRole('executive')} check a CFO would).
 *
 * <p>Custom roles are created in Access Control as a copy of an existing role; the
 * copy's screen grants are cloned at creation. This source extends the same intent to
 * the coarse role rules, which otherwise know nothing about custom roles.</p>
 *
 * <p><b>Availability contract:</b> return a (possibly empty) {@code customRole → basedOn}
 * map when resolved; return {@code null} when it could not be resolved. Unavailable means
 * custom roles inherit nothing for that request — the safe direction.</p>
 */
@FunctionalInterface
public interface CustomRoleBaseSource {

    Map<String, String> customRoleBases(UUID tenantId);

    /** Max hops when a custom role is based on another custom role. */
    int MAX_DEPTH = 5;

    /**
     * Resolves {@code role} through {@code basedOn} links to the built-in role it
     * ultimately derives from, or {@code null} if the chain does not end in an
     * inheritable built-in (unknown, cyclic, too deep, or a reserved role such as
     * {@code platform_admin}, which is never inheritable).
     */
    static String builtInBase(String role, Map<String, String> basedOn) {
        if (basedOn == null) return null;
        String current = role;
        for (int i = 0; i < MAX_DEPTH; i++) {
            String next = basedOn.get(current);
            if (next == null || next.isBlank()) return null;
            next = next.trim();
            if (EipRoles.RESERVED.contains(next)) return null;
            if (EipRoles.isBuiltIn(next)) return next;
            current = next;
        }
        return null;
    }
}
