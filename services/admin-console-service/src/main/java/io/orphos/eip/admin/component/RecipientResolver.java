package io.orphos.eip.admin.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.common.http.EipHttpHeaders;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a notification rule's audience to concrete email addresses by
 * asking platform-core-service's internal user directory
 * ({@code GET /api/internal/users/emails}), which reads the tenant's (or the
 * platform's) Keycloak realm.
 *
 * <p>Reuses the platform-core base URL and shared internal-service token the
 * service already holds for tenant discovery, so no new secret is needed.</p>
 *
 * <p>Resolution failures return an empty list — the dispatcher records the
 * outcome on the event so admins can see rules that matched but had no
 * reachable recipients.</p>
 *
 * <p>The directory call is gated by a circuit breaker: a rule fires on every matching
 * event, so a stuck platform-core would otherwise cost every one of those dispatches a
 * full {@code TIMEOUT} before falling back to "no recipients". Once the breaker opens the
 * fallback is immediate, and it's the same empty-list fallback used for a normal failure.</p>
 */
@Component
public class RecipientResolver {

    private static final Logger log = LogManager.getLogger(RecipientResolver.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CircuitBreaker breaker;

    private final String directoryBaseUrl;
    private final String internalToken;

    public RecipientResolver(
            EipRestTemplates restTemplates,
            EipCircuitBreakers circuitBreakers,
            @Value("${admin.events.directory-url:${eip.security.tenant-discovery.url:http://eip-platform-core:8090}}") String directoryBaseUrl,
            @Value("${admin.events.internal-token:${eip.security.tenant-discovery.service-token:}}") String internalToken) {
        // Lenient so the "not exactly 200" check below sees the response instead of an exception.
        this.rest = restTemplates.createLenient(TIMEOUT, TIMEOUT);
        this.breaker = circuitBreakers.breaker("platform-core-user-directory");
        this.directoryBaseUrl = directoryBaseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * @param audience PLATFORM_ADMIN | TENANT_ADMIN | ALL_USERS | ROLE
     * @param roleName realm role, only meaningful when audience == ROLE
     * @param tenantId tenant whose realm should be queried (ignored for PLATFORM_ADMIN)
     */
    public List<String> resolve(String audience, String roleName, UUID tenantId) {
        if (internalToken.isEmpty()) {
            log.warn("Recipient resolution skipped — no internal service token configured "
                    + "(admin.events.internal-token)");
            return List.of();
        }
        try {
            StringBuilder url = new StringBuilder(directoryBaseUrl)
                    .append("/api/internal/users/emails?audience=")
                    .append(URLEncoder.encode(audience, StandardCharsets.UTF_8));
            if (roleName != null && !roleName.isBlank()) {
                url.append("&role=").append(URLEncoder.encode(roleName.trim(), StandardCharsets.UTF_8));
            }
            if (tenantId != null) {
                url.append("&tenantId=").append(tenantId);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set(EipHttpHeaders.INTERNAL_TOKEN, internalToken);

            // Deliberately a URI, not a String: the query above is already
            // percent-encoded, and RestTemplate treats a String as a URI *template*
            // — it would re-encode the escapes and read a "{" in a role name as a
            // placeholder. Passing a URI bypasses template expansion entirely.
            URI uri = URI.create(url.toString());

            ResponseEntity<String> resp = breaker.executeSupplier(() ->
                    rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class));
            if (resp.getStatusCode().value() != 200) {
                log.warn("User directory returned HTTP {} for audience={} tenant={}",
                        resp.getStatusCode().value(), audience, tenantId);
                return List.of();
            }
            String body = resp.getBody();
            if (body == null || body.isBlank()) return List.of();
            JsonNode node = mapper.readTree(body);
            List<String> out = new ArrayList<>();
            for (JsonNode e : node.path("emails")) {
                String email = e.asText("");
                if (!email.isBlank()) out.add(email);
            }
            return out;
        } catch (Exception e) {
            log.warn("Recipient resolution failed for audience={} tenant={}: {}",
                    audience, tenantId, e.getMessage());
            return List.of();
        }
    }
}
