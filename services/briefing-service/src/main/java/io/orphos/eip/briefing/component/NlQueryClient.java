package io.orphos.eip.briefing.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Calls nl-query-service's internal completion endpoint, reusing the tenant's
 * configured AI provider (Claude/Gemini/Ollama) via the existing
 * ChatProviderRegistry — the same pattern doc-ingest-service and
 * mail-insight-service already use.
 *
 * <p>The tenant id is passed explicitly rather than read from
 * {@code TenantContextHolder}: the digest is also composed on the scheduled
 * briefing path, where no request-bound context exists.</p>
 */
@Component
public class NlQueryClient {

    private static final Logger log = LogManager.getLogger(NlQueryClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String internalToken;
    private final int timeoutSeconds;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public NlQueryClient(
            @Value("${briefing.llm.enabled:true}") boolean enabled,
            @Value("${briefing.llm.nl-query-url:http://eip-nl-query:8082}") String baseUrl,
            @Value("${briefing.llm.internal-token:}") String internalToken,
            @Value("${briefing.llm.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : (baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** False when disabled by config or when no internal-service token is available. */
    public boolean isEnabled() {
        return enabled && !internalToken.isEmpty();
    }

    /**
     * Completion tagged with a usage type (see AiUsageTypes) so nl-query honours
     * the tenant admin's per-function provider/model assignment. Empty when
     * disabled or on any failure — callers fall back to their own copy rather
     * than surfacing an error, so an unreachable model never blanks the UI.
     */
    public Optional<String> complete(String tenantId, String prompt, String usageType) {
        if (!isEnabled()) return Optional.empty();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("prompt", prompt);
            if (usageType != null && !usageType.isBlank()) {
                body.put("usageType", usageType);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/nl-query/internal/complete"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Tenant-Id", tenantId)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.debug("nl-query internal complete returned {}", res.statusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(res.body());
            String completion = root.path("completion").asText("");
            return completion.isBlank() ? Optional.empty() : Optional.of(completion);
        } catch (Exception e) {
            log.debug("nl-query internal complete failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
