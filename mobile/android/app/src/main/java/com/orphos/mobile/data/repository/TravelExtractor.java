package com.orphos.mobile.data.repository;

import com.orphos.mobile.data.model.MailInsightDto;
import com.orphos.mobile.data.model.TravelItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts travel from mail-insight items using keywords. For each mail whose subject or
 * summary mentions travel, it pulls out From, To, Travel Mode, Date and Purpose.
 *
 * <p>This is deliberately rule-based (no LLM): the mail-insight service already scores and
 * summarises mail; here we recognise the trip fields the mobile Travel card needs.
 */
public final class TravelExtractor {

    private TravelExtractor() { }

    /** Words that mark a mail as travel-related. */
    private static final Pattern TRAVEL = Pattern.compile(
            "\\b(travel|trip|flight|flights|itinerary|pnr|boarding|departure|depart(?:s|ing)?|" +
            "airline|airfare|train|rail|cab|taxi|journey|e-?ticket|check-?in|layover)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Mode keywords → normalised travel mode. */
    private static final String[][] MODES = {
            { "flight|airline|airfare|boarding|pnr|e-?ticket|airport|terminal", "Flight" },
            { "train|rail|railway", "Train" },
            { "cab|taxi", "Cab" },
            { "bus|coach", "Bus" },
            { "car|drive|self-?drive", "Car" },
    };

    // "from X to Y", "X to Y", "X → Y", "X - Y" (endpoints are 1-3 capitalised-ish words).
    private static final Pattern FROM_TO = Pattern.compile(
            "(?:from\\s+)?([A-Za-z][A-Za-z .]{1,24}?)\\s*(?:→|->|—|-|\\bto\\b)\\s*([A-Za-z][A-Za-z .]{1,24}?)" +
            "(?=[,.;:\\n]|\\s+on\\b|\\s+for\\b|\\s+at\\b|$)",
            Pattern.CASE_INSENSITIVE);

    // Dates like "15 Aug", "Aug 15", "15 August 2026", "15/08", "2026-08-15", plus time.
    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}" +
            "|\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?" +
            "|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*(?:\\s+\\d{4})?" +
            "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2}(?:,\\s*\\d{4})?)" +
            "(?:\\s*(?:at|,)?\\s*\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|AM|PM)?)?",
            Pattern.CASE_INSENSITIVE);

    // Purpose: "for <purpose>" up to a delimiter.
    private static final Pattern PURPOSE = Pattern.compile(
            "\\bfor\\s+([A-Za-z][A-Za-z0-9 .&'-]{3,50}?)(?=[,.;:\\n]|$)",
            Pattern.CASE_INSENSITIVE);

    /** Returns the travel items found across the given mail-insight rows (most recent first). */
    public static List<TravelItem> fromMail(List<MailInsightDto> mails) {
        List<TravelItem> out = new ArrayList<>();
        if (mails == null) return out;
        for (MailInsightDto m : mails) {
            String subject = m.title == null ? "" : m.title;
            String body = m.summary == null ? "" : m.summary;
            String text = (subject + ". " + body).trim();
            if (text.isEmpty() || !TRAVEL.matcher(text).find()) continue;

            TravelItem t = new TravelItem();
            t.sourceId = m.id;
            t.mode = mode(text);

            Matcher ft = FROM_TO.matcher(text);
            if (ft.find()) {
                t.from = clean(ft.group(1));
                t.to = clean(ft.group(2));
            }

            Matcher dt = DATE.matcher(text);
            if (dt.find()) t.date = clean(dt.group());
            if (t.date == null || t.date.isEmpty()) t.date = m.dueDate != null ? m.dueDate : m.receivedAt;

            Matcher pp = PURPOSE.matcher(body);
            if (pp.find()) t.purpose = clean(pp.group(1));
            if (t.purpose == null || t.purpose.isEmpty()) t.purpose = subject;

            out.add(t);
        }
        return out;
    }

    private static String mode(String text) {
        for (String[] pair : MODES) {
            if (Pattern.compile("\\b(" + pair[0] + ")\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return pair[1];
            }
        }
        return "Travel";
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim().replaceAll("\\s+", " ");
        // Drop trailing connectors/punctuation the endpoint regex may have caught.
        s = s.replaceAll("(?i)\\b(from|to|on|for|at)$", "").trim();
        return s.isEmpty() ? null : s;
    }
}
