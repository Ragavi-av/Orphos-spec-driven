package io.orphos.eip.common.security;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AllowedIssuersProvider} that fetches the live issuer list from
 * platform-core-service over HTTP. Used by every non-admin service so adding a new
 * tenant doesn't require touching their config.
 *
 * <p>Caching: the latest successful response is held in an
 * {@link AtomicReference} along with its expiry instant. Reads hit the cache
 * when fresh, refresh in-band when stale. Failures fall back to the last-known
 * value (so platform-core-service hiccups don't lock every service out of accepting
 * tokens). On cold start with an unreachable platform-core-service the provider
 * returns an empty set, which means every JWT is rejected until platform-core-service
 * recovers — by design.</p>
 *
 * <p>Always composes the result with {@link SecurityProperties#getAllowedIssuers()}
 * (static fallback) and {@link SecurityProperties#getPlatformIssuer()}
 * (always-trusted platform realm), so an empty / unreachable platform-core-service
 * does not lock out platform-superadmins.</p>
 *
 * <p>Every JWT-bearing request on every service runs through {@link #allowedIssuers()}, so a
 * slow (not just down) platform-core-service would otherwise stall authentication for the whole
 * request rate. A circuit breaker in front of {@link #refresh}'s call opens after repeated
 * failures and fails instantly from then on — falling back to the last-known issuer set exactly
 * like a caught network exception already does — until platform-core-service is healthy again.</p>
 */
public class AdminPollingAllowedIssuersProvider implements AllowedIssuersProvider {

    private static final Logger log = LogManager.getLogger(AdminPollingAllowedIssuersProvider.class);
    private static final String DISCOVERY_PATH = "/api/internal/tenant-issuers";

    private final String adminBaseUrl;
    private final Duration cacheTtl;
    private final RestTemplate rest;
    private final SecurityProperties props;
    private final CircuitBreaker breaker;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public AdminPollingAllowedIssuersProvider(
            String adminBaseUrl,
            Duration cacheTtl,
            Duration connectTimeout,
            Duration readTimeout,
            SecurityProperties props,
            EipCircuitBreakers circuitBreakers) {
        this.adminBaseUrl = stripTrailingSlash(adminBaseUrl);
        this.cacheTtl = cacheTtl;
        this.props = props;
        this.rest = new RestTemplateBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
        this.breaker = circuitBreakers.breaker("platform-core-tenant-issuers");
    }

    @Override
    public Set<String> allowedIssuers() {
        Snapshot current = cache.get();
        Set<String> tenantIssuers;
        if (current != null && Instant.now().isBefore(current.expiresAt)) {
            tenantIssuers = current.issuers;
        } else {
            tenantIssuers = refresh(current);
        }
        return compose(tenantIssuers);
    }

    private Set<String> refresh(Snapshot stale) {
        try {
            HttpHeaders headers = new HttpHeaders();
            String token = props.getTenantDiscovery().getServiceToken();
            if (token != null && !token.isBlank()) {
                headers.set("X-Service-Token", token);
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String[]> resp = breaker.executeSupplier(() -> rest.exchange(
                    adminBaseUrl + DISCOVERY_PATH, HttpMethod.GET, entity, String[].class));
            String[] list = resp.getBody();
            Set<String> issuers = (list == null) ? Set.of() : Set.copyOf(Arrays.asList(list));
            cache.set(new Snapshot(issuers, Instant.now().plus(cacheTtl)));
            if (log.isDebugEnabled()) {
                log.debug("Refreshed tenant-issuer list from platform-core-service: {} entries", issuers.size());
            }
            return issuers;
        } catch (Exception e) {
            int fallbackSize = stale != null ? stale.issuers.size() : 0;
            log.warn("Tenant-issuer poll failed ({}): {} — falling back to {} cached issuers",
                    adminBaseUrl + DISCOVERY_PATH, e.getMessage(), fallbackSize);
            return stale != null ? stale.issuers : Set.of();
        }
    }

    private Set<String> compose(Set<String> tenantIssuers) {
        Set<String> out = new LinkedHashSet<>(tenantIssuers);
        String platform = props.getPlatformIssuer();
        if (platform != null && !platform.isBlank()) out.add(platform.trim());
        if (props.getAllowedIssuers() != null) {
            for (String iss : props.getAllowedIssuers()) {
                if (iss != null && !iss.isBlank()) out.add(iss.trim());
            }
        }
        return out;
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private record Snapshot(Set<String> issuers, Instant expiresAt) {}
}
