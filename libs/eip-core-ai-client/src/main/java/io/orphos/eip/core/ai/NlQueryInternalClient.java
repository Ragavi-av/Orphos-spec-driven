package io.orphos.eip.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls nl-query-service's internal completion endpoint
 * ({@code POST /api/nl-query/internal/complete}), which resolves the
 * tenant's configured AI provider (Claude/Gemini/Ollama/...) via its
 * ChatProviderRegistry. Authenticated with the shared internal-service token
 * plus an {@code X-Tenant-Id} header so nl-query resolves the right
 * provider/key — callers never need their own LLM provider stack or
 * tenant/user API-key resolution.
 *
 * <p>Extracted from mail-insight-service's original
 * {@code inference.NlQueryClient}, generalized so any service can depend on
 * {@code eip-core-ai-client} instead of hand-rolling this HTTP call.</p>
 */
public class NlQueryInternalClient {

    private static final Logger log = LogManager.getLogger(NlQueryInternalClient.class);

    /**
     * Version of the nl-query internal AI-gateway contract this client speaks.
     * Sent as {@code X-Ai-Contract-Version} on every call so the gateway (a
     * Tier-2 shared base — see the Modular Agents & MCP design §16) can evolve
     * its request/response shape behind a stable, negotiable version while old
     * callers keep working. Bump only on a breaking change to the wire contract.
     */
    public static final String CONTRACT_VERSION = "1";

    /** Header carrying {@link #CONTRACT_VERSION} on internal gateway calls. */
    public static final String CONTRACT_VERSION_HEADER = "X-Ai-Contract-Version";

    private final NlQueryProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    // Lazily created on first use: HttpClient construction opens a selector,
    // which is wasted work for services where the client stays disabled and
    // fails outright in network-restricted (test) environments.
    private volatile HttpClient http;

    public NlQueryInternalClient(NlQueryProperties props) {
        this.props = props;
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                client = http;
            }
        }
        return client;
    }

    public boolean isEnabled() {
        return props.isEnabled() && !props.getInternalToken().isEmpty();
    }

    /** Completes {@code prompt} for the tenant bound to the current thread via {@link TenantContextHolder}. */
    public Optional<String> complete(String prompt) {
        return complete(prompt, (String) null);
    }

    /**
     * Completes {@code prompt} tagged with a usage type (e.g. "briefing") for
     * the thread-bound tenant, so nl-query honours the tenant admin's
     * per-function provider/model assignment. Null keeps the default.
     */
    public Optional<String> complete(String prompt, String usageType) {
        return TenantContextHolder.current()
                .map(ctx -> complete(prompt, ctx.tenantId(), usageType))
                .orElseGet(() -> {
                    log.debug("nl-query internal complete skipped: no tenant bound to current thread");
                    return Optional.empty();
                });
    }

    /** Completes {@code prompt} for an explicit tenant, independent of thread-local context. */
    public Optional<String> complete(String prompt, UUID tenantId) {
        return complete(prompt, tenantId, null);
    }

    /** Completes {@code prompt} for an explicit tenant and usage type. */
    public Optional<String> complete(String prompt, UUID tenantId, String usageType) {
        if (!isEnabled() || tenantId == null) return Optional.empty();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("prompt", prompt);
            if (usageType != null && !usageType.isBlank()) {
                body.put("usageType", usageType);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(props.getBaseUrl()) + "/api/nl-query/internal/complete"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", props.getInternalToken())
                    .header("X-Tenant-Id", tenantId.toString())
                    .header(CONTRACT_VERSION_HEADER, CONTRACT_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
     * The tool-calling model a tenant should plan with, as resolved by nl-query's planner-model
     * gateway (design §9, §15).
     *
     * @param providerId nl-query provider id (e.g. "inhouse", "claude")
     * @param kind       {@code "ollama"} (agent can build it directly) or {@code "external"}
     * @param model      resolved model name
     * @param baseUrl    Ollama base URL for {@code kind == "ollama"}, else blank
     * @param available  whether the provider is currently usable for the tenant
     */
    public record PlannerModelDescriptor(String providerId, String kind, String model,
                                         String baseUrl, boolean available) {
        public boolean isOllama() { return "ollama".equalsIgnoreCase(kind); }
    }

    /**
     * Resolves the tenant's planner model for {@code usageType} (typically {@code "agent-planning"})
     * via {@code POST /api/nl-query/internal/planner-model}. Returns empty when the gateway is
     * disabled/unreachable so the caller falls back to a local default.
     */
    public Optional<PlannerModelDescriptor> resolvePlannerModel(UUID tenantId, String usageType) {
        if (!isEnabled() || tenantId == null) return Optional.empty();
        try {
            ObjectNode reqBody = mapper.createObjectNode();
            if (usageType != null && !usageType.isBlank()) {
                reqBody.put("usageType", usageType);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(props.getBaseUrl()) + "/api/nl-query/internal/planner-model"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", props.getInternalToken())
                    .header("X-Tenant-Id", tenantId.toString())
                    .header(CONTRACT_VERSION_HEADER, CONTRACT_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqBody), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.debug("nl-query planner-model returned {}", res.statusCode());
                return Optional.empty();
            }
            // 204 No Content / empty body ⇒ no explicit planner assignment for this tenant → fall back.
            if (res.statusCode() == 204 || res.body() == null || res.body().isBlank()) {
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(res.body());
            String model = root.path("model").asText("");
            if (model.isBlank()) return Optional.empty();
            return Optional.of(new PlannerModelDescriptor(
                    root.path("providerId").asText(""),
                    root.path("kind").asText("external"),
                    model,
                    root.path("baseUrl").asText(""),
                    root.path("available").asBoolean(true)));
        } catch (Exception e) {
            log.debug("nl-query planner-model resolution failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
