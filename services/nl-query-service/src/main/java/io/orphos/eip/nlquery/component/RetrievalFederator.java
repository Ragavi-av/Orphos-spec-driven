package io.orphos.eip.nlquery.component;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.orphos.eip.nlquery.config.CacheConfig;
import io.orphos.eip.nlquery.util.RetrievalProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Fans a single embedded query out across every configured Qdrant collection,
 * tenant-filters each collection's matches, then merges and re-ranks them by
 * score into one provenance-tagged context block for the briefing prompt.
 *
 * <p>Because all platform corpora share the same bge-m3 / 1024-dim embedding
 * space, one embedding call serves every collection — the fan-out is a handful
 * of cheap vector searches, no per-source re-embedding and no LLM tool-router.
 *
 * <p>Tenant isolation mirrors the original single-collection path: every match
 * is dropped unless its {@code tenant_id} payload equals the calling tenant.
 * Each collection is searched independently and a failure (including a
 * not-yet-created collection such as {@code mail_insight_comms} before the
 * Teams phase ships) is logged and skipped — never fatal to the query.
 */
@Component
public class RetrievalFederator {

    private static final Logger log = LogManager.getLogger(RetrievalFederator.class);

    private final EmbeddingModel embeddingModel;
    private final RetrievalProperties props;
    private final String qdrantHost;
    private final int qdrantGrpcPort;
    private final Cache embeddingCache;

    // One store per collection, built lazily and reused (QdrantEmbeddingStore is bound to a single collection).
    private final Map<String, QdrantEmbeddingStore> stores = new ConcurrentHashMap<>();

    public RetrievalFederator(EmbeddingModel embeddingModel,
                              RetrievalProperties props,
                              CacheManager cacheManager,
                              @Value("${qdrant.host:eip-qdrant}") String qdrantHost,
                              @Value("${qdrant.grpc-port:6334}") int qdrantGrpcPort) {
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.embeddingCache = cacheManager.getCache(CacheConfig.EMBEDDINGS);
        this.qdrantHost = qdrantHost;
        this.qdrantGrpcPort = qdrantGrpcPort;
    }

    /**
     * Embeds the query text, reusing a cached vector when the same text has been
     * embedded recently. Every miss is a network round trip to the hi-memory
     * Ollama, and the fixed briefing lead-ins mean the same normalised text
     * recurs constantly.
     *
     * <p>The key is the query text alone, deliberately not tenant-scoped: an
     * embedding is a pure function of text and the model, so there is nothing
     * tenant-specific to leak — a caller can only ever retrieve the vector for
     * text it already supplied.</p>
     */
    private Embedding embed(String queryText) {
        if (embeddingCache != null) {
            Cache.ValueWrapper hit = embeddingCache.get(queryText);
            if (hit != null && hit.get() instanceof Embedding cached) return cached;
        }
        Response<Embedding> embResp = embeddingModel.embed(TextSegment.from(queryText));
        Embedding embedding = embResp.content();
        if (embeddingCache != null && embedding != null) embeddingCache.put(queryText, embedding);
        return embedding;
    }

    private QdrantEmbeddingStore storeFor(String collection) {
        return stores.computeIfAbsent(collection, c -> QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantGrpcPort)
                .collectionName(c)
                .build());
    }

    /**
     * Retrieve context for a question. {@code tenantId} is the calling tenant's
     * UUID string; {@code lang} is the detected language ("ja"/"en") used only to
     * pick language-appropriate padding for very short queries.
     */
    public FederatedContext retrieve(String question, String tenantId, String lang) {
        String queryText = padShort(normalize(question), lang);

        final Embedding queryEmbedding;
        try {
            queryEmbedding = embed(queryText);
        } catch (Exception e) {
            log.warn("Federated retrieval: embedding failed for tenant {}, proceeding without vector context: {}",
                    tenantId, e.getMessage());
            return FederatedContext.empty();
        }

        List<RetrievalProperties.Source> active = props.isFederationEnabled()
                ? props.getSources()
                : props.getSources().stream()
                       .filter(s -> "eip_context".equals(s.getCollection()))
                       .toList();

        List<Snippet> all = new ArrayList<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();

        for (RetrievalProperties.Source src : active) {
            double minScore  = src.getMinScore()   != null ? src.getMinScore()   : props.getDefaultMinScore();
            int    maxResults = src.getMaxResults() != null ? src.getMaxResults() : props.getPerSourceMaxResults();
            try {
                EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        // Over-fetch so the tenant post-filter still leaves room for maxResults.
                        .maxResults(Math.max(1, maxResults) * 4)
                        .minScore(minScore)
                        .build();

                EmbeddingSearchResult<TextSegment> result = storeFor(src.getCollection()).search(req);

                List<EmbeddingMatch<TextSegment>> matches = result.matches().stream()
                        .filter(m -> tenantId.equals(m.embedded().metadata().getString("tenant_id")))
                        .filter(m -> isShared(m.embedded()))
                        .limit(maxResults)
                        .toList();

                for (EmbeddingMatch<TextSegment> m : matches) {
                    all.add(new Snippet(src.getLabel(), m.embedded(), m.score()));
                }
                if (!matches.isEmpty()) perSource.put(src.getLabel(), matches.size());
            } catch (Exception e) {
                // Missing collection or transient Qdrant error — never fatal to the query.
                log.debug("Federated retrieval: collection '{}' skipped ({})", src.getCollection(), e.getMessage());
            }
        }

        if (all.isEmpty()) {
            log.info("Federated retrieval: no chunks matched across {} source(s) for tenant {}",
                    active.size(), tenantId);
            return FederatedContext.empty();
        }

        all.sort(Comparator.comparingDouble(Snippet::score).reversed());
        List<Snippet> top = all.size() > props.getGlobalMaxResults()
                ? new ArrayList<>(all.subList(0, props.getGlobalMaxResults()))
                : all;

        String context = top.stream().map(RetrievalFederator::render).collect(Collectors.joining("\n---\n"));
        List<SourceRef> sources = top.stream().map(RetrievalFederator::toSourceRef).toList();
        return new FederatedContext(context, top.size(), top.get(0).score(), perSource, sources);
    }

    /**
     * False for a chunk that belongs to one user (doc-ingest stamps a mobile capture
     * with {@code owner_sub}). Retrieval here is tenant-scoped with no caller identity,
     * so a private note would otherwise surface in a colleague's Copilot answer.
     */
    static boolean isShared(TextSegment segment) {
        String owner = segment.metadata().getString("owner_sub");
        return owner == null || owner.isBlank();
    }

    /** Provenance descriptor for one retrieved snippet, read from its Qdrant payload. */
    private static SourceRef toSourceRef(Snippet s) {
        Metadata md = s.segment().metadata();
        String type = md == null ? null : md.getString("source_type");
        String id   = md == null ? null : md.getString("source_id");
        return new SourceRef(s.label(), type, id, s.score());
    }

    /** Prefix each snippet with its provenance label (and title, when present) so the model can attribute sources. */
    private static String render(Snippet s) {
        Metadata md = s.segment().metadata();
        String title = md == null ? null : md.getString("title");
        String header = "[" + s.label() + (title != null && !title.isBlank() ? ": " + title.strip() : "") + "]";
        return header + "\n" + s.segment().text();
    }

    /** Strip the fixed briefing lead-ins and quotes so the embedded query is the user's actual intent. */
    private static String normalize(String question) {
        return question
                .replaceAll("(?i)provide an executive briefing on (this priority item|this incident|this decision|this alert|this metric):\\s*", "")
                .replaceAll("(?i)summaris(e|z)e this executive (decision|incident|alert):\\s*", "")
                .replaceAll("(?i)(give me the executive analysis for|analyse the budget status for|explain this enterprise alert and its business impact:)\\s*", "")
                .replaceAll("[\"']", "")
                .trim();
    }

    /**
     * Short queries are embedded exactly as the user typed them.
     *
     * <p>This used to append generic domain terms ("enterprise status risk impact
     * action") to anything under 35 characters, on the theory that a terse query
     * needs more signal. It does the opposite. Six words of corporate boilerplate
     * dominate the pooled vector and the user's one distinguishing term stops
     * mattering — measured against bge-m3 on the document corpus, the padded
     * nonsense query "XYZZY …" scored 0.427 against a policy document while the
     * padded real query "POSH …" scored 0.411 on the same document. Similarity
     * had become a property of the padding, not the question, so any short query
     * cleared the relevance floor against any generic corporate text and the
     * caller was handed a confidently-wrong document.
     *
     * <p>A terse query that genuinely matches nothing must return nothing. The
     * lever for short-query recall is the score floor and keyword matching, never
     * injecting words the user did not write.
     */
    private static String padShort(String queryText, String lang) {
        return queryText;
    }

    // ── Records ──────────────────────────────────────────────────────────────

    private record Snippet(String label, TextSegment segment, double score) {}

    /**
     * Provenance descriptor for one snippet that fed the answer.
     *
     * @param label source label (e.g. "Enterprise Context", "Decisions")
     * @param type  originating record type (Incident, Decision, …) or null for aggregates
     * @param id    originating record id, or null for aggregate/summary chunks
     * @param score match relevance (cosine)
     */
    public record SourceRef(String label, String type, String id, double score) {}

    /**
     * Merged, provenance-tagged retrieval context.
     *
     * @param context     newline-delimited snippets, each prefixed with its source label
     * @param chunkCount  number of snippets included after the global cap
     * @param bestScore   highest match score across all sources
     * @param perSource   snippet count kept per provenance label (for logging/telemetry)
     * @param sources     per-snippet provenance descriptors (for the answer's trust signal)
     */
    public record FederatedContext(String context, int chunkCount, double bestScore,
                                   Map<String, Integer> perSource, List<SourceRef> sources) {
        public boolean hasContext() { return context != null && !context.isBlank(); }
        public static FederatedContext empty() { return new FederatedContext("", 0, 0.0, Map.of(), List.of()); }
    }
}
