package io.orphos.eip.docingest.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires bge-m3 (Ollama, multilingual, 1024-dim) as the embedding model and
 * Qdrant as the vector store, matching the infrastructure already running for
 * nl-query. bge-m3 replaces nomic-embed-text so Japanese and English documents
 * embed into one mutually-retrievable space (bilingual deployment).
 *
 * Embeddings target the hi-memory Ollama container (embed-url) so the primary
 * container never has to hold a chat model and the embedding model at once.
 *
 * Collection "doc_ingest_documents" uses a tenant_id payload field for
 * row-level multi-tenant isolation (consistent with Orphos shared-DB model).
 * The collection is created on first write if it does not exist.
 */
@Configuration
public class EmbeddingConfig {

    // Embeddings run on the hi-memory container by default (see application.yml);
    // falls back to the primary ollama.url if embed-url is unset.
    @Value("${ollama.embed-url:${ollama.url:http://eip-ollama:11434}}")
    private String ollamaUrl;

    @Value("${ollama.embed-model:bge-m3}")
    private String embedModel;

    @Value("${qdrant.host:eip-qdrant}")
    private String qdrantHost;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Value("${doc-ingest.qdrant.collection:doc_ingest_documents}")
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
