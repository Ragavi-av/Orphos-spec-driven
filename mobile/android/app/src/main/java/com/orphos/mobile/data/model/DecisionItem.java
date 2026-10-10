package com.orphos.mobile.data.model;

import java.util.List;

/**
 * A decision from decision-log-service. Field names match the expanded object returned
 * by GET /api/decisions and GET /api/decisions/{id} (see DecisionRepository.DECISION_FIELDS
 * and DecisionService.expand()).
 */
public class DecisionItem {

    public String id;
    public String tenantId;
    public String title;
    public String status;               // PENDING | DETECTED | APPROVED | REJECTED | COMPLETED
    public String domain;
    public String rationale;
    public String description;
    public String decisionArea;         // NB: the list filter param is `area`, field is `decisionArea`
    public String priorityLevel;        // CRITICAL | HIGH | MEDIUM | LOW
    public Double priorityScore;
    public Double confidenceScore;      // 0.0–1.0
    public String strategicImportance;
    public String impactSummary;
    public String implementationStatus; // NOT_STARTED | IN_PROGRESS | COMPLETED
    public String dueDate;
    public String decidedBy;
    public String decidedAt;
    public Boolean escalated;
    public String createdAt;
    public String updatedAt;

    public PredictedImpact predictedImpact;
    public List<Object> milestones;
    public List<Object> affectedAreas;

    public DecisionItem() { }

    /** AI confidence as a whole percentage, preferring the impact model's confidence. */
    public int confidencePercent() {
        Double c = (predictedImpact != null && predictedImpact.confidence != null)
                ? predictedImpact.confidence
                : confidenceScore;
        if (c == null) return 0;
        return (int) Math.round(c * 100);
    }

    /** Risk from the predicted-impact model (HIGH/MEDIUM/LOW). */
    public String risk() {
        return predictedImpact != null ? predictedImpact.overallRisk : null;
    }

    /** Convenience for the card: a short impact label. */
    public String impactLabel() {
        if (strategicImportance != null && !strategicImportance.isEmpty()) return strategicImportance;
        if (impactSummary != null && !impactSummary.isEmpty()) return impactSummary;
        return "—";
    }
}
