package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Third in-house provider — Qwen2.5 1.5B (Apache-2.0) served by the secondary
 * high-memory Ollama container (eip-ollama-hi). At roughly 1.0 GB it sits
 * comfortably alongside the embedding model in that container.
 *
 * <p>Not to be confused with the external {@code GeminiChatProvider}, which
 * talks to Google AI Studio over the public API. This provider runs entirely
 * on the operator's own hardware and needs no API key. The model is built on
 * demand via {@link OllamaModelFactory}, applying admin-editable tuning for
 * {@code inhouse-hi}.</p>
 */
@Component
public class InhouseHiChatProvider implements ChatProvider {

    private final OllamaModelFactory factory;
    private final String baseUrl;
    private final String modelName;
    private final String promptSuffix;
    private final double temperature;
    private final int numPredict;
    private final int numCtx;
    private final int timeoutSeconds;

    public InhouseHiChatProvider(
            OllamaModelFactory factory,
            @Value("${ollama.hi-base-url:http://eip-ollama-hi:11434}")     String baseUrl,
            @Value("${ollama.hi-chat-model:qwen2.5:1.5b}")                   String modelName,
            @Value("${chat-providers.inhouse-hi.temperature:0.4}")      double temperature,
            @Value("${chat-providers.inhouse-hi.num-predict:1024}")     int numPredict,
            @Value("${chat-providers.inhouse-hi.num-ctx:4096}")         int numCtx,
            @Value("${chat-providers.inhouse-hi.timeout-seconds:360}")  int timeoutSeconds,
            @Value("${chat-providers.inhouse-hi.prompt-suffix:}")       String promptSuffix) {
        this.factory        = factory;
        this.baseUrl        = baseUrl;
        this.modelName      = modelName;
        this.temperature    = temperature;
        this.numPredict     = numPredict;
        this.numCtx         = numCtx;
        this.timeoutSeconds = timeoutSeconds;
        this.promptSuffix   = promptSuffix == null ? "" : promptSuffix;
    }

    @Override public String id()          { return "inhouse-hi"; }

    // Derived from ollama.hi-chat-model rather than hardcoded. This provider is the
    // worst case for a literal label: the id says gemma, the default model is Qwen,
    // and the operator can point it at a third thing. Only the model is truthful.
    @Override public String label()       { return "In-house · " + ModelFamily.of(modelName, id()); }
    @Override public String description() {
        return modelName + " served from the high-memory Ollama container. "
             + "Compact and fast, runs entirely on the operator's hardware.";
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
