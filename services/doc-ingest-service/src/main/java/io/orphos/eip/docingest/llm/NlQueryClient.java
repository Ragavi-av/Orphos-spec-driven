package io.orphos.eip.docingest.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls nl-query-service's internal endpoints, reusing the tenant's configured
 * AI provider (Claude/Gemini/Ollama) via the existing ChatProviderRegistry —
 * the same pattern mail-insight-service uses for email classification.
 *
 * <p>Unlike mail-insight's client, the tenant id is passed explicitly: the
 * ingestion pipeline runs on {@code @Async} executors where no request-bound
 * {@code TenantContextHolder} is available.</p>
 */
@Component
public class NlQueryClient {

    private static final Logger log = LogManager.getLogger(NlQueryClient.class);

    // Templates change rarely but ingestion can process many documents in a
    // burst, so a short per-tenant cache keeps the pipeline from hammering
    // nl-query. Failures are cached as empty for the same TTL.
    private static final long PROMPT_CACHE_TTL_MS = 30_000;

    private record CachedPrompt(String text, long fetchedAt) {}

    private final ConcurrentHashMap<String, CachedPrompt> promptCache = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final String baseUrl;
    private final String internalToken;
    private final int timeoutSeconds;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public NlQueryClient(
            @Value("${doc-ingest.llm.enabled:true}") boolean enabled,
            @Value("${doc-ingest.llm.nl-query-url:http://eip-nl-query:8082}") String baseUrl,
            @Value("${doc-ingest.llm.internal-token:${EIP_SECURITY_INTERNAL_SERVICE_TOKEN:}}") String internalToken,
            @Value("${doc-ingest.llm.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : (baseUrl == null ? "" : baseUrl);
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        return enabled && !internalToken.isEmpty();
    }

    /**
     * Completion tagged with a usage type (e.g. "document-processing") so
     * nl-query honours the tenant admin's per-function provider/model
     * assignment. Empty when disabled or on any failure.
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
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
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

    /**
     * The tenant's active (admin-editable) template for a prompt key, from
     * nl-query's versioned prompt store (Administration → Document Ingestion →
     * Document AI). Empty when disabled, unreachable or the key is unknown —
     * callers fall back to their bundled template.
     */
    public Optional<String> promptText(String tenantId, String promptKey) {
        if (!isEnabled()) return Optional.empty();
        String cacheKey = tenantId + "|" + promptKey;
        long now = System.currentTimeMillis();
        CachedPrompt hit = promptCache.get(cacheKey);
        if (hit != null && now - hit.fetchedAt() < PROMPT_CACHE_TTL_MS) {
            return hit.text().isBlank() ? Optional.empty() : Optional.of(hit.text());
        }
        String fetched = "";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/nl-query/internal/prompts?keys="
                            + URLEncoder.encode(promptKey, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Internal-Token", internalToken)
                    .header("X-Tenant-Id", tenantId)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 == 2) {
                fetched = mapper.readTree(res.body()).path("prompts").path(promptKey).asText("");
            } else {
                log.debug("nl-query prompt fetch for {} returned {}", promptKey, res.statusCode());
            }
        } catch (Exception e) {
            log.debug("nl-query prompt fetch failed for {}: {}", promptKey, e.getMessage());
        }
        promptCache.put(cacheKey, new CachedPrompt(fetched, now));
        return fetched.isBlank() ? Optional.empty() : Optional.of(fetched);
    }
}
