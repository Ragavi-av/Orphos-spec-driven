package io.orphos.eip.decision.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.orphos.eip.decision.util.DecisionLogProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires bge-m3 (Ollama, multilingual, 1024-dim) + Qdrant for decision
 * embeddings — the same embedding space nl-query ({@code eip_context}) and
 * doc-ingest ({@code doc_ingest_documents}) already use, so semantic search
 * over decisions is mutually consistent with the rest of the platform and
 * works for Japanese and English content alike.
 *
 * Collection {@code decision_log_decisions} carries a {@code tenant_id}
 * payload field for row-level multi-tenant isolation (filtered at search
 * time, consistent with the Orphos shared-DB model).
 */
@Configuration
@EnableConfigurationProperties(DecisionLogProperties.class)
public class DecisionEmbeddingConfig {

    // Embeddings run on the hi-memory Ollama container by default, matching doc-ingest.
    @Value("${ollama.embed-url:${ollama.url:http://eip-ollama-hi:11434}}")
    private String ollamaUrl;

    @Value("${ollama.embed-model:bge-m3}")
    private String embedModel;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Value("${decision-log.qdrant.collection:decision_log_decisions}")
    private String collectionName;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
            .baseUrl(ollamaUrl)
            .modelName(embedModel)
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return QdrantEmbeddingStore.builder()
            .host(qdrantHost)
            .port(qdrantGrpcPort)
            .collectionName(collectionName)
            .build();
    }
}
