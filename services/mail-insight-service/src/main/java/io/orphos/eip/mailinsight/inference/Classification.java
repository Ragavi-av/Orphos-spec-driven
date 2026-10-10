package io.orphos.eip.mailinsight.inference;

import java.time.Instant;
import java.util.Map;

/**
 * The inference output for a single {@link io.orphos.eip.mailinsight.graph.GraphItem}.
 *
 * @param kind            TASK | NOTIFICATION
 * @param priority        CRITICAL | HIGH | MEDIUM | LOW (meaningful for TASKs)
 * @param criticality     ALERT | WARNING | INFORMATION (meaningful for NOTIFICATIONs)
 * @param score           0-100 composite urgency/importance score
 * @param scoreBreakdown  per-signal contributions, surfaced for explainability
 * @param rationale       short human-readable justification
 * @param dueDate         extracted/derived due date, or null
 * @param summary         derived one-line summary (never the raw body)
 * @param inferenceSource RULES | LLM
 */
public record Classification(
        String kind,
        String priority,
        String criticality,
        int score,
        Map<String, Integer> scoreBreakdown,
        String rationale,
        Instant dueDate,
        String summary,
        String inferenceSource
) {
    public static final String KIND_TASK = "TASK";
    public static final String KIND_NOTIFICATION = "NOTIFICATION";
}
