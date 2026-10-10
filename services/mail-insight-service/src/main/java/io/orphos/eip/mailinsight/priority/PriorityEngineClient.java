package io.orphos.eip.mailinsight.priority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads priority-engine-service's shared EPQ (Executive Priority Queue) so
 * "Tasks for Action"/"Notifications" show the SAME composite priority ranking
 * (severity × urgency × impact × recency × domain-risk) as the rest of the
 * platform, instead of {@link io.orphos.eip.mailinsight.datasource.DataSourceReader}
 * re-deriving a crude severity-only score for the same Incident/Decision/Alert
 * rows independently.
 *
 * <p>Forwards the calling user's own JWT rather than introducing a new
 * internal-service-token flow — {@code GET /api/priority/queue} only requires
 * a valid JWT (no special role), matching what any authenticated tenant user
 * could already call directly. Same pattern as widgets-service's
 * {@code OnboardingWidgetsClient}.</p>
 *
 * <p>Best-effort: on any failure (priority-engine-service unreachable, no
 * queue built yet for this tenant, no bound JWT), returns an empty map so
 * callers fall back to their own local scoring.</p>
 */
@Component
public class PriorityEngineClient {

    private static final Logger log = LogManager.getLogger(PriorityEngineClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PriorityEngineClient(
            @Value("${mail-insight.priority-engine.url:http://eip-priority-engine:8083}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * The tenant's current EPQ queue, keyed by {@code type + ":" + id} (e.g.
     * "INCIDENT:abc123") for O(1) lookup against a specific entity row.
     * priority-engine-service only scores INCIDENT | ALERT | BUDGET_ITEM |
     * DECISION | KEY_RESULT | PROJECT — SLAMetric/CostCentre rows (which
     * DataSourceReader also surfaces) will simply never be found here.
     */
    public Map<String, JsonNode> queueByKey() {
        String token = currentJwtTokenValue();
        if (token == null) return Map.of();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/priority/queue"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return Map.of();
            JsonNode queue = mapper.readTree(res.body()).path("queue");
            if (!queue.isArray()) return Map.of();
            Map<String, JsonNode> byKey = new LinkedHashMap<>();
            for (JsonNode item : queue) {
                String type = item.path("type").asText(null);
                String id = item.path("id").asText(null);
                if (type != null && id != null) byKey.put(type + ":" + id, item);
            }
            return byKey;
        } catch (Exception e) {
            log.debug("Could not fetch EPQ queue from priority-engine-service: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String currentJwtTokenValue() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        return null;
    }
}
