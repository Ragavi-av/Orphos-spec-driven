package io.orphos.eip.ingest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.ingest.vo.TenantRoutingInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Caffeine-backed lookup for {@link TenantRoutingInfo} by tenant UUID.
 * Duplicated from graph-proxy-service's identical class — see
 * {@link TenantRoutingInfo} for why this is duplicated rather than shared.
 *
 * <p>Hits platform-core-service's {@code GET /api/internal/tenants/{id}} on
 * cache miss, caches the parsed result for 60 seconds. On HTTP 404 the cache
 * stores a negative entry (Optional.empty) for a short TTL.</p>
 */
@Service
public class TenantInfoCache {

    private static final Logger log = LogManager.getLogger(TenantInfoCache.class);

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cache<UUID, Optional<TenantRoutingInfo>> cache;

    @Value("${eip.security.tenant-discovery.url:}")
    private String adminBaseUrl;

    @Value("${eip.security.tenant-discovery.service-token:}")
    private String serviceToken;

    public TenantInfoCache(
            @Value("${eip.security.tenant-discovery.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${eip.security.tenant-discovery.read-timeout:PT5S}") Duration readTimeout,
            @Value("${eip.security.tenant-discovery.cache-ttl:PT60S}") Duration cacheTtl) {
        this.rest = new RestTemplateBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
        this.cache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(cacheTtl)
                .build();
    }

    /**
     * Look up routing info for {@code tenantId}. Returns {@code Optional.empty()}
     * when the tenant is unknown or platform-core-service is unreachable — callers
     * MUST treat empty as "deny", never "fall through to a shared default", to
     * prevent cross-tenant leakage on a stale cache.
     *
     * <p>Only a CONFIRMED answer — found, or genuinely not found (404) — is
     * written into the cache. An unexpected failure (network error, timeout,
     * malformed response, a non-404 status) still returns {@code Optional.empty()}
     * for this call (fail closed, same as before), but is deliberately not
     * cached: caching it would pin a transient platform-core-service outage as a
     * denial for every lookup of that tenant for the rest of the cache TTL,
     * instead of only for the outage's actual duration. The next call simply
     * retries.</p>
     */
    public Optional<TenantRoutingInfo> find(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        Optional<TenantRoutingInfo> cached = cache.getIfPresent(tenantId);
        if (cached != null) return cached;
        FetchResult result = fetch(tenantId);
        if (result.cacheable()) {
            cache.put(tenantId, result.value());
        }
        return result.value();
    }

    /** Whether {@link #fetch}'s answer is confirmed (safe to cache) or provisional (retry next time). */
    private record FetchResult(Optional<TenantRoutingInfo> value, boolean cacheable) {
        static FetchResult confirmed(Optional<TenantRoutingInfo> value) { return new FetchResult(value, true); }
        static FetchResult unconfirmed() { return new FetchResult(Optional.empty(), false); }
    }

    private FetchResult fetch(UUID tenantId) {
        if (adminBaseUrl == null || adminBaseUrl.isBlank()) {
            log.warn("eip.security.tenant-discovery.url is unset — cannot resolve tenant {}", tenantId);
            return FetchResult.unconfirmed();
        }
        String url = stripTrailingSlash(adminBaseUrl) + "/api/internal/tenants/" + tenantId;
        try {
            HttpHeaders headers = new HttpHeaders();
            if (serviceToken != null && !serviceToken.isBlank()) {
                headers.set("X-Service-Token", serviceToken);
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) return FetchResult.unconfirmed();   // 200 w/ empty body is unexpected, not "not found"
            JsonNode root = mapper.readTree(body);

            String idStr = text(root, "id");
            String isolationStr = text(root, "isolationMode");
            String dataset = text(root, "arcadeDatabase");
            String connId = text(root, "arcadeConnectionId");   // null → bundled/platform ArcadeDB
            String status = text(root, "status");
            if (idStr == null || isolationStr == null || dataset == null) {
                log.warn("Malformed tenant info from platform-core-service for {}: {}", tenantId, body);
                return FetchResult.unconfirmed();
            }
            TenantRoutingInfo info = new TenantRoutingInfo(
                    UUID.fromString(idStr),
                    TenantRoutingInfo.IsolationMode.valueOf(isolationStr),
                    dataset,
                    connId,
                    "ACTIVE".equalsIgnoreCase(status));
            return FetchResult.confirmed(Optional.of(info));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                log.debug("Tenant {} not found in platform-core-service", tenantId);
                return FetchResult.confirmed(Optional.empty());   // genuinely absent — safe to cache
            }
            log.warn("Tenant lookup failed for {} ({} {}): {}",
                    tenantId, e.getStatusCode(), e.getStatusText(), e.getMessage());
            return FetchResult.unconfirmed();
        } catch (Exception e) {
            log.warn("Tenant lookup failed for {}: {}", tenantId, e.getMessage());
            return FetchResult.unconfirmed();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
