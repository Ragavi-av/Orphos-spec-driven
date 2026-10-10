package io.orphos.eip.decision.component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Ensures the Qdrant collection for decision embeddings exists at startup —
 * QdrantEmbeddingStore (langchain4j) does not auto-create collections. Same
 * approach as doc-ingest's QdrantCollectionInitializer: GET the collection on
 * the REST port (6333) and PUT it (1024-dim / Cosine, matching bge-m3) when
 * missing. Best-effort: a Qdrant outage must not block boot — semantic search
 * degrades and keyword search keeps working.
 */
@Component
@Order(2)
public class QdrantDecisionCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(QdrantDecisionCollectionInitializer.class);

    /** bge-m3 output dimension. */
    private static final int VECTOR_DIM = 1024;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantHttpPort;

    @Value("${decision-log.qdrant.collection:decision_log_decisions}")
    private String collectionName;

    @Autowired
    private RestTemplate rest;

    @Override
    public void run(ApplicationArguments args) {
        String url = "http://" + qdrantHost + ":" + qdrantHttpPort + "/collections/" + collectionName;
        try {
            ResponseEntity<Map> check = rest.getForEntity(url, Map.class);
            if (check.getStatusCode() == HttpStatus.OK) {
                log.info("Qdrant collection '{}' already exists — skipping creation.", collectionName);
                return;
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Qdrant collection '{}' not found — creating with dim={}, distance=Cosine.",
                collectionName, VECTOR_DIM);
        } catch (Exception e) {
            log.warn("Could not check Qdrant collection '{}': {} — will attempt creation anyway.",
                collectionName, e.getMessage());
        }
        createCollection(url);
    }

    private void createCollection(String url) {
        Map<String, Object> body = Map.of("vectors", Map.of("size", VECTOR_DIM, "distance", "Cosine"));
        try {
            rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
            log.info("Qdrant collection '{}' created (dim={}, Cosine).", collectionName, VECTOR_DIM);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                log.info("Qdrant collection '{}' already exists (concurrent creation — OK).", collectionName);
            } else {
                log.error("Failed to create Qdrant collection '{}': {} — {}",
                    collectionName, e.getStatusCode(), e.getResponseBodyAsString());
            }
        } catch (Exception e) {
            log.error("Unexpected error creating Qdrant collection '{}': {}", collectionName, e.getMessage());
        }
    }
}
