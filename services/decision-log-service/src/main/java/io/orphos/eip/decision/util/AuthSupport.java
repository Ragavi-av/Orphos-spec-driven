package io.orphos.eip.decision.util;

import io.orphos.eip.common.security.EipRoles;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/** JWT-derived display identity + role bucket shared by the decision controllers. */
public final class AuthSupport {

    private AuthSupport() {}

    /** Human-readable actor label for audit fields ({@code preferred_username} claim, no raw subject ids). */
    public static String actor(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            if (username != null && !username.isBlank()) return username;
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) return name;
        }
        return auth == null ? "system" : "user";
    }

    /** Audience bucket for role-adapted AI annotations (Req 7.5). */
    public static String audienceRole(Authentication auth) {
        if (auth == null) return "user";
        // The roles the user holds (a custom role counts as its base), deliberately
        // without hierarchy expansion: admin implies the C-suite for access checks,
        // but an admin still reads the admin-flavoured annotation.
        Set<String> held = EipRoles.roleNames(auth);
        boolean executive = held.stream().anyMatch(EXECUTIVE_ROLES::contains);
        boolean admin = held.contains(EipRoles.PLATFORM_ADMIN)
                || held.stream().anyMatch(EipRoles.ADMIN_TIER::contains);
        return executive ? "executive" : admin ? "admin" : "user";
    }

    private static final Set<String> EXECUTIVE_ROLES =
            Set.of(EipRoles.CEO, EipRoles.CTO, EipRoles.CFO, EipRoles.EXECUTIVE);
}
