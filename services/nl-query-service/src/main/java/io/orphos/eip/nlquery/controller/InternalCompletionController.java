package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.security.InternalTokens;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.constants.AiUsageTypes;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import io.orphos.eip.nlquery.component.UsageAssignmentResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal, service-to-service completion endpoint. Reuses the tenant's
 * configured AI provider (Claude / Gemini / Ollama) via {@link ChatProviderRegistry}
 * so callers (e.g. mail-insight-service) don't duplicate provider/key resolution.
 *
 * <p>Not JWT-gated — guarded by a shared {@code X-Internal-Token} that must match
 * the platform internal-service secret. The tenant is taken from the
 * {@code X-Tenant-Id} header and bound for the duration of the call so per-tenant
 * provider config and keys resolve correctly.</p>
 */
@RestController
@RequestMapping("/api/nl-query/internal")
public class InternalCompletionController {

    private static final Logger log = LogManager.getLogger(InternalCompletionController.class);

    private final ChatProviderRegistry registry;
    private final TenantProviderConfigStore tenantConfig;
    private final UsageAssignmentResolver usageResolver;
    private final String internalToken;

    public InternalCompletionController(ChatProviderRegistry registry,
                                        TenantProviderConfigStore tenantConfig,
                                        UsageAssignmentResolver usageResolver,
                                        @Value("${eip.security.internal-service-token:${EIP_SECURITY_INTERNAL_SERVICE_TOKEN:}}") String internalToken) {
        this.registry = registry;
        this.tenantConfig = tenantConfig;
        this.usageResolver = usageResolver;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                      @RequestBody Map<String, Object> body) {
        if (internalToken.isEmpty() || token == null || !InternalTokens.matches(internalToken, token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }
        UUID tenantId = parseUuid(tenantHeader);
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing or invalid X-Tenant-Id"));
        }
        String prompt = body.get("prompt") == null ? "" : body.get("prompt").toString();
        if (prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        String requestedProvider = body.get("providerId") == null ? null : body.get("providerId").toString();
        String usageType = body.get("usageType") == null ? null : body.get("usageType").toString();

        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        TenantContextHolder.runWith(TenantContext.of(tenantId, null), () -> {
            try {
                Resolved resolved = resolveProvider(requestedProvider, usageType, tenantId);
                ChatProvider provider = resolved.provider();
                String completion = provider.chat(prompt, resolved.overrides());
                result.set(Map.of(
                        "completion", completion == null ? "" : completion,
                        "provider", provider.id(),
                        "model", provider.resolveModelName(resolved.overrides())));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });

        if (failure.get() != null) {
            log.warn("Internal completion failed for tenant {}: {}", tenantId, failure.get().getMessage());
            return ResponseEntity.status(502).body(Map.of("error", failure.get().getMessage()));
        }
        return ResponseEntity.ok(result.get());
    }

    /**
     * Planner-model gateway (TO-BE design §9, §15): resolves <em>which</em> tool-calling model a
     * tenant should plan with for the given usage type (default {@code agent-planning}) and returns a
     * connection descriptor. The agent runtime builds the model from this and runs the tool-calling
     * loop against its MCP servers — keeping the MCP client where the discovery/gateway live, while
     * nl-query stays the single authority on per-tenant provider/model selection.
     *
     * <p>Returns {@code kind:"ollama"} with a {@code baseUrl} for in-house tool-calling models (which
     * the agent can build directly), or {@code kind:"external"} for provider-backed models the agent
     * does not host locally — no secret is ever returned, so the caller falls back to its local
     * default for external kinds until a langchain4j provider module is added agent-side.</p>
     */
    @PostMapping("/planner-model")
    public ResponseEntity<?> plannerModel(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                          @RequestBody(required = false) Map<String, Object> body) {
        if (internalToken.isEmpty() || token == null || !InternalTokens.matches(internalToken, token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }
        UUID tenantId = parseUuid(tenantHeader);
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing or invalid X-Tenant-Id"));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        String requestedProvider = in.get("providerId") == null ? null : in.get("providerId").toString();
        String usageType = in.get("usageType") == null ? AiUsageTypes.AGENT_PLANNING : in.get("usageType").toString();

        AtomicReference<Resolved> resolvedRef = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        TenantContextHolder.runWith(TenantContext.of(tenantId, null), () -> {
            try {
                resolvedRef.set(resolvePlannerProvider(requestedProvider, usageType, tenantId));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });

        if (failure.get() != null) {
            log.warn("Planner-model resolution failed for tenant {}: {}", tenantId, failure.get().getMessage());
            return ResponseEntity.status(502).body(Map.of("error", failure.get().getMessage()));
        }
        Resolved resolved = resolvedRef.get();
        // No explicit planner assignment for this tenant → no override; the agent keeps its own
        // (tool-calling) default rather than being downgraded to the tenant's generic chat model.
        if (resolved == null) {
            return ResponseEntity.noContent().build();
        }
        ChatProvider provider = resolved.provider();
        String baseUrl = provider.ollamaBaseUrl();
        String kind = (baseUrl != null && !baseUrl.isBlank()) ? "ollama" : "external";
        Map<String, Object> descriptor = new java.util.HashMap<>();
        descriptor.put("providerId", provider.id());
        descriptor.put("kind", kind);
        descriptor.put("model", provider.resolveModelName(resolved.overrides()));
        descriptor.put("baseUrl", baseUrl == null ? "" : baseUrl);
        descriptor.put("available", provider.isAvailable(resolved.overrides()));
        return ResponseEntity.ok(descriptor);
    }

    private record Resolved(ChatProvider provider, ProviderOverrides overrides) {}

    /**
     * Planner-model resolution — <em>explicit only</em>: an explicit provider request, else the
     * tenant's usage-type assignment. Returns {@code null} when neither is set, so the caller keeps
     * its own tool-calling default rather than being routed to a generic chat model. (Deliberately
     * does not fall through to tenant-default / first-available like {@link #resolveProvider}.)
     */
    private Resolved resolvePlannerProvider(String requested, String usageType, UUID tenantId) {
        if (requested != null && !requested.isBlank()) {
            return new Resolved(registry.resolve(requested), ProviderOverrides.empty());
        }
        var assigned = usageResolver.resolve(tenantId, usageType);
        if (assigned.isPresent() && assigned.get().provider().isAvailable(ProviderOverrides.empty())) {
            return new Resolved(assigned.get().provider(),
                    new ProviderOverrides(null, assigned.get().model().orElse(null)));
        }
        return null;
    }

    /**
     * Resolution order: explicit request → the tenant's usage-type assignment
     * (which may also pin a model) → the tenant's default provider → first
     * available provider → the in-house default.
     */
    private Resolved resolveProvider(String requested, String usageType, UUID tenantId) {
        if (requested != null && !requested.isBlank()) {
            return new Resolved(registry.resolve(requested), ProviderOverrides.empty());
        }
        var assigned = usageResolver.resolve(tenantId, usageType);
        if (assigned.isPresent() && assigned.get().provider().isAvailable(ProviderOverrides.empty())) {
            return new Resolved(assigned.get().provider(),
                    new ProviderOverrides(null, assigned.get().model().orElse(null)));
        }
        for (ChatProvider p : registry.all()) {
            if (tenantConfig.isDefault(tenantId, p.id()) && p.isAvailable(ProviderOverrides.empty())) {
                return new Resolved(p, ProviderOverrides.empty());
            }
        }
        for (ChatProvider p : registry.all()) {
            if (p.isAvailable(ProviderOverrides.empty())) {
                return new Resolved(p, ProviderOverrides.empty());
            }
        }
        return new Resolved(registry.resolve(null), ProviderOverrides.empty());
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
