package io.orphos.eip.nlquery.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Helpers for resolving the currently authenticated Keycloak user.
 * The 'sub' claim is the stable identifier we use to scope per-user secrets.
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<String> sub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            return (sub == null || sub.isBlank()) ? Optional.empty() : Optional.of(sub);
        }
        // Fallback to the Authentication name (works for non-JWT contexts in tests)
        String name = auth.getName();
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }

    /**
     * Human-readable identifier for display and audit logs.
     * Prefers {@code preferred_username} (set by Keycloak to the email / login name),
     * then {@code email}, then falls back to the opaque {@code sub} UUID.
     */
    public static Optional<String> displayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            for (String claim : new String[]{"preferred_username", "email"}) {
                Object val = jwt.getClaim(claim);
                if (val instanceof String s && !s.isBlank()) return Optional.of(s);
            }
            String sub = jwt.getSubject();
            return (sub == null || sub.isBlank()) ? Optional.empty() : Optional.of(sub);
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }
}
