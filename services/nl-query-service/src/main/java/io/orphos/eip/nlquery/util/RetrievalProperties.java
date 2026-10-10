package io.orphos.eip.nlquery.util;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for federated retrieval — a single NL query fans out across
 * several Qdrant collections that all share the bge-m3 / 1024-dim embedding
 * space (nl-query's {@code eip_context}, doc-ingest's {@code doc_ingest_documents},
 * decision-log's {@code decision_log_decisions}, and mail-insight's
 * {@code mail_insight_comms}). Matches are tenant-filtered per collection, then
 * merged and re-ranked by score before being handed to the briefing prompt.
 *
 * <p>Defaults cover the four platform corpora out of the box; a collection that
 * does not exist yet (e.g. {@code mail_insight_comms} before the Teams phase is
 * deployed) is skipped gracefully at search time, so no configuration change is
 * needed to roll the feature out incrementally.
 */
@ConfigurationProperties(prefix = "nl-query.retrieval")
public class RetrievalProperties {

    /**
     * Master switch. When {@code false}, only the primary {@code eip_context}
     * collection is queried — byte-for-byte the pre-federation behaviour.
     */
    private boolean federationEnabled = true;

    /** Minimum cosine score a match must meet, unless a source overrides it. */
    private double defaultMinScore = 0.45;

    /** Per-collection cap on matches kept after tenant filtering, unless a source overrides it. */
    private int perSourceMaxResults = 8;

    /** Global cap on merged snippets fed into the prompt across all sources. */
    private int globalMaxResults = 12;

    /** Ordered list of collections to federate. */
    private List<Source> sources = new ArrayList<>(List.of(
            Source.of("eip_context",            "CONTEXT"),
            Source.of("doc_ingest_documents",   "DOCUMENT"),
            Source.of("decision_log_decisions", "DECISION"),
            Source.of("mail_insight_comms",     "COMMUNICATION")
    ));

    public boolean isFederationEnabled() { return federationEnabled; }
    public void setFederationEnabled(boolean federationEnabled) { this.federationEnabled = federationEnabled; }

    public double getDefaultMinScore() { return defaultMinScore; }
    public void setDefaultMinScore(double defaultMinScore) { this.defaultMinScore = defaultMinScore; }

    public int getPerSourceMaxResults() { return perSourceMaxResults; }
    public void setPerSourceMaxResults(int perSourceMaxResults) { this.perSourceMaxResults = perSourceMaxResults; }

    public int getGlobalMaxResults() { return globalMaxResults; }
    public void setGlobalMaxResults(int globalMaxResults) { this.globalMaxResults = globalMaxResults; }

    public List<Source> getSources() { return sources; }
    public void setSources(List<Source> sources) { this.sources = sources; }

    /** One federated collection. {@code minScore}/{@code maxResults} are optional per-source overrides. */
    public static class Source {
        private String collection;
        /** Short provenance label shown to the LLM, e.g. DOCUMENT / DECISION / COMMUNICATION / CONTEXT. */
        private String label;
        private Double minScore;
        private Integer maxResults;

        public static Source of(String collection, String label) {
            Source s = new Source();
            s.collection = collection;
            s.label = label;
            return s;
        }

        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Double getMinScore() { return minScore; }
        public void setMinScore(Double minScore) { this.minScore = minScore; }

        public Integer getMaxResults() { return maxResults; }
        public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
    }
}
