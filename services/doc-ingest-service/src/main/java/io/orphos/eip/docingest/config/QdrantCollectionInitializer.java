package io.orphos.eip.docingest.config;

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
 * Ensures the Qdrant collection for document embeddings exists at startup.
 *
 * <p>QdrantEmbeddingStore (langchain4j) does not auto-create collections.
 * This runner calls the Qdrant REST API on port 6333:
 * <ul>
 *   <li>GET  /collections/{name}    — 200 = exists, 404 = must create</li>
 *   <li>PUT  /collections/{name}    — create with cosine / 1024-dim vectors</li>
 * </ul>
 *
 * <p>1024 dimensions matches {@code bge-m3} (the multilingual model used by
 * nl-query-service for the {@code eip_context} collection). If an existing
 * collection was created at the old 768-dim ({@code nomic-embed-text}) size,
 * it is dropped and recreated at the new size — a one-time destructive
 * migration that requires re-ingesting documents (see DocIngestionService
 * #reindexTenant).
 */
@Component
@Order(2)
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(QdrantCollectionInitializer.class);

    /** bge-m3 output dimension. */
    private static final int VECTOR_DIM = 1024;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantHttpPort;

    @Value("${doc-ingest.qdrant.collection:doc_ingest_documents}")
    private String collectionName;

    @Autowired
    private RestTemplate rest;

    @Override
    public void run(ApplicationArguments args) {
        String base = "http://" + qdrantHost + ":" + qdrantHttpPort;
        String url  = base + "/collections/" + collectionName;

        try {
            ResponseEntity<Map> check = rest.getForEntity(url, Map.class);
            if (check.getStatusCode() == HttpStatus.OK) {
                Integer existingDim = extractVectorSize(check.getBody());
                if (existingDim != null && existingDim != VECTOR_DIM) {
                    log.warn("Qdrant collection '{}' has dim={} but the embedding model now produces dim={} "
                            + "— dropping and recreating (existing vectors will be lost; re-ingest required).",
                        collectionName, existingDim, VECTOR_DIM);
                    recreateCollection(url);
                } else {
                    log.info("Qdrant collection '{}' already exists at dim={} — skipping creation.",
                        collectionName, existingDim != null ? existingDim : VECTOR_DIM);
                }
                return;
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Qdrant collection '{}' not found — creating with dim={}, distance=Cosine.",
                collectionName, VECTOR_DIM);
            createCollection(url);
        } catch (Exception e) {
            log.warn("Could not check Qdrant collection '{}': {} — will attempt creation anyway.",
                collectionName, e.getMessage());
            tryCreateCollection(url);
        }
    }

    /** Pulls result.config.params.vectors.size out of a Qdrant GET /collections/{name} body. */
    @SuppressWarnings("unchecked")
    private static Integer extractVectorSize(Map<String, Object> body) {
        try {
            Map<String, Object> result = (Map<String, Object>) body.get("result");
            Map<String, Object> config = (Map<String, Object>) result.get("config");
            Map<String, Object> params = (Map<String, Object>) config.get("params");
            Object vectors = params.get("vectors");
            // Unnamed vectors: { "size": N, "distance": "Cosine" }.
            if (vectors instanceof Map<?, ?> vm) {
                Object size = ((Map<String, Object>) vm).get("size");
                if (size instanceof Number n) return n.intValue();
            }
        } catch (Exception e) {
            log.debug("Could not read existing Qdrant vector size: {}", e.getMessage());
        }
        return null;
    }

    /** Drops the collection then creates it fresh at the current VECTOR_DIM. */
    private void recreateCollection(String url) {
        try {
            rest.exchange(url, HttpMethod.DELETE, null, Map.class);
        } catch (Exception e) {
            log.warn("Failed to drop Qdrant collection '{}' before recreate: {}", collectionName, e.getMessage());
        }
        createCollection(url);
    }

    private void createCollection(String url) {
        Map<String, Object> body = Map.of(
            "vectors", Map.of(
                "size",     VECTOR_DIM,
                "distance", "Cosine"
            )
        );
        try {
            rest.exchange(url, HttpMethod.PUT,
                new HttpEntity<>(body), Map.class);
            log.info("Qdrant collection '{}' created successfully (dim={}, Cosine).",
                collectionName, VECTOR_DIM);
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

    private void tryCreateCollection(String url) {
        try { createCollection(url); }
        catch (Exception e) { log.error("Collection creation attempt failed: {}", e.getMessage()); }
    }
}
