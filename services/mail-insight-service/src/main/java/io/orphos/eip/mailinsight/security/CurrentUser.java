package io.orphos.eip.mailinsight.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/** Resolves a human-readable identifier for the current user (for audit fields). */
public final class CurrentUser {

    private CurrentUser() {}

    /** Stable per-user identifier (JWT sub) — used to scope personal mailbox items. */
    public static Optional<String> sub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            return (sub == null || sub.isBlank()) ? Optional.empty() : Optional.of(sub);
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }

    /** The signed-in user's email/UPN — prefers the {@code email} claim. */
    public static Optional<String> email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            for (String claim : new String[]{"email", "preferred_username"}) {
                Object val = jwt.getClaim(claim);
                if (val instanceof String s && !s.isBlank()) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

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
