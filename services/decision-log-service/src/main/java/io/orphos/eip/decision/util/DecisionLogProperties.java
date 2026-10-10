package io.orphos.eip.decision.util;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the Decision Log RAG pipeline (see application.yml `decision-log.*`). */
@ConfigurationProperties(prefix = "decision-log")
public class DecisionLogProperties {

    private final Sync sync = new Sync();
    private final Detection detection = new Detection();
    private final Escalation escalation = new Escalation();
    private final Search search = new Search();

    public Sync getSync() { return sync; }
    public Detection getDetection() { return detection; }
    public Escalation getEscalation() { return escalation; }
    public Search getSearch() { return search; }

    /** RAG source synchronization sweep (Req 2.5: <= 5 min lag). */
    public static class Sync {
        private boolean enabled = true;
        private long intervalMs = 300_000;      // 5 min
        private long initialDelayMs = 90_000;
        private int batchSize = 200;            // max source items per source per sweep

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    /** AI decision detection (Req 3). */
    public static class Detection {
        private boolean llmEnabled = true;
        /** Candidates below this confidence are dropped, not flagged. */
        private double minConfidence = 0.5;

        public boolean isLlmEnabled() { return llmEnabled; }
        public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    }

    /** Overdue / at-risk implementation alerts (Req 10.5). */
    public static class Escalation {
        private boolean enabled = true;
        private long intervalMs = 300_000;
        private long initialDelayMs = 120_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    }

    /** Search & retrieval (Req 6). */
    public static class Search {
        private int maxResults = 50;
        private double semanticMinScore = 0.45;   // bge-m3-calibrated, same floor as nl-query

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public double getSemanticMinScore() { return semanticMinScore; }
        public void setSemanticMinScore(double semanticMinScore) { this.semanticMinScore = semanticMinScore; }
    }
}
