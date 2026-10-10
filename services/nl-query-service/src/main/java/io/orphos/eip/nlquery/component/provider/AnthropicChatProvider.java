package io.orphos.eip.nlquery.component.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.common.resilience.UpstreamClientException;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import io.orphos.eip.nlquery.component.UserApiKeyStore;
import io.orphos.eip.nlquery.util.CurrentUser;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
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
import java.util.UUID;

/**
 * External chat provider backed by Anthropic's Messages API.
 * Default model is the latest Opus, but operators or end-users can override
 * to Sonnet / Haiku per deployment or per request.
 *
 * The API key is read from ANTHROPIC_API_KEY by default; callers may also
 * pass a per-request override so an operator can plug in their own key
 * from the UI without redeploying the service.
 */
@Component
public class AnthropicChatProvider implements ChatProvider {

    private static final Logger log = LogManager.getLogger(AnthropicChatProvider.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final String defaultApiKey;
    private final String defaultModel;
    private final UserApiKeyStore userKeys;
    private final TenantProviderConfigStore tenantConfig;
    private final PromptConfigStore prompts;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final String promptSuffix;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EipCircuitBreakers circuitBreakers;
    private final Retry retry;

    public AnthropicChatProvider(
            @Value("${anthropic.api-key:}")                            String defaultApiKey,
            @Value("${anthropic.model:claude-opus-4-7}")               String defaultModel,
            @Value("${chat-providers.claude.temperature:0.3}")         double temperature,
            @Value("${chat-providers.claude.max-tokens:2048}")         int maxTokens,
            @Value("${chat-providers.claude.timeout-seconds:120}")     int timeoutSeconds,
            @Value("${chat-providers.claude.prompt-suffix:}")          String promptSuffix,
            UserApiKeyStore userKeys,
            TenantProviderConfigStore tenantConfig,
            PromptConfigStore prompts,
            EipCircuitBreakers circuitBreakers,
            EipRetries retries) {
        this.defaultApiKey  = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultModel   = defaultModel;
        this.userKeys       = userKeys;
        this.tenantConfig   = tenantConfig;
        this.prompts        = prompts;
        this.temperature    = temperature;
        this.maxTokens      = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.promptSuffix   = promptSuffix == null ? "" : promptSuffix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.circuitBreakers = circuitBreakers;
        // Only 1 retry (not the shared 3-attempt default): each attempt can take up to
        // timeoutSeconds (120s default), so a blind 3-attempt retry could keep a caller
        // waiting for several minutes before failing. A single retry is safe to do blindly
        // here — unlike a DB write, a chat completion request has no server-side persistence
        // for a retry to duplicate; worst case is one extra billed call. A 4xx client error
        // (bad key, bad request) is never retried — it won't succeed and just wastes a call.
        this.retry = retries.retry("claude", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(java.time.Duration.ofMillis(500)))
                .ignoreExceptions(UpstreamClientException.class)
                .build());
    }

    @Override public String tuningSuffix() { return promptSuffix; }

    @Override
    public String resolveModelName(ProviderOverrides overrides) {
        if (overrides != null && overrides.hasModel()) return overrides.model().trim();
        UUID tenantId = currentTenantId();
        if (tenantId != null) {
            String m = tenantConfig.readEnabledModel(tenantId, id()).orElse(null);
            if (m != null && !m.isBlank()) return m.trim();
        }
        return defaultModel;
    }

    @Override public String id()          { return "claude"; }
    @Override public String label()       { return "Claude (Anthropic)"; }
    @Override public String description() {
        return "Anthropic Claude models via api.anthropic.com. Requires an Anthropic API key.";
    }
    @Override public boolean isInternal() { return false; }

    /**
     * The circuit breaker for the calling tenant. Scoped per tenant (not one global "claude"
     * breaker) because API keys are resolved per tenant/user: one tenant's expired key or
     * exhausted quota would otherwise open the breaker for every other tenant sharing this
     * gateway. Breakers are registry-keyed by name, so this is a cheap lookup that reuses the
     * same instance across calls, not a new breaker each time.
     */
    private CircuitBreaker breaker() {
        UUID tenantId = currentTenantId();
        return circuitBreakers.breaker("claude:" + (tenantId == null ? "default" : tenantId));
    }

    @Override
    public boolean isAvailable(ProviderOverrides overrides) {
        // Also false while this tenant's breaker is OPEN, so ChatProviderRegistry's
        // default-provider fallback reacts to "claude is currently failing", not just
        // "claude has no key".
        return resolveKey(overrides) != null && breaker().getState() != CircuitBreaker.State.OPEN;
    }

    @Override
    public String chat(String prompt, ProviderOverrides overrides) {
        String apiKey = resolveKey(overrides);
        if (apiKey == null) {
            throw new IllegalStateException(
                "Claude provider has no API key for this tenant. Ask an admin to enable it under "
              + "Administration → AI Providers, or save a personal key in Settings → Claude.");
        }
        String model = resolveModelName(overrides);

        // Admin-editable tuning (tuning.claude.*), falling back to the @Value defaults.
        UUID tenant   = currentTenantId();
        double temp   = prompts.tuningDouble(tenant, id(), "temperature", temperature);
        int maxTok    = prompts.tuningInt(tenant, id(), "max-tokens", maxTokens);
        int timeout   = prompts.tuningInt(tenant, id(), "timeout-seconds", timeoutSeconds);

        try {
            String body = buildRequestBody(prompt, model, temp, maxTok);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            // CB+retry wrap the network call and the status check together, so a transient
            // 5xx/timeout gets one retry before failing, and repeated failures trip the
            // breaker. This is a single request/response completion call (no streaming), so
            // there's no "mid-stream" content to worry about corrupting on retry.
            HttpResponse<String> res = EipResilienceDecorators.call(() -> sendAndCheck(req), null, breaker(), retry);
            return extractText(res.body());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> sendAndCheck(HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("Anthropic API returned {}: {}", res.statusCode(), truncate(res.body()));
            String detail = "Anthropic API error " + res.statusCode() + ": " + extractError(res.body());
            // A 4xx other than 429 is a client-side problem (bad key, bad request, forbidden):
            // it won't succeed on retry and must not trip the breaker. A 429 (rate limited) and
            // any 5xx do signal an overloaded/failing dependency, so they stay generic runtime
            // exceptions that are retried once and counted toward the breaker.
            if (res.statusCode() / 100 == 4 && res.statusCode() != 429) {
                throw new UpstreamClientException(res.statusCode(), detail);
            }
            throw new RuntimeException(detail);
        }
        return res;
    }

    private String resolveKey(ProviderOverrides overrides) {
        // 1. Per-request override (mainly for scripts / admin escape hatch)
        if (overrides != null && overrides.hasApiKey()) return overrides.apiKey().trim();

        // 2. Per-user SSO-bound key from OpenBao — power-user override that
        //    wins over the tenant config so a user with their own key keeps using it.
        //    Scoped by tenant: the same subject in two tenants has two distinct keys.
        UUID tenantId = currentTenantId();
        if (tenantId != null && userKeys.isBackendConfigured()) {
            String key = CurrentUser.sub()
                    .flatMap(sub -> userKeys.readKey(tenantId, sub, id()))
                    .orElse(null);
            if (key != null && !key.isBlank()) return key.trim();
        }

        // 3. Tenant-wide key configured by an admin under Administration → AI Providers.
        //    Only honoured when the provider is also marked enabled for the tenant.
        if (tenantId != null && tenantConfig.isBackendConfigured()) {
            String key = tenantConfig.readEnabledKey(tenantId, id()).orElse(null);
            if (key != null && !key.isBlank()) return key.trim();
        }

        // 4. Server-wide fallback (env var) — kept for local dev / no-OpenBao setups.
        if (!defaultApiKey.isEmpty()) return defaultApiKey;
        return null;
    }

    private static UUID currentTenantId() {
        return TenantContextHolder.current().map(c -> c.tenantId()).orElse(null);
    }

    private String buildRequestBody(String prompt, String model, double temperature, int maxTokens) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Anthropic request", e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new RuntimeException("Anthropic returned no content blocks: " + truncate(responseBody));
            }
            StringBuilder out = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    out.append(block.path("text").asText(""));
                }
            }
            String text = out.toString().trim();
            if (text.isEmpty()) {
                throw new RuntimeException("Anthropic returned empty text");
            }
            return text;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    private String extractError(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            String msg = root.path("error").path("message").asText("");
            if (msg.isEmpty()) msg = root.path("message").asText("");
            return msg.isEmpty() ? truncate(body) : msg;
        } catch (Exception ignored) {
            return truncate(body);
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
