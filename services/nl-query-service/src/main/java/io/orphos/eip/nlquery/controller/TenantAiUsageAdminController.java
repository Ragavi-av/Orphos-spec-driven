package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.constants.AiUsageTypes;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.component.provider.ProviderAvailabilityService;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.component.TenantAiUsageAssignmentStore;
import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped admin controls assigning a default AI provider (and optional
 * model) to each platform usage type — AI Co-pilot, Document Search, Email
 * Classification, etc. Unassigned types fall back to the tenant's default
 * provider, so this is an override layer on top of the existing
 * {@link TenantProviderAdminController} configuration.
 *
 * <p>The selectable provider list is live ({@link ProviderAvailabilityService}):
 * in-house providers appear only when the build serves them (OLLAMA_MODEL_MODE /
 * OLLAMA_EXTRA_MODELS) <i>and</i> the Ollama runtime currently loads their
 * model; external providers only when the tenant admin has enabled them with a
 * key. Same role gate as the provider admin API.</p>
 */
@RestController
@RequestMapping("/api/nl-query/admin/tenant/ai-usage")
@PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin')")
public class TenantAiUsageAdminController {

    private final ChatProviderRegistry registry;
    private final TenantProviderConfigStore tenantConfig;
    private final TenantAiUsageAssignmentStore assignments;
    private final ProviderAvailabilityService availability;

    public TenantAiUsageAdminController(ChatProviderRegistry registry,
                                        TenantProviderConfigStore tenantConfig,
                                        TenantAiUsageAssignmentStore assignments,
                                        ProviderAvailabilityService availability) {
        this.registry = registry;
        this.tenantConfig = tenantConfig;
        this.assignments = assignments;
        this.availability = availability;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        Map<String, TenantAiUsageAssignmentStore.Assignment> saved = assignments.readAll(tenantId);

        List<Map<String, Object>> types = new ArrayList<>();
        for (AiUsageTypes.UsageType t : AiUsageTypes.ALL) {
            TenantAiUsageAssignmentStore.Assignment a = saved.get(t.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          t.id());
            m.put("label",       t.label());
            m.put("description", t.description());
            m.put("providerId",  a == null ? null : a.providerId());
            m.put("model",       a == null ? null : a.model().orElse(null));
            // Stale flag: assignment exists but its provider is gone / gated off /
            // disabled — the UI shows a warning and runtime falls back to default.
            m.put("stale",       a != null && !isSelectable(a.providerId(), tenantId));
            types.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId",   tenantId.toString());
        body.put("usageTypes", types);
        body.put("providers",  selectableProviders(tenantId));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{usageType}")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable String usageType,
                                                      @RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!AiUsageTypes.isKnown(usageType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown usage type: " + usageType));
        }
        String providerId = asString(body.get("providerId"));
        if (providerId == null || providerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "providerId is required"));
        }
        if (!isSelectable(providerId, tenantId)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Provider '" + providerId + "' is not available for this tenant. "
                       + "In-house models must be part of the build and loaded in the AI runtime; "
                       + "external providers must be enabled with an API key first."));
        }
        String model = asString(body.get("model"));
        assignments.upsert(tenantId, usageType, providerId,
                Optional.ofNullable(model).map(String::trim).filter(s -> !s.isEmpty()));
        return ResponseEntity.ok(Map.of(
                "usageType",  usageType,
                "providerId", providerId,
                "model",      model == null ? "" : model.trim()));
    }

    /** Reset a usage type to the tenant-default provider. */
    @DeleteMapping("/{usageType}")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String usageType) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!AiUsageTypes.isKnown(usageType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown usage type: " + usageType));
        }
        assignments.delete(tenantId, usageType);
        return ResponseEntity.ok(Map.of("usageType", usageType, "providerId", ""));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Providers this tenant can assign right now, each with the model choices
     * that make sense for it. In-house providers serve exactly one pulled model
     * (fixed by the build); external providers list the tenant-configured model
     * plus the server default — the UI additionally allows free-text entry.
     */
    private List<Map<String, Object>> selectableProviders(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatProvider p : registry.all()) {
            if (!availability.isAvailable(p, tenantId)) continue;
            Set<String> models = new LinkedHashSet<>();
            if (p.isInternal()) {
                models.add(p.resolveModelName(ProviderOverrides.empty()));
            } else {
                tenantConfig.readEnabledModel(tenantId, p.id()).ifPresent(models::add);
                String serverDefault = p.resolveModelName(ProviderOverrides.empty());
                if (serverDefault != null && !serverDefault.isBlank()) models.add(serverDefault);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       p.id());
            m.put("label",    p.label());
            m.put("internal", p.isInternal());
            m.put("models",   new ArrayList<>(models));
            out.add(m);
        }
        return out;
    }

    private boolean isSelectable(String providerId, UUID tenantId) {
        return availability.isAvailable(registry.find(providerId), tenantId);
    }

    private static String asString(Object o) {
        return (o instanceof String s) ? s : null;
    }
}
