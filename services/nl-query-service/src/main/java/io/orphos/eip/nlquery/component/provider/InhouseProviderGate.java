package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.component.TenantProviderConfigStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which in-house (Ollama) providers this build actually serves — the "dynamic
 * by build" half of provider selection. The default provider (Qwen, id
 * "inhouse") is always on; optional extras are gated on
 * {@code OLLAMA_MODEL_MODE=MULTIPLE} plus their key appearing in
 * {@code OLLAMA_EXTRA_MODELS}. Previously private to {@code NlQueryController};
 * extracted so the usage-type admin API and resolver apply the same gate.
 */
@Component
public class InhouseProviderGate {

    // Provider IDs for optional in-house models
    private static final String ID_PHI     = "inhouse-phi";
    private static final String ID_GRANITE = "inhouse-granite";
    private static final String ID_HI      = "inhouse-hi"; // hi-memory in-house model (currently Qwen2.5)

    /** SINGLE (default) or MULTIPLE — set in .env as OLLAMA_MODEL_MODE. */
    @Value("${OLLAMA_MODEL_MODE:SINGLE}")
    private String ollamaModelMode;

    /**
     * Comma-separated extra model keys active in MULTIPLE mode.
     * Valid values: phi, granite, hi  (e.g. "phi,granite").
     */
    @Value("${OLLAMA_EXTRA_MODELS:}")
    private String ollamaExtraModels;

    /**
     * Returns the set of in-house provider IDs enabled by the current model
     * mode configuration.
     */
    public Set<String> enabledInhouseIds() {
        Set<String> ids = new HashSet<>();
        ids.add(ChatProviderRegistry.DEFAULT_PROVIDER_ID); // default in-house (Qwen) always shown
        if ("MULTIPLE".equalsIgnoreCase(ollamaModelMode != null ? ollamaModelMode.trim() : "")) {
            Set<String> keys = Arrays.stream((ollamaExtraModels != null ? ollamaExtraModels : "").split(","))
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (keys.contains("phi"))     ids.add(ID_PHI);
            if (keys.contains("granite")) ids.add(ID_GRANITE);
            // "hi" is the general key; "qwen" kept as an alias for the old config.
            if (keys.contains("hi") || keys.contains("qwen")) ids.add(ID_HI);
        }
        return ids;
    }

    /**
     * True when the provider can be offered to this tenant right now:
     * in-house providers must be part of the build's model mode, external
     * providers must have been enabled (with a key) by the tenant admin.
     */
    public boolean isSelectable(ChatProvider p, UUID tenantId, TenantProviderConfigStore tenantConfig) {
        if (p == null) return false;
        if (p.isInternal()) return enabledInhouseIds().contains(p.id());
        return tenantId != null && tenantConfig.isEnabled(tenantId, p.id());
    }
}
