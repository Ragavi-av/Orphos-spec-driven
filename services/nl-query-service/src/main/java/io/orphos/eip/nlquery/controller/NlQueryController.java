package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.constants.AiUsageTypes;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.component.provider.InhouseProviderGate;
import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.vo.TenantProviderConfig;
import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import io.orphos.eip.nlquery.component.UsageAssignmentResolver;
import io.orphos.eip.nlquery.service.NlQueryService;
import io.orphos.eip.nlquery.vo.TrustSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/nl-query")
public class NlQueryController {

    private static final Logger log = LogManager.getLogger(NlQueryController.class);

    private final NlQueryService nlQueryService;
    private final ChatProviderRegistry providerRegistry;
    private final TenantProviderConfigStore tenantConfig;
    private final InhouseProviderGate inhouseGate;
    private final UsageAssignmentResolver usageResolver;

    public NlQueryController(NlQueryService nlQueryService,
                             ChatProviderRegistry providerRegistry,
                             TenantProviderConfigStore tenantConfig,
                             InhouseProviderGate inhouseGate,
                             UsageAssignmentResolver usageResolver) {
        this.nlQueryService = nlQueryService;
        this.providerRegistry = providerRegistry;
        this.tenantConfig = tenantConfig;
        this.inhouseGate = inhouseGate;
        this.usageResolver = usageResolver;
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
        Object rawQuestion = body.get("question");
        if (!(rawQuestion instanceof String) || ((String) rawQuestion).isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'question' is required"));
        }
        String question = ((String) rawQuestion).trim();
        if (question.length() > 500) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Question must be 500 characters or fewer"));
        }

        String providerId = asString(body.get("provider"));
        ProviderOverrides overrides = new ProviderOverrides(
                asString(body.get("apiKey")),
                asString(body.get("model"))
        );

        // No explicit provider from the caller → honour the admin's "copilot"
        // usage-type assignment (provider + pinned model) before the generic
        // tenant-default resolution kicks in downstream.
        if (providerId == null || providerId.isBlank()) {
            UUID tenantId = TenantContextHolder.current().map(c -> c.tenantId()).orElse(null);
            var assigned = usageResolver.resolve(tenantId, AiUsageTypes.COPILOT);
            if (assigned.isPresent()) {
                providerId = assigned.get().provider().id();
                if (!overrides.hasModel() && assigned.get().model().isPresent()) {
                    overrides = new ProviderOverrides(overrides.apiKey(), assigned.get().model().get());
                }
            }
        }

        try {
            NlQueryService.NlQueryResult result = nlQueryService.answer(question, providerId, overrides);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("answer",    result.answer());
            resp.put("followups", result.followups());
            resp.put("provider",  result.providerId());
            // The model that answered, and its family. 'provider' above is the
            // routing key and mis-names the family for the historical ids
            // ('gemma' → gemini-*, 'inhouse-hi' → qwen2.5), so a UI naming
            // the model to the user reads these two.
            resp.put("model",       result.modelName());
            resp.put("modelFamily", ModelFamily.of(result.modelName(), result.providerId()));
            resp.put("question",  question);
            resp.put("timestamp", Instant.now().toString());
            // Trust envelope — present for genuine briefings, absent for scope
            // declines or when the trust layer is disabled.
            if (result.trust() != null) {
                resp.put("trust", trustToMap(result.trust()));
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "AI service temporarily unavailable: " + e.getMessage()));
        }
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> providers() {
        UUID tenantId = TenantContextHolder.current().map(c -> c.tenantId()).orElse(null);

        // Internal providers are always available (no key needed), but in-house
        // Ollama providers are further gated by OLLAMA_MODEL_MODE / OLLAMA_EXTRA_MODELS
        // so the picker only shows models that have actually been pulled.
        // External providers are only listed when the tenant admin has enabled them.
        Set<String> inhouseEnabled = inhouseGate.enabledInhouseIds();
        List<Map<String, Object>> list = new ArrayList<>();
        String tenantDefault = null;
        for (ChatProvider p : providerRegistry.all()) {
            // Skip optional in-house providers not configured for current model mode
            if (p.isInternal() && !inhouseEnabled.contains(p.id())) continue;
            TenantProviderConfig cfg = (tenantId == null)
                    ? TenantProviderConfig.empty()
                    : tenantConfig.read(tenantId, p.id());
            // An external provider is normally listed only once a tenant admin has
            // enabled it. Also list one that already has a usable server-wide key
            // (GEMINI_API_KEY / ANTHROPIC_API_KEY) — that env fallback exists
            // precisely for local/dev and no-OpenBao setups, and without this the
            // provider is configured, reachable, and yet invisible in the picker.
            boolean enabledForTenant = p.isInternal() || cfg.enabled()
                    || p.isAvailable(ProviderOverrides.empty());
            if (!enabledForTenant) continue;
            list.add(describe(p, cfg));
            if (cfg.isDefault() && cfg.enabled()) {
                tenantDefault = p.id();
            }
        }

        // The copilot usage-type assignment (if any) beats the generic tenant
        // default — it is the more specific admin intent for this surface.
        String copilotAssigned = usageResolver.resolve(tenantId, AiUsageTypes.COPILOT)
                .map(r -> r.provider().id())
                .orElse(null);

        // Fall back to the service-configured default (nlquery.default-provider)
        // when the admin hasn't pinned one — that setting is how an operator points
        // the whole deployment at a fast external model without touching each
        // tenant. It self-downgrades to in-house if the provider has no usable key.
        String defaultId = (copilotAssigned != null) ? copilotAssigned
                : (tenantDefault != null) ? tenantDefault
                : providerRegistry.defaultProviderId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("default",       defaultId);
        body.put("tenantDefault", tenantDefault);
        body.put("providers",     list);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    private Map<String, Object> describe(ChatProvider p, TenantProviderConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.id());
        m.put("label",       p.label());
        m.put("description", p.description());
        m.put("internal",    p.isInternal());
        // The model this provider would actually call for the current tenant, plus
        // its family. The provider id is a routing key and two of them are
        // historical misnomers ('gemma' serves gemini-* models, 'inhouse-hi'
        // serves Qwen2.5), so any UI that names the model must read these rather
        // than derive a family from the id or the static label.
        String model = resolvedModel(p);
        m.put("model",       model);
        m.put("modelFamily", ModelFamily.of(model, p.id()));
        // Availability is reported without per-request overrides — UI uses this
        // to decide whether to nudge the user toward saving a personal key.
        m.put("available",   p.isAvailable(ProviderOverrides.empty()));
        // Surface tenant intent so the UI can render badges ("default") without
        // a second round-trip to the admin endpoint.
        m.put("tenantDefault", cfg != null && cfg.isDefault());
        m.put("tenantKeyConfigured", cfg != null && cfg.hasApiKey());
        return m;
    }

    /**
     * Resolved model name, best-effort: the external providers read tenant config
     * (and therefore OpenBao) to resolve theirs, and the picker must still list a
     * provider whose config lookup failed.
     */
    private String resolvedModel(ChatProvider p) {
        try {
            return p.resolveModelName(ProviderOverrides.empty());
        } catch (Exception e) {
            log.debug("Could not resolve model name for provider {}: {}", p.id(), e.getMessage());
            return null;
        }
    }

    /** Serialise the trust signal for the /ask JSON response. */
    private static Map<String, Object> trustToMap(io.orphos.eip.nlquery.vo.TrustSignal t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grounding",    t.grounding().name());
        m.put("confidence",   t.confidence());
        m.put("verification", t.verification().name());
        m.put("caveats",      t.caveats());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (io.orphos.eip.nlquery.vo.TrustSignal.Source s : t.sources()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("type", s.type());
            sm.put("id",   s.id());
            sm.put("relevance", s.relevance());
            sources.add(sm);
        }
        m.put("sources", sources);
        return m;
    }

    private static String asString(Object o) {
        return (o instanceof String s) ? s : null;
    }
}
