package io.orphos.eip.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Grants {@code ROLE_service_internal} to service-to-service calls that carry a valid
 * {@code X-Internal-Token} header (design §9). Runs before the JWT bearer filter and is purely
 * <em>additive</em>: it authenticates a request only when the shared token is configured and
 * matches; it never rejects. Requests without the header (or with a bearer JWT) fall through to the
 * normal JWT resource-server flow unchanged.
 *
 * <p>This lets an internal caller (an agent's runtime, an MCP client, the orchestrator) authenticate
 * with the shared token instead of minting a user JWT, while user-facing calls keep using JWTs —
 * so {@code anyRequest().authenticated()} covers both and {@code @PreAuthorize("hasAuthority('ROLE_service_internal')")}
 * works for internal endpoints.</p>
 */
public class InternalServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(InternalServiceTokenAuthenticationFilter.class);

    /** Header carrying the shared internal-service token. Matches {@code McpHeaders.INTERNAL_TOKEN}. */
    public static final String HEADER = "X-Internal-Token";

    /** Authority granted to a validated internal call. */
    public static final String ROLE = "ROLE_service_internal";

    private final String expectedToken;

    public InternalServiceTokenAuthenticationFilter(String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    /** {@code true} when a token is configured — otherwise the filter is a no-op. */
    public boolean isActive() {
        return !expectedToken.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (isActive()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String supplied = req.getHeader(HEADER);
            if (supplied != null && !supplied.isBlank() && constantTimeEquals(supplied.trim(), expectedToken)) {
                InternalServiceAuthentication auth = new InternalServiceAuthentication();
                auth.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(auth);
                if (log.isTraceEnabled()) {
                    log.trace("Authenticated internal service call to {} {}", req.getMethod(), req.getRequestURI());
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** A minimal authenticated principal representing a trusted internal service. */
    static final class InternalServiceAuthentication extends AbstractAuthenticationToken {
        InternalServiceAuthentication() {
            super(List.of(new SimpleGrantedAuthority(ROLE)));
        }
        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return "internal-service"; }
    }

    /** Length-checked constant-time comparison (length may leak; per-char timing must not). */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
