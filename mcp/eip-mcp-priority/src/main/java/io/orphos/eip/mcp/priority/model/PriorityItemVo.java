package io.orphos.eip.mcp.priority.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One ranked item in the Executive Priority Queue, mirroring priority-engine-service's
 * {@code PriorityItem} contract. {@code scoreBreakdown} and {@code attributes} stay open maps
 * because priority-engine passes ArcadeDB fields through them unmodified — pinning them to fixed
 * types here would silently drop entity fields the planner may need.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriorityItemVo(

        /** Source entity id (e.g. "inc-001"). */
        String id,

        /** Entity type: INCIDENT | ALERT | BUDGET_ITEM | DECISION | KEY_RESULT | PROJECT. */
        String type,

        String title,
        String severity,
        String status,
        String domain,

        /** Composite EPQ score 0-100 (higher = more urgent). */
        double epqScore,

        /** 1-based rank in the returned queue. */
        int rank,

        /** Individual dimension scores used to produce epqScore. */
        Map<String, Double> scoreBreakdown,

        /** Human-readable explanation of the top scoring factors. */
        String rationale,

        /** Original ArcadeDB fields passed through for UI rendering. */
        Map<String, Object> attributes) {
}
