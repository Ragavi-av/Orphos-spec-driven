package io.orphos.eip.mcp.mail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One mail-derived task or notification, mirroring mail-insight-service's {@code MailInsightDto}
 * contract. That DTO is already curated upstream — the raw email body and sender address are never
 * serialised, only the derived {@code summary}, {@code title} and display name — so this VO carries
 * no content that mail-insight has not deliberately published.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MailItemVo(

        String id,
        String mailboxId,

        /** TASK | NOTIFICATION. */
        String kind,

        /** EMAIL | EVENT | MEETING. */
        String sourceType,

        String title,
        String summary,
        String senderDisplay,

        /** Effective priority (a user override wins over the inferred value). */
        String priority,

        /** ALERT | WARNING | INFORMATION. */
        String criticality,

        int score,
        String rationale,

        /** ISO-8601 or null. */
        String dueDate,

        String receivedAt,

        /** OPEN | IN_PROGRESS | DONE | DISMISSED. */
        String status,

        /** ISO-8601 or null. */
        String reminderAt,

        /** RULES | LLM | DATA. */
        String inferenceSource,

        /** EMAIL | MS_TEAMS | SLACK | DATA. */
        String source) {
}
