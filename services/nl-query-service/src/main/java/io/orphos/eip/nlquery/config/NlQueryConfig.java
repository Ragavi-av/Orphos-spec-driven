package io.orphos.eip.nlquery.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import io.orphos.eip.nlquery.component.provider.OllamaModelFactory;
import io.orphos.eip.nlquery.util.RetrievalProperties;
import io.orphos.eip.nlquery.util.TrustProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
@EnableConfigurationProperties({RetrievalProperties.class, io.orphos.eip.nlquery.util.TrustProperties.class})
public class NlQueryConfig {

    // bge-m3 (multilingual, 1024-dim) — see application.yml. Must match the
    // model doc-ingest uses so both write into compatible Qdrant collections.
    @Value("${ollama.embed-model:bge-m3}")
    private String embedModelName;

    // Embeddings target the hi-memory Ollama container by default so the primary
    // container is never forced to keep the embedding model resident next to a
    // chat model (the combination that caused OOM kills). Falls back to base-url.
    @Value("${ollama.embed-url:${ollama.base-url:http://127.0.0.1:11434}}")
    private String embedUrl;

    @Value("${qdrant.host:qdrant}")
    private String qdrantHost;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(embedUrl)
                .modelName(embedModelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // NOTE: the in-house chat models are no longer built here. Each in-house
    // provider builds its OllamaChatModel on demand via OllamaModelFactory, which
    // reads admin-editable tuning (temperature / num-predict / num-ctx / timeout)
    // from PromptConfigStore and caches per tuning-signature — so tuning changes
    // take effect without a restart.

    /**
     * Executor for the parts of a single question that can overlap — today the
     * Qdrant fan-out, which runs while the caller thread builds the ArcadeDB live
     * context. Virtual threads because every task is pure I/O wait, and Spring
     * closes the executor on shutdown.
     *
     * <p>Only hand work to this that does not read request thread-locals (tenant,
     * roles, security context): they do not propagate to a virtual thread here.</p>
     */
    @Bean(destroyMethod = "close")
    public ExecutorService nlQueryPipelineExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public QdrantEmbeddingStore embeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantGrpcPort)
                .collectionName("eip_context")
                .build();
    }
}
