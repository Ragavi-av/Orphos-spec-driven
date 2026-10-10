package io.orphos.eip.mailinsight.graph;

import java.time.Instant;

/**
 * A normalized signal pulled from Microsoft Graph (an email message or a calendar
 * event), reduced to just the fields the classifier needs. Raw bodies are kept
 * only transiently in {@code bodyPreview} for classification and are never
 * persisted or logged.
 */
public record GraphItem(
        String sourceRef,         // Graph message/event id
        String sourceType,        // EMAIL | EVENT | MEETING
        String subject,
        String bodyPreview,       // short preview text, classification input only
        String senderDisplay,     // display name only (no raw address)
        Instant receivedAt,
        boolean importanceHigh,   // Graph importance == "high"
        boolean directRecipient,  // addressed directly (not bulk/cc-only)
        Instant eventStart,       // calendar start, used as a due-date hint; null for mail
        MeetingInfo meeting       // extra calendar detail; null for mail and simple events
) {
    /**
     * Calendar detail for the mobile "Today's meeting" card. Attendees are display
     * names + response status only — no email addresses are carried or persisted.
     */
    public record MeetingInfo(
            Instant eventEnd,
            String attendeesJson,     // [{displayName,response}]
            int attendeeCount,
            String locationText,
            String onlineJoinUrl
    ) { }

    /** Backwards-compatible constructor for mail / simple items (no meeting detail). */
    public GraphItem(String sourceRef, String sourceType, String subject, String bodyPreview,
                     String senderDisplay, Instant receivedAt, boolean importanceHigh,
                     boolean directRecipient, Instant eventStart) {
        this(sourceRef, sourceType, subject, bodyPreview, senderDisplay, receivedAt,
                importanceHigh, directRecipient, eventStart, null);
    }

    public boolean isCalendar() {
        return "EVENT".equals(sourceType) || "MEETING".equals(sourceType);
    }
}
