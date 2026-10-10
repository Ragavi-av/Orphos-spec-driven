package io.orphos.eip.nlquery.component.provider;

import dev.langchain4j.model.ollama.OllamaChatModel;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds {@link OllamaChatModel} instances for the in-house providers using the
 * admin-editable tuning values (temperature, num-predict, num-ctx,
 * timeout-seconds) from {@link PromptConfigStore}.
 *
 * <p>Models are cached by a signature of their effective parameters, so:
 * <ul>
 *   <li>repeat calls with the same tuning reuse a single model (no churn), and</li>
 *   <li>an admin tuning change produces a new signature → a freshly-built model,
 *       i.e. the change takes effect without a service restart.</li>
 * </ul>
 * Building an {@code OllamaChatModel} is cheap (it only holds config + an HTTP
 * client), so the per-signature cache stays small and is never evicted.</p>
 */
@Component
public class OllamaModelFactory {

    private final PromptConfigStore prompts;
    private final ConcurrentHashMap<String, OllamaChatModel> cache = new ConcurrentHashMap<>();

    public OllamaModelFactory(PromptConfigStore prompts) {
        this.prompts = prompts;
    }

    /**
     * Returns a chat model for the given provider, applying the current tenant's
     * tuning (falling back to the supplied defaults when unset).
     */
    public OllamaChatModel get(String baseUrl, String modelName, String providerId,
                               double defTemperature, int defNumPredict, int defNumCtx, int defTimeoutSeconds) {
        UUID tenant = TenantContextHolder.current().map(c -> c.tenantId()).orElse(null);
        double temperature  = prompts.tuningDouble(tenant, providerId, "temperature",     defTemperature);
        int    numPredict   = prompts.tuningInt(tenant, providerId, "num-predict",      defNumPredict);
        int    numCtx       = prompts.tuningInt(tenant, providerId, "num-ctx",          defNumCtx);
        int    timeoutSecs  = prompts.tuningInt(tenant, providerId, "timeout-seconds",  defTimeoutSeconds);

        String signature = baseUrl + '|' + modelName + '|' + temperature + '|'
                + numPredict + '|' + numCtx + '|' + timeoutSecs;

        return cache.computeIfAbsent(signature, k -> OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .numPredict(numPredict)
                .numCtx(numCtx)
                .timeout(Duration.ofSeconds(timeoutSecs))
                .build());
    }
}
