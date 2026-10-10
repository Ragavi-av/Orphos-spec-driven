package io.orphos.eip.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.common.http.EipHttpHeaders;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CustomRoleBaseSource} for services that do not own the role registry:
 * fetches {@code GET /api/internal/role-bases} from platform-core with the shared
 * internal token, cached per tenant.
 *
 * <p>Only consulted for tokens that carry a non-built-in role, so the common case
 * (built-in roles only) never touches the network. On a fetch failure the last good
 * value is served for up to {@link #STALE_GRACE_MILLIS}; past that the source reports
 * unavailable and custom roles inherit nothing until platform-core answers again.</p>
 */
public class HttpCustomRoleBaseSource implements CustomRoleBaseSource {

    private static final Logger log = LogManager.getLogger(HttpCustomRoleBaseSource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long TTL_MILLIS = 60_000L;
    static final long STALE_GRACE_MILLIS = 15 * 60_000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final String baseUrl;
    private final String token;
    private final RestTemplate rest;
    private final CircuitBreaker breaker = EipCircuitBreakers.standalone("platform-core-role-bases");

    private record Entry(Map<String, String> bases, long fetchedAt) {}
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    public HttpCustomRoleBaseSource(EipRestTemplates restTemplates, String baseUrl, String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.rest = restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Override
    public Map<String, String> customRoleBases(UUID tenantId) {
        if (baseUrl.isBlank() || tenantId == null) return null;
        long now = System.currentTimeMillis();
        Entry cached = cache.get(tenantId);
        if (cached != null && now - cached.fetchedAt() < TTL_MILLIS) return cached.bases();

        Map<String, String> fetched = fetch(tenantId);
        if (fetched != null) {
            cache.put(tenantId, new Entry(fetched, now));
            return fetched;
        }
        if (cached != null && now - cached.fetchedAt() < TTL_MILLIS + STALE_GRACE_MILLIS) {
            return cached.bases();
        }
        return null;
    }

    private Map<String, String> fetch(UUID tenantId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(EipHttpHeaders.SERVICE_TOKEN, token);
            headers.set(EipHttpHeaders.INTERNAL_TOKEN, token);
            headers.set(EipHttpHeaders.TENANT_ID, tenantId.toString());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> resp = breaker.executeSupplier(() -> rest.exchange(
                    baseUrl + "/api/internal/role-bases", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class));
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.debug("role-bases fetch returned HTTP {}", resp.getStatusCode().value());
                return null;
            }
            Map<String, String> parsed = new java.util.HashMap<>(
                    MAPPER.readValue(resp.getBody(), new TypeReference<Map<String, String>>() {}));
            parsed.values().removeIf(v -> v == null || v.isBlank());
            return Map.copyOf(parsed);
        } catch (Exception e) {
            log.debug("Could not fetch custom role bases from {}: {}", baseUrl, e.getMessage());
            return null;
        }
    }
}
