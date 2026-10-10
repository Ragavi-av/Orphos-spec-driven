package io.orphos.eip.common.tenant;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@link ActiveTenantsProvider} that fetches the live tenant list from
 * platform-core-service via {@code GET /api/internal/active-tenants}. Used by every
 * non-admin background job so a new tenant gets picked up by the next
 * scheduled tick without restarting.
 *
 * <p>Same caching + fallback semantics as
 * {@link io.orphos.eip.common.security.AdminPollingAllowedIssuersProvider}:
 * snapshot in an {@link AtomicReference}, refresh when stale, fall back to
 * last-known on transient failure — a circuit breaker sits in front of the same
 * refresh call so a down platform-core-service fails every poller's tick instantly
 * instead of after a full read-timeout, once it's failed enough times to open.</p>
 */
public class AdminPollingActiveTenantsProvider implements ActiveTenantsProvider {

    private static final Logger log = LogManager.getLogger(AdminPollingActiveTenantsProvider.class);
    private static final String DISCOVERY_PATH = "/api/internal/active-tenants";

    private final String adminBaseUrl;
    private final Duration cacheTtl;
    private final RestTemplate rest;
    private final String serviceToken;
    private final CircuitBreaker breaker;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public AdminPollingActiveTenantsProvider(
            String adminBaseUrl,
            Duration cacheTtl,
            Duration connectTimeout,
            Duration readTimeout,
            String serviceToken,
            EipCircuitBreakers circuitBreakers) {
        this.adminBaseUrl = stripTrailingSlash(adminBaseUrl);
        this.cacheTtl = cacheTtl;
        this.serviceToken = serviceToken;
        this.rest = new RestTemplateBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
        this.breaker = circuitBreakers.breaker("platform-core-active-tenants");
    }

    @Override
    public Set<UUID> activeTenantIds() {
        Snapshot current = cache.get();
        if (current != null && Instant.now().isBefore(current.expiresAt)) {
            return current.tenantIds;
        }
        return refresh(current);
    }

    private Set<UUID> refresh(Snapshot stale) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (serviceToken != null && !serviceToken.isBlank()) {
                headers.set("X-Service-Token", serviceToken);
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String[]> resp = breaker.executeSupplier(() -> rest.exchange(
                    adminBaseUrl + DISCOVERY_PATH, HttpMethod.GET, entity, String[].class));
            String[] list = resp.getBody();
            Set<UUID> ids = (list == null) ? Set.of() :
                    Arrays.stream(list)
                            .filter(s -> s != null && !s.isBlank())
                            .map(UUID::fromString)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            cache.set(new Snapshot(ids, Instant.now().plus(cacheTtl)));
            if (log.isDebugEnabled()) {
                log.debug("Refreshed active-tenant list from platform-core-service: {} entries", ids.size());
            }
            return ids;
        } catch (Exception e) {
            int fallbackSize = stale != null ? stale.tenantIds.size() : 0;
            log.warn("Active-tenant poll failed ({}): {} — falling back to {} cached entries",
                    adminBaseUrl + DISCOVERY_PATH, e.getMessage(), fallbackSize);
            return stale != null ? stale.tenantIds : Set.of();
        }
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private record Snapshot(Set<UUID> tenantIds, Instant expiresAt) {}
}
