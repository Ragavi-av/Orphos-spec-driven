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
import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
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
import java.util.UUID;

/**
 * External chat provider backed by Google's Generative Language REST API.
 * Supports both Gemma (e.g. gemma-3-27b-it) and Gemini (e.g. gemini-2.0-flash)
 * model families exposed through the same endpoint.
 *
 * The API key is read from the GEMINI_API_KEY env var by default, but callers
 * may also pass a per-request override so an operator can plug in their own
 * key from the UI without redeploying the service.
 */
@Component
public class GeminiChatProvider implements ChatProvider {

    private static final Logger log = LogManager.getLogger(GeminiChatProvider.class);
    private static final String API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String defaultApiKey;
    private final String defaultModel;
    private final UserApiKeyStore userKeys;
    private final TenantProviderConfigStore tenantConfig;
    private final PromptConfigStore prompts;
    private final double temperature;
    private final int maxOutputTokens;
    private final int timeoutSeconds;
    private final String promptSuffix;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EipCircuitBreakers circuitBreakers;
    private final Retry retry;

    public GeminiChatProvider(
            @Value("${gemini.api-key:}")                               String defaultApiKey,
            @Value("${gemini.model:gemma-3-27b-it}")                   String defaultModel,
            @Value("${chat-providers.gemma.temperature:0.3}")          double temperature,
            @Value("${chat-providers.gemma.max-tokens:2048}")          int maxOutputTokens,
            @Value("${chat-providers.gemma.timeout-seconds:120}")      int timeoutSeconds,
            @Value("${chat-providers.gemma.prompt-suffix:}")           String promptSuffix,
            UserApiKeyStore userKeys,
            TenantProviderConfigStore tenantConfig,
            PromptConfigStore prompts,
            EipCircuitBreakers circuitBreakers,
            EipRetries retries) {
        this.defaultApiKey   = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultModel    = defaultModel;
        this.userKeys        = userKeys;
        this.tenantConfig    = tenantConfig;
        this.prompts         = prompts;
        this.temperature     = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.timeoutSeconds  = timeoutSeconds;
        this.promptSuffix    = promptSuffix == null ? "" : promptSuffix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.circuitBreakers = circuitBreakers;
        // See AnthropicChatProvider for why this is 1 retry, not the shared 3-attempt default,
        // and why a 4xx client error is ignored (never retried, never counted by the breaker).
        this.retry = retries.retry("gemma", RetryConfig.custom()
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

    // The id stays 'gemma' — it is a persisted routing key (tenant provider
    // config, prompt-store keys, AiUsage rows, saved user preference). The label
    // must not repeat it: this route serves Gemini models just as often, so the
    // family shown to users is derived from the resolved model (ModelFamily).
    @Override public String id()          { return "gemma"; }
    @Override public String label()       { return "Google AI Studio"; }
    @Override public String description() {
        return "Google Gemma / Gemini models via Google AI Studio. Requires an API key.";
    }
    @Override public boolean isInternal() { return false; }

    /**
     * The circuit breaker for the calling tenant. Scoped per tenant (not one global "gemma"
     * breaker) because API keys are resolved per tenant/user: one tenant's expired key or
     * exhausted quota would otherwise open the breaker for every other tenant sharing this
     * gateway. Breakers are registry-keyed by name, so this is a cheap lookup that reuses the
     * same instance across calls, not a new breaker each time.
     */
    private CircuitBreaker breaker() {
        UUID tenantId = currentTenantId();
        return circuitBreakers.breaker("gemma:" + (tenantId == null ? "default" : tenantId));
    }

    @Override
    public boolean isAvailable(ProviderOverrides overrides) {
        return resolveKey(overrides) != null && breaker().getState() != CircuitBreaker.State.OPEN;
    }

    @Override
    public String chat(String prompt, ProviderOverrides overrides) {
        String apiKey = resolveKey(overrides);
        if (apiKey == null) {
            throw new IllegalStateException(
                "Gemma provider has no API key for this tenant. Ask an admin to enable it under "
              + "Administration → AI Providers, or save a personal key in Settings → Gemma.");
        }
        String model = resolveModelName(overrides);

        // Admin-editable tuning (tuning.gemma.*), falling back to the @Value defaults.
        UUID tenant = currentTenantId();
        double temp = prompts.tuningDouble(tenant, id(), "temperature", temperature);
        int maxTok  = prompts.tuningInt(tenant, id(), "max-tokens", maxOutputTokens);
        int timeout = prompts.tuningInt(tenant, id(), "timeout-seconds", timeoutSeconds);

        try {
            String body = buildRequestBody(prompt, temp, maxTok);
            String url  = API_BASE + URLEncoder.encode(model, StandardCharsets.UTF_8)
                        + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = EipResilienceDecorators.call(() -> sendAndCheck(req), null, breaker(), retry);
            return extractText(res.body());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Gemma API call failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> sendAndCheck(HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Gemma API call failed: " + e.getMessage(), e);
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("Gemma API returned {}: {}", res.statusCode(), truncate(res.body()));
            String detail = "Gemma API error " + res.statusCode() + ": " + extractError(res.body());
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

    private String buildRequestBody(String prompt, double temperature, int maxOutputTokens) {
        ObjectNode root  = mapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode turn  = contents.addObject();
        ArrayNode parts  = turn.putArray("parts");
        parts.addObject().put("text", prompt);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", temperature);
        gen.put("maxOutputTokens", maxOutputTokens);

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Gemma request", e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new RuntimeException("Gemma returned no candidates: " + truncate(responseBody));
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            StringBuilder out = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) out.append(text);
                }
            }
            String text = out.toString().trim();
            if (text.isEmpty()) {
                throw new RuntimeException("Gemma returned empty text");
            }
            return text;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemma response: " + e.getMessage(), e);
        }
    }

    private String extractError(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            String msg = root.path("error").path("message").asText("");
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
