package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.component.provider.ChatProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Loads the chat and embedding models into their Ollama containers as soon as the
 * service is up, so the first real question does not pay a cold model load
 * (several seconds to tens of seconds for a 2B model on CPU).
 *
 * <p>Uses Ollama's own load path — {@code POST /api/generate} with an empty
 * prompt loads the model and returns without generating — rather than going
 * through {@link io.orphos.eip.nlquery.component.provider.ChatProvider}, which needs a tenant
 * context this early-startup thread does not have. {@code keep_alive} is sent
 * explicitly so the model is pinned even if the container's default differs.</p>
 *
 * <p>Entirely best-effort: any failure is logged at INFO and ignored. A missing
 * or still-starting Ollama must never stop this service from coming up.</p>
 */
@Component
public class ModelWarmup {

    private static final Logger log = LogManager.getLogger(ModelWarmup.class);

    private final boolean enabled;
    private final String chatBaseUrl;
    private final String chatModel;
    private final String embedBaseUrl;
    private final String embedModel;
    private final String keepAlive;
    private final ExecutorService executor;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ModelWarmup(
            @Value("${nlquery.warmup.enabled:true}")             boolean enabled,
            @Value("${ollama.base-url:http://eip-ollama:11434}") String chatBaseUrl,
            @Value("${ollama.chat-model:gemma3:4b}")             String chatModel,
            @Value("${ollama.embed-url:${ollama.base-url:}}")    String embedBaseUrl,
            @Value("${ollama.embed-model:bge-m3}")               String embedModel,
            // Matches the containers' OLLAMA_KEEP_ALIVE default; "-1" pins the
            // model for the process lifetime.
            @Value("${nlquery.warmup.keep-alive:-1}")            String keepAlive,
            @Qualifier("nlQueryPipelineExecutor") ExecutorService executor) {
        this.executor     = executor;
        this.enabled      = enabled;
        this.chatBaseUrl  = chatBaseUrl;
        this.chatModel    = chatModel;
        this.embedBaseUrl = embedBaseUrl;
        this.embedModel   = embedModel;
        this.keepAlive    = keepAlive;
    }

    /**
     * Runs off the startup thread: a first-ever load reads GBs of weights off
     * disk and must never hold up the service becoming ready. Submitted to the
     * shared pipeline executor rather than {@code @Async}, which would need a
     * {@code TaskExecutor} bean this service does not define.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) {
            log.debug("Model warm-up disabled");
            return;
        }
        executor.submit(() -> {
            loadChat(chatBaseUrl, chatModel);
            if (embedBaseUrl != null && !embedBaseUrl.isBlank()) {
                loadEmbedding(embedBaseUrl, embedModel);
            }
        });
    }

    /**
     * An empty prompt makes Ollama load the chat model into memory and return
     * immediately without running inference.
     */
    private void loadChat(String baseUrl, String model) {
        post(baseUrl, "/api/generate", model, "chat",
                "{\"model\":\"" + model + "\",\"prompt\":\"\",\"keep_alive\":" + keepAlive + "}");
    }

    /**
     * Embedding models reject {@code /api/generate} (HTTP 400) — they have no
     * generate capability — so they are warmed through the embed endpoint with a
     * single trivial input.
     */
    private void loadEmbedding(String baseUrl, String model) {
        post(baseUrl, "/api/embed", model, "embedding",
                "{\"model\":\"" + model + "\",\"input\":\"warmup\",\"keep_alive\":" + keepAlive + "}");
    }

    private void post(String baseUrl, String path, String model, String kind, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(baseUrl) + path))
                    .header("Content-Type", "application/json")
                    // Generous: a first-ever load reads GBs of weights off disk.
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            long startNs = System.nanoTime();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            if (resp.statusCode() / 100 == 2) {
                log.info("Warmed {} model '{}' on {} in {} ms (keep_alive={})",
                        kind, model, baseUrl, ms, keepAlive);
            } else {
                log.info("Warm-up of {} model '{}' returned HTTP {} — first question may be slower",
                        kind, model, resp.statusCode());
            }
        } catch (Exception e) {
            log.info("Warm-up of {} model '{}' on {} skipped ({}) — first question may be slower",
                    kind, model, baseUrl, e.getMessage());
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
