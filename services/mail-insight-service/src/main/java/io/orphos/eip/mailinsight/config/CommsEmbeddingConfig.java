package io.orphos.eip.mailinsight.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires bge-m3 (Ollama, multilingual, 1024-dim) + Qdrant for communication
 * embeddings — the same embedding space nl-query ({@code eip_context}),
 * doc-ingest ({@code doc_ingest_documents}) and decision-log
 * ({@code decision_log_decisions}) use, so the copilot's federated retrieval can
 * search communications alongside every other corpus.
 *
 * <p>Collection {@code mail_insight_comms} carries a {@code tenant_id} payload
 * field for row-level multi-tenant isolation, filtered at search time.</p>
 */
@Configuration
public class CommsEmbeddingConfig {

    @Value("${ollama.embed-url:${ollama.url:http://eip-ollama-hi:11434}}")
    private String ollamaUrl;

    @Value("${ollama.embed-model:bge-m3}")
    private String embedModel;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Value("${mail-insight.qdrant.collection:mail_insight_comms}")
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
    public EmbeddingStore<TextSegment> commsEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantGrpcPort)
                .collectionName(collectionName)
                .build();
    }
}
