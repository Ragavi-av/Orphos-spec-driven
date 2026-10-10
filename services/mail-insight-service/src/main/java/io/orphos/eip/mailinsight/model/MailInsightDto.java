package io.orphos.eip.mailinsight.model;

import io.orphos.eip.common.access.DataDomains;

import java.util.Map;

/**
 * API representation of a single inferred item. Deliberately curated — the raw
 * email body and sender address are never serialised; only the derived
 * {@code summary}, {@code title}, and display name leave the service.
 */
public record MailInsightDto(
        String id,
        String mailboxId,
        String kind,              // TASK | NOTIFICATION
        String sourceType,        // EMAIL | EVENT | MEETING
        String title,
        String summary,
        String senderDisplay,
        String priority,          // effective priority (override wins over inferred)
        String criticality,       // ALERT | WARNING | INFORMATION
        int score,
        String rationale,
        String dueDate,           // ISO-8601 or null
        String receivedAt,
        String status,            // OPEN | IN_PROGRESS | DONE | DISMISSED
        String reminderAt,        // ISO-8601 or null
        String inferenceSource,   // RULES | LLM | DATA
        String source,            // EMAIL | MS_TEAMS | SLACK | DATA — see SourceChannel
        MeetingDetail meeting,    // populated only for sourceType=MEETING/EVENT; else null
        String domain,            // business domain (DataDomains) — drives persona filtering
        boolean personal          // from the caller's own mailbox/calendar (ownerSub set)
) {

    /**
     * Calendar detail for a meeting item — backs the mobile "Today's meeting" card.
     * Display-name only in {@code attendeesJson}; no email addresses are serialised.
     */
    public record MeetingDetail(
            String startAt,           // ISO-8601
            String endAt,             // ISO-8601
            String attendeesJson,     // [{displayName,response}]
            int attendeeCount,
            String briefPointsJson,   // ["…","…"] AI brief bullets
            String locationText,
            String onlineJoinUrlRef,
            String organizerDisplay
    ) { }

    public static MailInsightDto fromRow(Map<String, Object> r) {
        String prioOverride = str(r, "userPriorityOverride");
        String effPriority = (prioOverride != null && !prioOverride.isBlank()) ? prioOverride : str(r, "priority");
        String critOverride = str(r, "userCriticalityOverride");
        String effCriticality = (critOverride != null && !critOverride.isBlank()) ? critOverride : str(r, "criticality");
        // Legacy rows written before the "source" column existed have no value
        // here — they were always mailbox-scanned, so EMAIL is the correct default.
        String source = str(r, "source");
        if (source == null || source.isBlank()) source = SourceChannel.EMAIL;
        // No stored domain column: classify from the item's own text on read, so
        // existing rows are covered without a backfill.
        String domain = DataDomains.of(str(r, "domain"), str(r, "title"),
                str(r, "summary"), str(r, "rationale"), str(r, "senderDisplay"), str(r, "organizerDisplay"));
        String ownerSub = str(r, "ownerSub");
        boolean personal = ownerSub != null && !ownerSub.isBlank();
        return new MailInsightDto(
                str(r, "id"),
                str(r, "mailboxId"),
                str(r, "kind"),
                str(r, "sourceType"),
                str(r, "title"),
                str(r, "summary"),
                str(r, "senderDisplay"),
                effPriority,
                effCriticality,
                toInt(r.get("score")),
                str(r, "rationale"),
                str(r, "dueDate"),
                str(r, "receivedAt"),
                str(r, "status"),
                str(r, "reminderAt"),
                str(r, "inferenceSource"),
                source,
                meetingFromRow(r),
                domain,
                personal);
    }

    /** Builds the meeting detail for MEETING/EVENT rows; null otherwise. */
    private static MeetingDetail meetingFromRow(Map<String, Object> r) {
        String type = str(r, "sourceType");
        if (!"MEETING".equalsIgnoreCase(type) && !"EVENT".equalsIgnoreCase(type)) {
            return null;
        }
        return new MeetingDetail(
                str(r, "meetingStartAt"),
                str(r, "meetingEndAt"),
                str(r, "attendeesJson"),
                toInt(r.get("attendeeCount")),
                str(r, "briefPointsJson"),
                str(r, "locationText"),
                str(r, "onlineJoinUrlRef"),
                str(r, "organizerDisplay"));
    }

    /** Returns a copy with the supplied non-blank fields applied (used to overlay
     *  per-user actions onto live data-source items). */
    public MailInsightDto withOverrides(String newStatus, String newPriority,
                                        String newCriticality, String newReminderAt) {
        return new MailInsightDto(
                id, mailboxId, kind, sourceType, title, summary, senderDisplay,
                blank(newPriority) ? priority : newPriority,
                blank(newCriticality) ? criticality : newCriticality,
                score, rationale, dueDate, receivedAt,
                blank(newStatus) ? status : newStatus,
                newReminderAt != null && !newReminderAt.isBlank() ? newReminderAt : reminderAt,
                inferenceSource, source, meeting, domain, personal);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }
}
