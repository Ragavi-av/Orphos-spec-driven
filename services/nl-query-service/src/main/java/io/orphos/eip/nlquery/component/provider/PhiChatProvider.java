package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Second in-house provider — Microsoft Phi-3.5 Mini served by the same Ollama
 * container as the default Llama model. Runs on the operator's own hardware,
 * no external API, no SSO secrets needed.
 *
 * <p>The model is built on demand via {@link OllamaModelFactory}, which applies
 * the admin-editable tuning for {@code inhouse-phi}. Phi-3.5 advertises a 128k
 * context, so pinning {@code num-ctx} (default 4096) is what keeps the model
 * runner from being OOM-killed — see the tuning notes in the LLM Prompts tab.</p>
 */
@Component
public class PhiChatProvider implements ChatProvider {

    private final OllamaModelFactory factory;
    private final String baseUrl;
    private final String modelName;
    private final String promptSuffix;
    private final double temperature;
    private final int numPredict;
    private final int numCtx;
    private final int timeoutSeconds;

    public PhiChatProvider(
            OllamaModelFactory factory,
            @Value("${ollama.base-url:http://eip-ollama:11434}")        String baseUrl,
            @Value("${ollama.alt-chat-model:phi3.5}")                   String modelName,
            @Value("${chat-providers.inhouse-phi.temperature:0.2}")     double temperature,
            @Value("${chat-providers.inhouse-phi.num-predict:1024}")    int numPredict,
            @Value("${chat-providers.inhouse-phi.num-ctx:4096}")        int numCtx,
            @Value("${chat-providers.inhouse-phi.timeout-seconds:240}") int timeoutSeconds,
            @Value("${chat-providers.inhouse-phi.prompt-suffix:}")      String promptSuffix) {
        this.factory        = factory;
        this.baseUrl        = baseUrl;
        this.modelName      = modelName;
        this.temperature    = temperature;
        this.numPredict     = numPredict;
        this.numCtx         = numCtx;
        this.timeoutSeconds = timeoutSeconds;
        this.promptSuffix   = promptSuffix == null ? "" : promptSuffix;
    }

    @Override public String id()          { return "inhouse-phi"; }

    // Derived from ollama.alt-chat-model rather than hardcoded — see the note in
    // OllamaChatProvider: the id is fixed, the model is operator-configurable.
    @Override public String label()       { return "In-house · " + ModelFamily.of(modelName, id()); }
    @Override public String description() {
        return modelName + " served from the bundled Ollama container. "
             + "Strong instruction-following for its size; good for structured briefings.";
    }
    @Override public boolean isInternal() { return true; }
    @Override public boolean isAvailable(ProviderOverrides overrides) { return true; }
    @Override public String  tuningSuffix() { return promptSuffix; }
    @Override public String  ollamaBaseUrl() { return baseUrl; }

    @Override
    public String resolveModelName(ProviderOverrides overrides) {
        return modelName;
    }

    @Override
    public String chat(String prompt, ProviderOverrides overrides) {
        return factory.get(baseUrl, modelName, id(), temperature, numPredict, numCtx, timeoutSeconds)
                .chat(prompt);
    }
}
