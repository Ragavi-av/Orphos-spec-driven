package com.orphos.mobile.data.model;

import java.util.List;

/** predictedImpact object embedded in a decision (ImpactAnalysisService). */
public class PredictedImpact {
    public String summary;
    public String overallRisk;   // HIGH | MEDIUM | LOW
    public Double confidence;    // 0.0–1.0
    public String method;        // LLM | HEURISTIC
    public String generatedAt;
    public List<Impact> impacts;

    public static class Impact {
        public String dimension; // PEOPLE | POLICY | OPERATIONS | FINANCIAL
        public String area;
        public String severity;  // HIGH | MEDIUM | LOW
        public Double likelihood;
        public String description;
    }
}
