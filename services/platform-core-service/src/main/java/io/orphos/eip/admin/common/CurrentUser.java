package io.orphos.eip.admin.common;

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

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
    }
}
