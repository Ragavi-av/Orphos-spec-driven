package io.orphos.eip.setup.service;

import io.orphos.eip.setup.dto.SetupDtos.AiProvider;
import io.orphos.eip.setup.dto.SetupDtos.LocalModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The selectable AI catalogues — local Ollama models and external providers —
 * driven by configuration, not hardcoded. Both come from properties that default
 * sensibly but are overridable via environment variables when the wizard is
 * launched, so operators can extend the lists without a code change:
 *
 * <pre>
 *   ORPHOS_SETUP_AI_LOCAL_MODELS = "gemma3:4b=Gemma 3 4B,phi3.5=Phi-3.5 Mini,..."
 *   ORPHOS_SETUP_AI_PROVIDERS    = "anthropic|Anthropic (Claude)|ANTHROPIC_API_KEY|ANTHROPIC_MODEL|claude-opus-4-8;gemini|..."
 * </pre>
 *
 * Each provider entry is {@code key|label|apiKeyEnv|modelEnv|defaultModel}; the
 * env-var names stay server-side (used by the config writer) and are never sent
 * to the browser.
 */
@Service
public class AiCatalog {

    /** Full provider definition (incl. the env-var names the config writer targets). */
    public record ProviderDef(String key, String label, String apiKeyEnv, String modelEnv, String defaultModel) {}

    private final List<LocalModel> localModels;
    private final List<ProviderDef> providers;

    public AiCatalog(
            @Value("${orphos.setup.ai.local-models:"
                    + "gemma3:4b=Gemma 3 4B,phi3.5=Phi-3.5 Mini,"
                    + "granite3.1-dense:2b=Granite 3.1 Dense 2B,qwen2.5:1.5b=Qwen 2.5 1.5B,"
                    + "bge-m3=BGE-M3 (embeddings)}") String localModelsSpec,
            @Value("${orphos.setup.ai.providers:"
                    + "anthropic|Anthropic (Claude)|ANTHROPIC_API_KEY|ANTHROPIC_MODEL|claude-opus-4-8;"
                    + "gemini|Google (Gemini)|GEMINI_API_KEY|GEMINI_MODEL|gemma-3-27b-it}") String providersSpec) {
        this.localModels = parseLocalModels(localModelsSpec);
        this.providers = parseProviders(providersSpec);
    }

    public List<LocalModel> localModels() {
        return localModels;
    }

    /** Providers as the UI sees them — no env-var names. */
    public List<AiProvider> providersForUi() {
        List<AiProvider> out = new ArrayList<>(providers.size());
        for (ProviderDef p : providers) out.add(new AiProvider(p.key(), p.label(), p.defaultModel()));
        return out;
    }

    public ProviderDef providerByKey(String key) {
        if (key == null) return null;
        for (ProviderDef p : providers) if (p.key().equalsIgnoreCase(key.trim())) return p;
        return null;
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    private static List<LocalModel> parseLocalModels(String spec) {
        List<LocalModel> out = new ArrayList<>();
        if (spec == null) return out;
        for (String entry : spec.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            // Split on the LAST '=' so model tags containing ':' but a label after
            // '=' parse correctly; if no '=', label = value.
            int eq = e.lastIndexOf('=');
            if (eq > 0) {
                out.add(new LocalModel(e.substring(0, eq).trim(), e.substring(eq + 1).trim()));
            } else {
                out.add(new LocalModel(e, e));
            }
        }
        return out;
    }

    private static List<ProviderDef> parseProviders(String spec) {
        List<ProviderDef> out = new ArrayList<>();
        if (spec == null) return out;
        for (String entry : spec.split(";")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String[] p = e.split("\\|", -1);
            if (p.length < 4) continue; // need at least key|label|apiKeyEnv|modelEnv
            out.add(new ProviderDef(
                    p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim(),
                    p.length > 4 ? p[4].trim() : ""));
        }
        return out;
    }
}
