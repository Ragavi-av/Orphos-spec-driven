package io.orphos.eip.admin.util;

import io.orphos.eip.common.security.EipRoles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the calling principal to a stable user key (Keycloak {@code sub})
 * without leaking PII (emails, names) into the response surface.
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static String key() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "anonymous";
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            return sub != null ? sub : "anonymous";
        }
        return auth.getName() != null ? auth.getName() : "anonymous";
    }

    /** Tenant admin or above (platform admins inherit it through the role hierarchy). */
    public static boolean isAdmin() {
        return EipRoles.hasRole(SecurityContextHolder.getContext().getAuthentication(), EipRoles.ADMIN);
    }
}
