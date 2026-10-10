package io.orphos.eip.dataingestion.component;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/**
 * Records every successful mutating admin request (POST/PUT/PATCH/DELETE under
 * {@code /api/data-ingestion/admin/**}) into {@code IngestAuditLog} —
 * requirement 8.4. Implemented as a {@link HandlerInterceptor} so the audit
 * trail covers all admin controllers without touching any of them.
 *
 * <p>Only the HTTP method, path and status ever reach the audit row — request
 * bodies (which may carry credentials) are never read. Controllers can append
 * a safe, human-readable note by setting the {@link #DETAIL_ATTRIBUTE} request
 * attribute (e.g. the classification level chosen).</p>
 *
 * <p>Failure policy: auditing must never break the audited request — every
 * error is caught and logged. Requests without a bound tenant context (should
 * not happen on this path) are skipped rather than crashed on.</p>
 */
@Component
public class AuditTrailInterceptor implements HandlerInterceptor {

    /** Request attribute controllers may set to enrich the audit detail. Safe text only. */
    public static final String DETAIL_ATTRIBUTE = "io.orphos.eip.dataingestion.audit.detail";

    static final String ADMIN_PREFIX = "/api/data-ingestion/admin/";

    private static final Logger log = LogManager.getLogger(AuditTrailInterceptor.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditLogRepository repo;

    public AuditTrailInterceptor(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            if (ex != null) return;
            int status = response.getStatus();
            if (status < 200 || status >= 300) return;
            if (!MUTATING.contains(request.getMethod())) return;
            String path = request.getRequestURI();
            if (path == null || !path.startsWith(ADMIN_PREFIX)) return;

            TenantContext tenant = TenantContextHolder.currentOrNull();
            if (tenant == null) {
                log.debug("Skipping audit for {} {} — no tenant context bound", request.getMethod(), path);
                return;
            }

            String detail = request.getMethod() + " " + path + " -> " + status;
            if (request.getAttribute(DETAIL_ATTRIBUTE) instanceof String extra && !extra.isBlank()) {
                detail += " — " + extra;
            }
            repo.insert(tenant.tenantId().toString(), currentActor(),
                    deriveAction(request.getMethod(), path), detail);
        } catch (Exception e) {
            // Fail open for the request, closed for the data: nothing sensitive
            // was written, and the admin operation itself already succeeded.
            log.warn("Audit write failed for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    /** The acting user — JWT {@code preferred_username}, falling back to {@code sub}. */
    static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            if (username != null && !username.isBlank()) return username;
            String sub = jwt.getToken().getSubject();
            if (sub != null && !sub.isBlank()) return sub;
        }
        return auth != null ? auth.getName() : "unknown";
    }

    /**
     * Derives an audit action code from method + path, e.g.
     * {@code POST /.../datasources} → {@code DATASOURCE_CREATED},
     * {@code POST /.../datasources/ds-1/test} → {@code DATASOURCE_TESTED},
     * {@code PUT /.../datasources/ds-1/classification} → {@code DATASOURCE_CLASSIFIED},
     * {@code POST /.../jobs/job-1/trigger} → {@code JOB_TRIGGERED},
     * {@code POST /.../datasources/ds-1/metadata/tables/T/refresh} → {@code METADATA_DISCOVERED}.
     */
    static String deriveAction(String method, String path) {
        String[] seg = path.substring(ADMIN_PREFIX.length()).split("/");
        if (seg.length == 0 || seg[0].isBlank()) return method + "_ADMIN";

        // Metadata discovery is nested under datasources/{id}/metadata/...
        if ("datasources".equals(seg[0]) && seg.length >= 3 && "metadata".equals(seg[2])) {
            return "METADATA_DISCOVERED";
        }

        String noun = noun(seg[0]);
        String last = seg[seg.length - 1];
        // An all-lowercase-letters trailing segment is an action word
        // (test, validate, trigger, ...); ids carry digits/dashes.
        if (seg.length > 1 && last.matches("[a-z]+")) {
            return noun + "_" + switch (last) {
                case "test" -> "TESTED";
                case "validate" -> "VALIDATED";
                case "trigger", "run", "start" -> "TRIGGERED";
                case "cancel", "stop" -> "CANCELLED";
                case "refresh" -> "DISCOVERED";
                case "classification" -> "CLASSIFIED";
                default -> last.toUpperCase(Locale.ROOT);
            };
        }
        return noun + "_" + switch (method) {
            case "POST" -> "CREATED";
            case "DELETE" -> "DELETED";
            default -> "UPDATED";
        };
    }

    private static String noun(String resource) {
        return switch (resource) {
            case "datasources" -> "DATASOURCE";
            case "mappings" -> "MAPPING";
            case "jobs" -> "JOB";
            case "metadata" -> "METADATA";
            default -> {
                String upper = resource.toUpperCase(Locale.ROOT);
                yield upper.endsWith("S") ? upper.substring(0, upper.length() - 1) : upper;
            }
        };
    }
}
