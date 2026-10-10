package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.component.provider.ProviderAvailabilityService;
import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.vo.TenantProviderConfig;
import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped admin controls for AI providers.
 *
 * <p>Each tenant admin enables the providers their organisation is allowed to
 * use, supplies a tenant-wide API key (stored in OpenBao), optionally sets a
 * preferred model, and picks one provider as the default selection for end
 * users. End-users still see the per-user key dialog as an override — admin
 * config is the baseline, not a lock.</p>
 *
 * <p>All endpoints require {@code ROLE_admin} and resolve the tenant from the
 * JWT-bound {@link TenantContextHolder}. Platform admins acting on behalf of
 * a tenant via {@code X-Tenant-Id} are honoured by the resolution filter, so
 * no special-casing is needed here.</p>
 */
@RestController
@RequestMapping("/api/nl-query/admin/tenant/providers")
@PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin')")
public class TenantProviderAdminController {

    private static final Logger log = LogManager.getLogger(TenantProviderAdminController.class);

    private final ChatProviderRegistry registry;
    private final TenantProviderConfigStore store;
    private final ProviderAvailabilityService availability;

    public TenantProviderAdminController(ChatProviderRegistry registry,
                                         TenantProviderConfigStore store,
                                         ProviderAvailabilityService availability) {
        this.registry = registry;
        this.store = store;
        this.availability = availability;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatProvider p : registry.all()) {
            TenantProviderConfig cfg = store.read(tenantId, p.id());
            rows.add(describe(p, cfg));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backendConfigured", store.isBackendConfigured());
        body.put("tenantId", tenantId.toString());
        body.put("providers", rows);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> upsert(
            @PathVariable String providerId,
            @RequestBody Map<String, Object> body) {

        UUID tenantId = TenantContextHolder.requireTenantId();

        if (!store.isBackendConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "Tenant provider storage is not configured. Configure the Infisical secrets backend on the server."));
        }
        ChatProvider provider = lookup(providerId);
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + providerId));
        }

        boolean enabled    = asBool(body.get("enabled"), false);
        boolean isDefault  = asBool(body.get("isDefault"), false);
        // apiKey:
        //   null / absent → preserve existing
        //   ""            → explicit clear
        //   anything else → set
        String apiKey = body.containsKey("apiKey") ? asString(body.get("apiKey")) : null;
        String model  = asString(body.get("model"));

        if (!enabled) {
            // disabling the provider implicitly clears its default flag so we
            // never leave a tenant pointing at a disabled provider.
            isDefault = false;
        }

        // Internal providers don't need a key — reject attempts to set one
        // so the UI surfaces the misconfiguration instead of silently storing it.
        if (provider.isInternal() && apiKey != null && !apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "In-house provider '" + providerId + "' does not accept an API key."));
        }
        // Never let a tenant default point at an in-house model this build does
        // not serve — end users would silently fall through at chat time.
        if (isDefault && provider.isInternal()
                && !availability.isAvailable(provider, TenantProviderConfig.empty())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "In-house provider '" + providerId
                    + "' is not available in this deployment and cannot be the tenant default."));
        }
        // External providers must have a key on first enable, otherwise the
        // provider will throw at chat-time.
        if (!provider.isInternal() && enabled) {
            TenantProviderConfig existing = store.read(tenantId, providerId);
            boolean hasExistingKey = existing.hasApiKey();
            boolean clearingKey    = apiKey != null && apiKey.isBlank();
            boolean providingKey   = apiKey != null && !apiKey.isBlank();
            if (!providingKey && (!hasExistingKey || clearingKey)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "External provider '" + providerId
                        + "' requires an API key before it can be enabled."));
            }
        }

        try {
            store.writePreservingKey(tenantId, providerId, enabled, apiKey, model, isDefault);

            // Enforce single-default: if this row is now the default, clear the
            // flag on every other provider for this tenant.
            if (isDefault) {
                for (ChatProvider other : registry.all()) {
                    if (other.id().equals(providerId)) continue;
                    TenantProviderConfig otherCfg = store.read(tenantId, other.id());
                    if (otherCfg.isDefault()) {
                        store.write(tenantId, other.id(), new TenantProviderConfig(
                                otherCfg.enabled(), otherCfg.apiKey(), otherCfg.model(), false));
                    }
                }
            }
        } catch (RuntimeException e) {
            // Surface a specific, actionable message (the frontend reads `error`)
            // instead of letting it fall through to the generic 500 handler,
            // which the UI could only show as "Could not change the default."
            return ResponseEntity.status(503).body(Map.of(
                "error", "Could not save the provider configuration — the tenant settings store "
                    + "(ArcadeDB) is temporarily unavailable. Please retry once it is healthy."));
        }

        return ResponseEntity.ok(describe(provider, store.read(tenantId, providerId)));
    }

    @DeleteMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String providerId) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!store.isBackendConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "Tenant provider storage is not configured."));
        }
        ChatProvider provider = lookup(providerId);
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + providerId));
        }
        store.delete(tenantId, providerId);
        return ResponseEntity.ok(describe(provider, TenantProviderConfig.empty()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> describe(ChatProvider p, TenantProviderConfig cfg) {
        ProviderAvailabilityService.Status status = availability.status(p, cfg);
        boolean available = status == ProviderAvailabilityService.Status.AVAILABLE;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.id());
        m.put("label",       p.label());
        m.put("description", p.description());
        m.put("internal",    p.isInternal());
        // "Available" = the provider can serve requests right now. For in-house
        // providers that means built into this deployment AND served by the
        // Ollama runtime; for external ones, enabled by the admin with a key.
        m.put("available",          available);
        m.put("availabilityStatus", status.name());
        // Internal providers have no tenant enable toggle — their effective
        // enabled state is their availability.
        m.put("enabled",     p.isInternal() ? available : cfg.enabled());
        m.put("isDefault",   cfg.isDefault());
        // 'model' stays the TENANT OVERRIDE only — the edit dialog and the
        // set-default PUT round-trip this field, so widening it to the resolved
        // model would silently pin that model as an override on the next save.
        m.put("model",       cfg.model().orElse(null));
        // What the provider would actually call, for display. Without this the
        // table can only render "default" and an operator has no way to see which
        // model an in-house provider is pointed at (OLLAMA_CHAT_MODEL and friends).
        String resolved = resolvedModel(p);
        m.put("resolvedModel", resolved);
        m.put("modelFamily",   ModelFamily.of(resolved, p.id()));
        // Key value never leaves OpenBao — only a presence flag is returned.
        m.put("keyConfigured", !p.isInternal() && cfg.hasApiKey());
        return m;
    }

    /**
     * Resolved model name, best-effort. External providers read tenant config
     * (and therefore OpenBao) to resolve theirs, and the table must still list a
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

    private ChatProvider lookup(String providerId) {
        try {
            return registry.resolve(providerId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String asString(Object o) {
        return (o instanceof String s) ? s : null;
    }

    private static boolean asBool(Object o, boolean defaultValue) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s)  return Boolean.parseBoolean(s);
        return defaultValue;
    }
}
