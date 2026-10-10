package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.vo.TenantProviderConfig;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Answers "can this provider actually serve requests right now?" for the admin
 * UI, combining every gate that exists:
 * <ul>
 *   <li><b>In-house</b> — the provider must be part of the build's model mode
 *       ({@link InhouseProviderGate}) <i>and</i> its model must currently be
 *       served by the Ollama runtime ({@link OllamaModelCatalog}).</li>
 *   <li><b>External</b> — the tenant admin must have enabled it and a tenant
 *       API key must be on file.</li>
 * </ul>
 * The distinct {@link Status} values let the UI explain <i>why</i> a provider
 * is unavailable instead of showing a bare flag.
 */
@Component
public class ProviderAvailabilityService {

    public enum Status {
        /** Ready to serve requests. */
        AVAILABLE,
        /** In-house model not part of this build's OLLAMA_MODEL_MODE / OLLAMA_EXTRA_MODELS. */
        NOT_IN_BUILD,
        /** In-house model is in the build but the Ollama runtime doesn't serve it (down or not pulled). */
        MODEL_NOT_LOADED,
        /** External provider not enabled by the tenant admin. */
        DISABLED,
        /** External provider enabled but no tenant API key on file (e.g. vault reset). */
        KEY_MISSING,
    }

    private final InhouseProviderGate gate;
    private final OllamaModelCatalog catalog;
    private final TenantProviderConfigStore tenantConfig;

    public ProviderAvailabilityService(InhouseProviderGate gate,
                                       OllamaModelCatalog catalog,
                                       TenantProviderConfigStore tenantConfig) {
        this.gate = gate;
        this.catalog = catalog;
        this.tenantConfig = tenantConfig;
    }

    public Status status(ChatProvider provider, TenantProviderConfig cfg) {
        if (provider.isInternal()) {
            if (!gate.enabledInhouseIds().contains(provider.id())) {
                return Status.NOT_IN_BUILD;
            }
            String baseUrl = provider.ollamaBaseUrl();
            if (baseUrl != null
                    && !catalog.isModelServed(baseUrl, provider.resolveModelName(ProviderOverrides.empty()))) {
                return Status.MODEL_NOT_LOADED;
            }
            return Status.AVAILABLE;
        }
        if (cfg == null || !cfg.enabled()) return Status.DISABLED;
        if (!cfg.hasApiKey())              return Status.KEY_MISSING;
        return Status.AVAILABLE;
    }

    public boolean isAvailable(ChatProvider provider, TenantProviderConfig cfg) {
        return status(provider, cfg) == Status.AVAILABLE;
    }

    /**
     * Tenant-aware convenience: loads the tenant's config for external
     * providers (in-house ones don't need it). Null-safe on both arguments —
     * the drop-in replacement for {@link InhouseProviderGate#isSelectable}.
     */
    public boolean isAvailable(ChatProvider provider, UUID tenantId) {
        if (provider == null) return false;
        if (provider.isInternal()) {
            return isAvailable(provider, TenantProviderConfig.empty());
        }
        if (tenantId == null) return false;
        return isAvailable(provider, tenantConfig.read(tenantId, provider.id()));
    }
}
