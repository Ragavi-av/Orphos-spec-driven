package com.orphos.mobile.data.model;

import java.util.List;

/**
 * AI analysis for a decision — GET /api/decisions/{id}/annotation (AiAnnotationService).
 * This powers the "See Analysis" screen.
 */
public class DecisionAnnotation {
    public String executiveSummary;
    public List<String> keyImpacts;
    public List<String> recommendations;
    public List<String> relatedConsiderations;
    public List<String> suggestedConnections;
    public List<Related> relatedDecisions;
    public String method;        // LLM | TEMPLATE
    public String audienceRole;  // admin | executive | user
    public String generatedAt;
    public Boolean cached;

    public static class Related {
        public String id;
        public String title;
        public String status;
        public String decidedAt;
    }
}
