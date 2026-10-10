package io.orphos.eip.mailinsight.pipeline;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Embeds a normalised communication (email or Teams message) into the shared
 * bge-m3 / 1024-dim space and indexes it in Qdrant collection
 * {@code mail_insight_comms}, so the copilot's federated retrieval
 * ({@code RetrievalFederator} in nl-query-service) can answer from
 * communications. Each vector carries a {@code tenant_id} payload for the same
 * search-time isolation every other collection uses.
 *
 * <p>Best-effort by contract: callers invoke this only for newly-created items
 * and must not let a vector failure fail the scan.</p>
 */
@Component
public class CommsVectorIndexer {

    private static final Logger log = LogManager.getLogger(CommsVectorIndexer.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> commsEmbeddingStore;

    public CommsVectorIndexer(EmbeddingModel embeddingModel,
                              @Qualifier("commsEmbeddingStore") EmbeddingStore<TextSegment> commsEmbeddingStore) {
        this.embeddingModel = embeddingModel;
        this.commsEmbeddingStore = commsEmbeddingStore;
    }

    /**
     * Index one communication. {@code fields} is the persisted MailInsight field
     * map (title/summary/source/senderDisplay/…); {@code bodyPreview} is the
     * transient message text used to enrich the embedding.
     */
    public void index(String tenantId, Map<String, Object> fields, String bodyPreview) {
        try {
            String text = buildText(fields, bodyPreview);
            if (text.isBlank()) return;

            Metadata meta = new Metadata();
            meta.put("tenant_id", tenantId == null ? "" : tenantId);
            meta.put("title", str(fields, "title"));
            meta.put("source", str(fields, "source"));           // EMAIL | MS_TEAMS …
            meta.put("source_type", str(fields, "sourceType"));  // EMAIL | TEAMS_MESSAGE | MEETING …
            meta.put("received_at", str(fields, "receivedAt"));

            TextSegment segment = TextSegment.from(text, meta);
            Response<Embedding> resp = embeddingModel.embed(segment);
            commsEmbeddingStore.add(resp.content(), segment);
        } catch (Exception e) {
            log.debug("Comms vector index skipped for '{}': {}", str(fields, "sourceRef"), e.getMessage());
        }
    }

    /** Compose a compact, retrieval-friendly text from title + summary + sender + body preview. */
    private static String buildText(Map<String, Object> fields, String bodyPreview) {
        StringBuilder sb = new StringBuilder();
        append(sb, str(fields, "title"));
        append(sb, str(fields, "summary"));
        String sender = str(fields, "senderDisplay");
        if (!sender.isBlank()) append(sb, "From: " + sender);
        append(sb, bodyPreview);
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.strip());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
