package io.orphos.eip.nlquery.component.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live view of which models each Ollama runtime actually serves, via
 * {@code GET /api/tags}. This is the "real-time by build" half of provider
 * availability: an in-house provider may be part of the configured model mode
 * yet still be unusable because the container is down or the model was never
 * pulled.
 *
 * <p>Results are cached per base URL for a short TTL so the admin screen (which
 * asks once per provider row) triggers at most one probe per runtime, and an
 * unreachable runtime costs one connect timeout per TTL window rather than one
 * per row.</p>
 */
@Component
public class OllamaModelCatalog {

    private static final Logger log = LogManager.getLogger(OllamaModelCatalog.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private record Snapshot(Instant fetchedAt, boolean reachable, Set<String> models) {}

    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();

    /** True when the runtime answered the last (cached) probe. */
    public boolean isReachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        return snapshot(baseUrl).reachable();
    }

    /**
     * True when the runtime at {@code baseUrl} is reachable and currently
     * serves {@code modelName}. Names are compared with the implicit
     * {@code :latest} tag stripped, so "phi3.5" matches "phi3.5:latest".
     */
    public boolean isModelServed(String baseUrl, String modelName) {
        if (baseUrl == null || baseUrl.isBlank() || modelName == null || modelName.isBlank()) {
            return false;
        }
        Snapshot s = snapshot(baseUrl);
        return s.reachable() && s.models().contains(normalize(modelName));
    }

    private Snapshot snapshot(String baseUrl) {
        Snapshot cached = cache.get(baseUrl);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        Snapshot fresh = fetch(baseUrl);
        cache.put(baseUrl, fresh);
        return fresh;
    }

    private Snapshot fetch(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "") + "/api/tags";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Ollama tag probe {} returned HTTP {}", url, response.statusCode());
                return new Snapshot(Instant.now(), false, Set.of());
            }
            Set<String> models = new HashSet<>();
            JsonNode root = mapper.readTree(response.body());
            for (JsonNode model : root.path("models")) {
                String name = model.path("name").asText("");
                if (!name.isBlank()) models.add(normalize(name));
            }
            return new Snapshot(Instant.now(), true, models);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(Instant.now(), false, Set.of());
        } catch (Exception e) {
            log.debug("Ollama tag probe {} failed: {}", url, e.getMessage());
            return new Snapshot(Instant.now(), false, Set.of());
        }
    }

    private static String normalize(String name) {
        String n = name.trim().toLowerCase();
        return n.endsWith(":latest") ? n.substring(0, n.length() - ":latest".length()) : n;
    }
}
