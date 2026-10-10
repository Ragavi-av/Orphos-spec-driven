package io.orphos.eip.mailinsight.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Ensures the Qdrant collection for communication embeddings exists at startup
 * (langchain4j's QdrantEmbeddingStore does not auto-create collections). Mirrors
 * doc-ingest's {@code QdrantCollectionInitializer}: GET to probe, PUT to create
 * at 1024-dim / Cosine to match bge-m3.
 */
@Component
public class CommsCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(CommsCollectionInitializer.class);

    /** bge-m3 output dimension. */
    private static final int VECTOR_DIM = 1024;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantHttpPort;

    @Value("${mail-insight.qdrant.collection:mail_insight_comms}")
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
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Qdrant collection '{}' not found — creating with dim={}, distance=Cosine.",
                    collectionName, VECTOR_DIM);
            createCollection(url);
        } catch (Exception e) {
            log.warn("Could not check Qdrant collection '{}': {} — attempting creation anyway.",
                    collectionName, e.getMessage());
            createCollection(url);
        }
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
            log.error("Unexpected error creating Qdrant collection '{}': {}", collectionName, e.getMessage(), e);
        }
    }
}
