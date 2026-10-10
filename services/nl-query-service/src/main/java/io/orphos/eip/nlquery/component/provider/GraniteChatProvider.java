package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optional in-house provider — IBM Granite 3.1 Dense 2B (Apache-2.0) served
 * by the primary Ollama container (eip-ollama).
 *
 * <p>Only shown in the AI Co-pilot picker when OLLAMA_MODEL_MODE=MULTIPLE and
 * "granite" appears in OLLAMA_EXTRA_MODELS. In SINGLE mode (the default) the
 * picker shows only the Gemma provider.</p>
 */
@Component
public class GraniteChatProvider implements ChatProvider {

    private final OllamaModelFactory factory;
    private final String baseUrl;
    private final String modelName;
    private final String promptSuffix;
    private final double temperature;
    private final int numPredict;
    private final int numCtx;
    private final int timeoutSeconds;

    public GraniteChatProvider(
            OllamaModelFactory factory,
            @Value("${ollama.base-url:http://eip-ollama:11434}")              String baseUrl,
            @Value("${ollama.granite-model:granite3.1-dense:2b}")             String modelName,
            @Value("${chat-providers.inhouse-granite.temperature:0.3}")       double temperature,
            @Value("${chat-providers.inhouse-granite.num-predict:1024}")      int numPredict,
            @Value("${chat-providers.inhouse-granite.num-ctx:4096}")          int numCtx,
            @Value("${chat-providers.inhouse-granite.timeout-seconds:360}")   int timeoutSeconds,
            @Value("${chat-providers.inhouse-granite.prompt-suffix:}")        String promptSuffix) {
        this.factory        = factory;
        this.baseUrl        = baseUrl;
        this.modelName      = modelName;
        this.temperature    = temperature;
        this.numPredict     = numPredict;
        this.numCtx         = numCtx;
        this.timeoutSeconds = timeoutSeconds;
        this.promptSuffix   = promptSuffix == null ? "" : promptSuffix;
    }

    @Override public String id()          { return "inhouse-granite"; }

    // Derived from ollama.granite-model rather than hardcoded — see the note in
    // OllamaChatProvider: the id is fixed, the model is operator-configurable.
    @Override public String label()       { return "In-house · " + ModelFamily.of(modelName, id()); }
    @Override public String description() {
        return modelName + " served from the bundled Ollama container. "
             + "Balanced and instruction-tuned; runs entirely on the operator's hardware.";
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
