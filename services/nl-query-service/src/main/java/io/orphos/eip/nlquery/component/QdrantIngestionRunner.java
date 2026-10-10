package io.orphos.eip.nlquery.component;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.orphos.eip.common.lang.LanguageDetector;
import io.orphos.eip.common.tenant.ActiveTenantsProvider;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.repository.ArcadeDbContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boot-time RAG ingestion into Qdrant. Walks the active-tenant list and
 * ingests each tenant's chunks under the SAME collection with a
 * {@code tenant_id} metadata field. Search-time filtering in
 * {@link io.orphos.eip.nlquery.service.NlQueryService} narrows results to
 * the calling tenant.
 *
 * <p>Pre-multi-tenant behaviour (drop-and-recreate the collection at every
 * boot) is preserved: stale chunks from previous runs cannot accumulate, but
 * the collection is now shared across tenants. The trade-off is that a
 * forgotten {@code tenant_id} filter at search time leaks data — that's why
 * {@link io.orphos.eip.nlquery.service.NlQueryService#retrieveFromQdrant}
 * threads the filter through explicitly.</p>
 *
 * <p>Best-effort: if admin-service is not yet reachable (cold-start order),
 * the runner logs and continues — searches will return empty results for
 * that tenant until the next boot. Acceptable for an MVP that's primarily
 * exercising the live-context path.</p>
 */
@Component
public class QdrantIngestionRunner implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(QdrantIngestionRunner.class);
    private static final String COLLECTION = "eip_context";
    // bge-m3 (multilingual) output dimension. Was 768 for nomic-embed-text;
    // the collection is dropped and recreated on every boot (see ensureCollection)
    // so this bump takes effect without a manual migration.
    private static final int VECTOR_SIZE = 1024;
    private static final String TENANT_PAYLOAD_KEY = "tenant_id";
    private static final String LANGUAGE_PAYLOAD_KEY = "language";
    // Provenance keys — read back at retrieval so an answer can report which
    // records informed it (see the trust layer's sources[]).
    private static final String SOURCE_TYPE_PAYLOAD_KEY = "source_type";
    private static final String SOURCE_ID_PAYLOAD_KEY = "source_id";

    private final EmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final ArcadeDbContextBuilder contextBuilder;
    private final ActiveTenantsProvider activeTenants;
    private final RestTemplate rest;

    @Value("${qdrant.host:qdrant}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantHttpPort;

    public QdrantIngestionRunner(EmbeddingModel embeddingModel,
                                 QdrantEmbeddingStore embeddingStore,
                                 ArcadeDbContextBuilder contextBuilder,
                                 ActiveTenantsProvider activeTenants,
                                 RestTemplate rest) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.contextBuilder = contextBuilder;
        this.activeTenants = activeTenants;
        this.rest = rest;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("Qdrant collection setup failed — RAG retrieval disabled: {}", e.getMessage());
            return;
        }

        Set<UUID> tenants;
        try {
            tenants = activeTenants.activeTenantIds();
        } catch (Exception e) {
            log.warn("Could not list active tenants from admin-service — RAG ingestion skipped: {}", e.getMessage());
            return;
        }
        if (tenants.isEmpty()) {
            log.warn("No active tenants reported by admin-service — RAG ingestion skipped this boot");
            return;
        }

        int totalIngested = 0;
        for (UUID tenantId : tenants) {
            try {
                int n = TenantContextHolder.callWith(
                        TenantContext.of(tenantId, null),
                        () -> ingestChunksForCurrentTenant(tenantId));
                totalIngested += n;
            } catch (Exception e) {
                log.warn("RAG ingestion failed for tenant {}: {} — continuing with other tenants",
                        tenantId, e.getMessage());
            }
        }
        log.info("Qdrant ingestion complete: {} chunks across {} tenants", totalIngested, tenants.size());
    }

    private int ingestChunksForCurrentTenant(UUID tenantId) {
        List<ArcadeDbContextBuilder.Chunk> chunks = contextBuilder.buildChunkRecords();
        if (chunks.isEmpty()) {
            log.debug("No chunks for tenant {}", tenantId);
            return 0;
        }

        List<TextSegment> segments = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();

        for (ArcadeDbContextBuilder.Chunk chunk : chunks) {
            try {
                // Embed once; segment metadata carries tenant_id (isolation), a
                // detected language tag (bilingual JA+EN), and provenance
                // (source_type + source_id) so the QdrantEmbeddingStore writes
                // them into the vector payload for read-back at retrieval.
                Metadata md = Metadata.from(TENANT_PAYLOAD_KEY, tenantId.toString());
                md.put(LANGUAGE_PAYLOAD_KEY, LanguageDetector.detect(chunk.text()));
                if (chunk.sourceType() != null) md.put(SOURCE_TYPE_PAYLOAD_KEY, chunk.sourceType());
                if (chunk.sourceId() != null) md.put(SOURCE_ID_PAYLOAD_KEY, chunk.sourceId());
                TextSegment segment = TextSegment.from(chunk.text(), md);
                Response<Embedding> resp = embeddingModel.embed(segment);
                segments.add(segment);
                embeddings.add(resp.content());
            } catch (Exception e) {
                log.warn("Failed to embed chunk for tenant {}: {}", tenantId, e.getMessage());
            }
        }

        if (embeddings.isEmpty()) return 0;
        embeddingStore.addAll(embeddings, segments);
        log.info("Tenant {} → ingested {} chunks into '{}'", tenantId, embeddings.size(), COLLECTION);
        return embeddings.size();
    }

    private void ensureCollection() {
        String url = "http://" + qdrantHost + ":" + qdrantHttpPort + "/collections/" + COLLECTION;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            rest.exchange(url, HttpMethod.DELETE, null, Map.class);
            log.info("Dropped existing Qdrant collection '{}' for clean re-ingestion", COLLECTION);
        } catch (Exception e) {
            log.debug("Collection '{}' did not exist yet, will create fresh", COLLECTION);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("vectors", Map.of("size", VECTOR_SIZE, "distance", "Cosine"));
        rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
        log.info("Created fresh Qdrant collection '{}'", COLLECTION);
    }
}
