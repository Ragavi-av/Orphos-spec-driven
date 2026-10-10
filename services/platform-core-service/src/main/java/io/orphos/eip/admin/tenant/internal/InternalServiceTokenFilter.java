package io.orphos.eip.admin.tenant.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Shared-secret gate for {@code /api/internal/**} endpoints.
 *
 * <p>The {@code /api/internal/} family is the service-to-service discovery
 * surface — {@code tenant-issuers}, {@code active-tenants}, {@code tenants/{id}}.
 * It is permit-all in {@link io.orphos.eip.admin.config.SecurityConfig} because
 * these calls are unauthenticated by design (services don't carry user JWTs
 * when polling for system-level discovery). This filter adds the
 * defense-in-depth layer: when {@code eip.security.internal-service-token} is
 * configured, every {@code /api/internal/**} request MUST carry a matching
 * {@code X-Service-Token} header or it's rejected with 401.</p>
 *
 * <p><b>Secure by default (fail closed).</b> The {@code /api/internal/} surface
 * exposes the tenant list and a user directory (names/emails — PII), so a blank
 * token must NOT silently open it. When {@code eip.security.internal-service-token}
 * is blank:</p>
 * <ul>
 *   <li>if {@code eip.security.internal-service-token-required} is true (the
 *       DEFAULT), every {@code /api/internal/**} request is rejected 503 and a
 *       loud error is logged at startup — the deployment MUST configure the
 *       token;</li>
 *   <li>only when an operator explicitly sets {@code …-token-required=false}
 *       (local dev convenience) is the surface left permit-all — logged as a
 *       prominent warning each boot.</li>
 * </ul>
 * <p>When the token is set, every request must carry a matching
 * {@code X-Service-Token} header or it is rejected 401.</p>
 *
 * <p>Constant-time comparison — important because a developer or operator
 * staring at log lines should not be able to infer the token from
 * timing of "wrong token" rejections.</p>
 */
@Component
public class InternalServiceTokenFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LogManager.getLogger(InternalServiceTokenFilter.class);
    private static final String PROTECTED_PREFIX = "/api/internal/";
    private static final String HEADER_NAME = "X-Service-Token";

    @Value("${eip.security.internal-service-token:}")
    private String expectedToken;

    /** Secure by default: a blank token fails closed unless this is explicitly false. */
    @Value("${eip.security.internal-service-token-required:true}")
    private boolean tokenRequired;

    private boolean tokenConfigured() {
        return expectedToken != null && !expectedToken.isBlank();
    }

    @jakarta.annotation.PostConstruct
    void logGateState() {
        if (tokenConfigured()) {
            log.info("/api/internal gate ACTIVE — X-Service-Token required on every internal call.");
        } else if (tokenRequired) {
            log.error("SECURITY: eip.security.internal-service-token is BLANK but required — "
                + "ALL /api/internal/** requests will be rejected (503, fail closed). The tenant "
                + "list and user-directory (PII) stay protected, but service-to-service discovery "
                + "is DOWN until the token is provisioned (eip/internal-service#token). Set the "
                + "token, or set eip.security.internal-service-token-required=false ONLY for local dev.");
        } else {
            log.warn("SECURITY: /api/internal is PERMIT-ALL — token blank and "
                + "internal-service-token-required=false. Acceptable for local dev ONLY; the tenant "
                + "list and user-directory (names/emails) are exposed unauthenticated. Never ship this.");
        }
    }

    @Override
    public int getOrder() {
        // Run before Spring Security's chain so we shed bad traffic early
        // and avoid wasting JWT-validation cycles on requests we'll reject.
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        // Skip when not in the protected family — every other path is unaffected.
        if (!req.getRequestURI().startsWith(PROTECTED_PREFIX)) return true;
        // Only skip (permit-all) when the token is blank AND the operator has
        // explicitly opted out of the requirement. Otherwise we always filter —
        // either to validate the header, or to fail closed when misconfigured.
        return !tokenConfigured() && !tokenRequired;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // Fail closed: required but no token provisioned → refuse the whole surface.
        if (!tokenConfigured()) {
            log.error("Rejected {} {} — /api/internal token required but not configured (fail closed)",
                    req.getMethod(), req.getRequestURI());
            writeError(res, HttpStatus.SERVICE_UNAVAILABLE, "internal_token_unconfigured",
                "Internal service token is required but not configured");
            return;
        }

        String supplied = req.getHeader(HEADER_NAME);
        if (supplied == null || supplied.isBlank() || !constantTimeEquals(supplied, expectedToken)) {
            log.warn("Rejected {} {} — missing or invalid {}",
                    req.getMethod(), req.getRequestURI(), HEADER_NAME);
            writeError(res, HttpStatus.UNAUTHORIZED, "missing_service_token",
                "X-Service-Token header required for /api/internal endpoints");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void writeError(HttpServletResponse res, HttpStatus status, String code, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(
            "{\"code\":\"" + code + "\","
          + "\"message\":\"" + message + "\","
          + "\"timestamp\":\"" + Instant.now() + "\"}");
    }

    /**
     * Length-leaking-safe equality check. The lengths still leak (early return
     * when they differ), which is acceptable — knowing the token LENGTH does
     * not help an attacker materially. What we MUST prevent is per-character
     * timing differences in the matched-prefix portion.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
