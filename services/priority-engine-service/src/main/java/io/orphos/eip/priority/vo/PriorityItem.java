package io.orphos.eip.priority.vo;

import java.util.Map;

/**
 * A single ranked item in the Executive Priority Queue.
 * Aggregates all entity types (Incident, Alert, BudgetItem, Decision, KeyResult, Project)
 * into a unified, comparably scored representation.
 */
public record PriorityItem(

    /** Source entity id (e.g. "inc-001") */
    String id,

    /** Entity type: INCIDENT | ALERT | BUDGET_ITEM | DECISION | KEY_RESULT | PROJECT */
    String type,

    String title,
    String severity,
    String status,
    String domain,

    /** Composite EPQ score 0–100 (higher = more urgent CEO attention) */
    double epqScore,

    /** 1-based rank in the returned queue */
    int rank,

    /** Individual dimension scores used to produce epqScore */
    Map<String, Double> scoreBreakdown,

    /** Human-readable explanation of the top scoring factors */
    String rationale,

    /** Original ArcadeDB fields passed through for UI rendering */
    Map<String, Object> attributes

) implements Comparable<PriorityItem> {

    @Override
    public int compareTo(PriorityItem other) {
        // Higher score sorts first
        return Double.compare(other.epqScore(), this.epqScore());
    }
}
