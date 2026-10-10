package com.orphos.mobile.data.model;

import java.util.List;

/** A "Today's meeting" card: time, title, attendees and the AI brief bullet points. */
public class MeetingBrief {
    public String id;
    public String time;
    /** Raw ISO-8601 start, kept alongside the display clock so a countdown can be computed. */
    public String startAtIso;
    /** Raw ISO-8601 end, when the calendar entry carries one — decides when a meeting has finished. */
    public String endAtIso;
    /** Room or joining text, when the calendar entry carries one. */
    public String location;
    public String title;
    public int attendeeCount;
    public List<String> briefPoints;
    /** Attendee display names from the calendar entry (meeting.attendeesJson). */
    public List<String> attendees = new java.util.ArrayList<>();
    /** Organizer display name, when the calendar entry carries one. */
    public String organizer;
    /** Online join link reference, when the calendar entry carries one. */
    public String joinUrl;
    /** mail-insight priority (e.g. HIGH, CRITICAL), when set. */
    public String priority;

    public MeetingBrief() { }

    public MeetingBrief(String id, String time, String title, int attendeeCount, List<String> briefPoints) {
        this.id = id; this.time = time; this.title = title;
        this.attendeeCount = attendeeCount; this.briefPoints = briefPoints;
    }

    // --- Time rules, shared by Home and the Calendar so the two can never disagree ---

    /** Start as epoch ms, or -1 when absent or unparsable. */
    public long startMillis() { return epochMillis(startAtIso); }

    /** End as epoch ms, or -1 when absent or unparsable. */
    public long endMillis() { return epochMillis(endAtIso); }

    /** Not started yet — the web's nextUpcoming() test (start >= now). */
    public boolean isUpcoming(long now) {
        long start = startMillis();
        return start >= 0 && start >= now;
    }

    /**
     * Finished: its end has passed. Without an end, its start stands in (the web rail's
     * rule), so an entry with no duration is not left looking live all day.
     */
    public boolean hasEnded(long now) {
        long end = endMillis();
        long over = end >= 0 ? end : startMillis();
        return over >= 0 && over < now;
    }

    /**
     * The first meeting that has not started yet, or null once the day is done — the
     * mobile twin of meetings.js nextUpcoming(). Does not assume the list is sorted.
     */
    public static MeetingBrief nextUpcoming(List<MeetingBrief> meetings, long now) {
        MeetingBrief next = null;
        if (meetings == null) return null;
        for (MeetingBrief b : meetings) {
            if (b.isUpcoming(now) && (next == null || b.startMillis() < next.startMillis())) next = b;
        }
        return next;
    }

    /** Earliest first by the actual start instant; entries without one sink to the end. */
    public static final java.util.Comparator<MeetingBrief> BY_START =
            java.util.Comparator.comparingLong(b -> {
                long at = b.startMillis();
                return at < 0 ? Long.MAX_VALUE : at;
            });

    private static long epochMillis(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try {
            return java.time.Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception e) {
            // Offsets ("+05:30") as well as "Z" — the backend's own lenient parse.
            try {
                return java.time.OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                return -1;
            }
        }
    }
}
