package io.orphos.eip.mailinsight.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.common.tenant.TenantContextHolder;
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
 * Calls nl-query-service's internal completion endpoint, which reuses the
 * tenant's configured AI provider (Claude/Gemini/Ollama) via the existing
 * ChatProviderRegistry. Authenticated with the shared internal service token
 * plus the {@code X-Tenant-Id} header so nl-query resolves the right provider/key.
 */
@Component
public class NlQueryClient {

    private static final Logger log = LogManager.getLogger(NlQueryClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String internalToken;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public NlQueryClient(
            @Value("${mail-insight.llm.enabled:true}") boolean enabled,
            @Value("${mail-insight.llm.nl-query-url:http://eip-nl-query:8082}") String baseUrl,
            @Value("${mail-insight.llm.internal-token:}") String internalToken,
            @Value("${mail-insight.llm.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.timeoutSeconds = timeoutSeconds;
    }

    private final int timeoutSeconds;

    public boolean isEnabled() {
        return enabled && !internalToken.isEmpty();
    }

    /** Returns the raw completion text, or empty if disabled / failed. */
    public Optional<String> complete(String prompt) {
        return complete(prompt, null);
    }

    /**
     * Completion tagged with a usage type (e.g. "email-classification") so
     * nl-query can honour the tenant admin's per-function provider/model
     * assignment. Null usageType keeps the tenant-default resolution.
     */
    public Optional<String> complete(String prompt, String usageType) {
        if (!isEnabled()) return Optional.empty();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("prompt", prompt);
            if (usageType != null && !usageType.isBlank()) {
                body.put("usageType", usageType);
            }
            String tenantId = TenantContextHolder.requireTenantId().toString();

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

    // ── Tenant prompt templates ──────────────────────────────────────────────

    // Templates change rarely but classification runs per scanned item, so a
    // short per-tenant cache keeps scans from hammering nl-query. Failures are
    // cached as empty for the same TTL so an outage doesn't add per-item latency.
    private static final long PROMPT_CACHE_TTL_MS = 30_000;

    private record CachedPrompt(String text, long fetchedAt) {}

    private final ConcurrentHashMap<String, CachedPrompt> promptCache = new ConcurrentHashMap<>();

    /**
     * The tenant's active (admin-editable) template for a prompt key, from
     * nl-query's versioned prompt store (Administration → Communications →
     * Email AI). Empty when disabled, unreachable or the key is unknown —
     * callers fall back to their bundled template.
     */
    public Optional<String> promptText(String promptKey) {
        if (!isEnabled()) return Optional.empty();
        String tenantId;
        try {
            tenantId = TenantContextHolder.requireTenantId().toString();
        } catch (Exception e) {
            return Optional.empty();
        }
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

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
