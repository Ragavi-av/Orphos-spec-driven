package io.orphos.eip.common.access;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.common.http.EipHttpHeaders;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AccessGrantSource} for services that do NOT own the RBAC store: fetches
 * the current tenant's grants from platform-core's internal endpoint
 * ({@code GET /api/internal/access-grants}) over service-to-service HTTP, with a
 * short per-tenant TTL cache so it isn't hit on every request.
 *
 * <p>Sends the shared internal secret under BOTH header names the platform
 * enforces ({@code X-Service-Token} for the {@code /api/internal/**} filter and
 * {@code X-Internal-Token} for the in-controller check) plus the {@code X-Tenant-Id}
 * of the current tenant. On a failure (network error, non-2xx) the last good map is
 * served for up to {@link #STALE_GRACE_MILLIS} past its expiry; with no usable map
 * the source reports unavailable ({@code null}, never cached) and the filter applies
 * its fail mode. Recovery is automatic on the next request once platform-core answers.</p>
 *
 * <p>The {@link RestTemplate} is built lenient so a non-2xx arrives as a response
 * to inspect rather than an exception &mdash; the unavailable branch below depends on
 * telling "platform-core said no grants" apart from "platform-core is unreachable".</p>
 *
 * <p>Every request is also gated by a circuit breaker: once platform-core fails (or times
 * out) enough times in a row, the breaker opens and further calls fail instantly instead of
 * waiting out {@code READ_TIMEOUT} on a peer that's already known to be down — every request
 * to every service behind {@code AccessEnforcementFilter} would otherwise stall for up to 5s
 * apiece while platform-core is unreachable. Rejections land in the same catch-and-unavailable
 * branch as a real network error, so the behaviour above is unchanged, just faster.</p>
 */
public class HttpAccessGrantSource implements AccessGrantSource {

    private static final Logger log = LogManager.getLogger(HttpAccessGrantSource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default cache TTL — long enough to spare platform-core, short enough that grant edits take effect promptly. */
    private static final long DEFAULT_TTL_MILLIS = 15_000L;

    /** How long past expiry the last good map is still served when platform-core can't be reached. */
    static final long STALE_GRACE_MILLIS = 15 * 60_000L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String token;
    private final long ttlMillis;
    private final RestTemplate rest;
    // Standalone (not registry-shared): this class is `new`'d directly by each service's own
    // AccessEnforcementConfig rather than resolved through Spring, so it can't take
    // EipCircuitBreakers as a collaborator — see EipCircuitBreakers#standalone.
    private final CircuitBreaker breaker = EipCircuitBreakers.standalone("platform-core-access-grants");

    private record Entry(Map<String, Map<String, String>> grants, long expiresAt) {}
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public HttpAccessGrantSource(EipRestTemplates restTemplates, String baseUrl, String token) {
        this(restTemplates, baseUrl, token, DEFAULT_TTL_MILLIS);
    }

    public HttpAccessGrantSource(EipRestTemplates restTemplates, String baseUrl, String token, long ttlMillis) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.ttlMillis = Math.max(0, ttlMillis);
        this.rest = restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Override
    public Map<String, Map<String, String>> grantsForCurrentTenant() {
        if (baseUrl.isBlank()) return null;            // not configured → unavailable
        TenantContext ctx = TenantContextHolder.currentOrNull();
        if (ctx == null) return null;                  // no tenant → unavailable
        String tenantId = ctx.tenantId().toString();

        long now = System.currentTimeMillis();
        Entry cached = cache.get(tenantId);
        if (ttlMillis > 0 && cached != null && cached.expiresAt() > now) return cached.grants();

        Map<String, Map<String, String>> fetched = fetch(tenantId);
        if (fetched == null) {
            // Serve the last good map through a short outage (the filter fails closed
            // by default, so a platform-core restart must not 503 every mapped route).
            if (cached != null && cached.expiresAt() + STALE_GRACE_MILLIS > now) {
                log.debug("access-grants fetch failed for tenant {} — serving last good map", tenantId);
                return cached.grants();
            }
            return null;                               // no usable map → unavailable
        }
        cache.put(tenantId, new Entry(fetched, now + ttlMillis));
        return fetched;
    }

    /** Fetches grants for {@code tenantId}, or {@code null} on any failure (caller treats it as unavailable). */
    private Map<String, Map<String, String>> fetch(String tenantId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(EipHttpHeaders.SERVICE_TOKEN, token);
            headers.set(EipHttpHeaders.INTERNAL_TOKEN, token);
            headers.set(EipHttpHeaders.TENANT_ID, tenantId);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> resp = breaker.executeSupplier(() -> rest.exchange(
                    baseUrl + "/api/internal/access-grants", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class));
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.debug("access-grants fetch returned HTTP {} — treating as no grants",
                        resp.getStatusCode().value());
                return null;
            }
            // Parsed with Jackson rather than a message converter to keep the exact
            // row shape AccessEnforcementFilter.toGrantMap expects.
            List<Map<String, Object>> rows = MAPPER.readValue(resp.getBody(), new TypeReference<>() {});
            return AccessEnforcementFilter.toGrantMap(rows);
        } catch (Exception e) {
            log.debug("Could not fetch access grants from {}: {} — treating as no grants", baseUrl, e.getMessage());
            return null;
        }
    }
}
