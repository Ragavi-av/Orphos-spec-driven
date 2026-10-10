package io.orphos.eip.nlquery.component.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.resilience.EipResilienceDecorators;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.nlquery.util.ModelFamily;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OllamaChatProvider implements ChatProvider {

    private final OllamaModelFactory factory;
    private final String baseUrl;
    private final String modelName;
    private final String promptSuffix;
    private final double temperature;
    private final int numPredict;
    private final int numCtx;
    private final int timeoutSeconds;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public OllamaChatProvider(
            OllamaModelFactory factory,
            @Value("${ollama.base-url:http://eip-ollama:11434}")        String baseUrl,
            @Value("${ollama.chat-model:gemma3:4b}")                    String modelName,
            @Value("${chat-providers.inhouse.prompt-suffix:}")          String promptSuffix,
            @Value("${chat-providers.inhouse.temperature:0.3}")         double temperature,
            @Value("${chat-providers.inhouse.num-predict:1024}")        int numPredict,
            @Value("${chat-providers.inhouse.num-ctx:4096}")            int numCtx,
            @Value("${chat-providers.inhouse.timeout-seconds:360}")     int timeoutSeconds,
            EipCircuitBreakers circuitBreakers,
            EipRetries retries) {
        this.factory        = factory;
        this.baseUrl        = baseUrl;
        this.modelName      = modelName;
        this.promptSuffix   = promptSuffix == null ? "" : promptSuffix;
        this.temperature    = temperature;
        this.numPredict     = numPredict;
        this.numCtx         = numCtx;
        this.timeoutSeconds = timeoutSeconds;
        this.breaker = circuitBreakers.breaker("inhouse");
        // Only 1 retry: this provider's own timeout already runs up to 360s per attempt.
        this.retry = retries.retry("inhouse", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(500)))
                .build());
    }

    @Override public String id()          { return ChatProviderRegistry.DEFAULT_PROVIDER_ID; }

    // Label and description are derived from the model this provider is actually
    // pointed at (OLLAMA_CHAT_MODEL), never hardcoded. The id is a fixed routing
    // key while the model is operator-configurable, so a literal "Gemma 3" here
    // misreported every deployment that overrode the default — the admin table
    // would show Granite running under a row calling itself Gemma.
    @Override public String label()       { return "In-house · " + ModelFamily.of(modelName, id()); }
    @Override public String description() {
        return modelName + " served from the bundled Ollama container. "
             + "Default single-model — runs entirely on the operator's hardware, no external calls.";
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
        // isAvailable() deliberately stays unconditionally true (see above) — this provider is
        // the fallback of last resort, so a breaker-open state here can't route anywhere else;
        // it still gets a breaker + retry around the call itself for its own resilience (fail
        // fast on repeated failures, one retry on a transient blip) rather than routing value.
        return EipResilienceDecorators.call(
                () -> factory.get(baseUrl, modelName, id(), temperature, numPredict, numCtx, timeoutSeconds)
                        .chat(prompt),
                null, breaker, retry);
    }
}
