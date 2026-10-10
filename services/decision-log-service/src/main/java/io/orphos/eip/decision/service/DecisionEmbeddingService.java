package io.orphos.eip.decision.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.resilience4j.retry.Retry;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.decision.util.DecisionLogProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Embeds decisions into the {@code decision_log_decisions} Qdrant collection
 * (bge-m3, 1024-dim) and runs tenant-isolated semantic retrieval over them
 * (Req 6.3, Req 4.3 similar-decision lookup, Req 7.3 related suggestions).
 *
 * <p>All operations are best-effort: if Ollama or Qdrant is down the caller
 * falls back to keyword-only behavior instead of failing the request.</p>
 */
@Service
public class DecisionEmbeddingService {

    private static final Logger log = LogManager.getLogger(DecisionEmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DecisionLogProperties props;
    private final Retry embedRetry;

    public DecisionEmbeddingService(EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore,
                                    DecisionLogProperties props,
                                    EipRetries retries) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.props = props;
        // Retries only the embed call (stateless Ollama read), never embeddingStore.add/search —
        // see index()/search() below for why the write side stays outside the retry.
        this.embedRetry = retries.retry("decision-log-embed");
    }

    /** A semantic hit: the decision id plus its cosine relevance. */
    public record SemanticMatch(String decisionId, double score) {}

    /**
     * (Re-)indexes one decision. Returns the new Qdrant point id, or null when
     * the embedding stack is unavailable. {@code previousEmbeddingId} (if any)
     * is removed first so a decision never has two live vectors.
     */
    public String index(Map<String, Object> decision, String previousEmbeddingId) {
        String decisionId = str(decision.get("id"));
        String tenantId = str(decision.get("tenantId"));
        String text = embeddableText(decision);
        if (decisionId.isBlank() || tenantId.isBlank() || text.isBlank()) return null;
        try {
            remove(previousEmbeddingId);
            Map<String, String> meta = new HashMap<>();
            meta.put("tenant_id", tenantId);
            meta.put("decision_id", decisionId);
            meta.put("domain", str(decision.get("domain")));
            meta.put("priority", str(decision.get("priorityLevel")));
            meta.put("decided_at", str(decision.get("decidedAt")));
            TextSegment segment = TextSegment.from(text, Metadata.from(meta));
            Response<Embedding> resp = embedRetry.executeSupplier(() -> embeddingModel.embed(segment));
            // Not retried: embeddingStore.add(embedding, segment) assigns a fresh random point
            // id each call, so retrying after an ambiguous failure risks a duplicate vector
            // rather than safely reapplying the same write (same reasoning as doc-ingest-service).
            return embeddingStore.add(resp.content(), segment);
        } catch (Exception e) {
            log.warn("Decision {} could not be embedded (semantic search will miss it until re-index): {}",
                decisionId, e.getMessage());
            return null;
        }
    }

    /** Removes a decision's vector; tolerated no-op when unsupported or missing. */
    public void remove(String embeddingId) {
        if (embeddingId == null || embeddingId.isBlank()) return;
        try {
            embeddingStore.removeAll(List.of(embeddingId));
        } catch (Exception e) {
            log.debug("Could not remove embedding {}: {}", embeddingId, e.getMessage());
        }
    }

    /**
     * Semantic search across the tenant's decisions (Req 6.3). Over-fetches 4×
     * then filters by tenant, mirroring nl-query's retrieval pattern.
     */
    public List<SemanticMatch> search(String tenantId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        try {
            Response<Embedding> embResp = embedRetry.executeSupplier(() -> embeddingModel.embed(TextSegment.from(query)));
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(embResp.content())
                .maxResults(Math.max(topK, 1) * 4)
                .minScore(props.getSearch().getSemanticMinScore())
                .build();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(req);
            List<SemanticMatch> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : result.matches()) {
                if (!tenantId.equals(m.embedded().metadata().getString("tenant_id"))) continue;
                String decisionId = m.embedded().metadata().getString("decision_id");
                if (decisionId == null || decisionId.isBlank()) continue;
                if (out.stream().anyMatch(x -> Objects.equals(x.decisionId(), decisionId))) continue;
                out.add(new SemanticMatch(decisionId, m.score()));
                if (out.size() >= topK) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("Semantic search unavailable, falling back to keyword-only: {}", e.getMessage());
            return List.of();
        }
    }

    /** Similar historical decisions for impact comparison / related suggestions, excluding the decision itself. */
    public List<SemanticMatch> findSimilar(String tenantId, Map<String, Object> decision, int topK) {
        String selfId = str(decision.get("id"));
        List<SemanticMatch> hits = search(tenantId, embeddableText(decision), topK + 1);
        return hits.stream().filter(h -> !h.decisionId().equals(selfId)).limit(topK).toList();
    }

    /** The canonical text a decision embeds as — kept stable so re-index is deterministic. */
    public static String embeddableText(Map<String, Object> decision) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, decision.get("title"));
        appendPart(sb, decision.get("description"));
        appendPart(sb, decision.get("rationale"));
        appendPart(sb, decision.get("impactSummary"));
        appendPart(sb, decision.get("domain"));
        appendPart(sb, decision.get("decisionArea"));
        return sb.toString().strip();
    }

    private static void appendPart(StringBuilder sb, Object value) {
        String s = str(value);
        if (!s.isBlank()) sb.append(s).append('\n');
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
