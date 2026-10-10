package com.orphos.mobile.ui.scribbles;

import com.orphos.mobile.data.model.Capture;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The "AI Insights from your notes" tiles, derived from this week's captures:
 * <ul>
 *   <li>Notes — how many notes were captured;</li>
 *   <li>Actions — items in them that someone has to do;</li>
 *   <li>Decisions — items that need a decision or approval;</li>
 *   <li>Insights — the AI suggestions (key points) generated for them.</li>
 * </ul>
 * Actions and decisions are the AI's own lists (doc-ingest's capture summary) when the note
 * has them. Notes saved before that, or with AI off, fall back to a keyword reading of their
 * key points or, failing those, their sentences. A decision is never also an action.
 */
public final class NoteInsights {

    public final int notes;
    public final int actions;
    public final int decisions;
    public final int insights;

    /** One counted line, with the note it came from (so a tap can open that note). */
    public static final class Item {
        public final String text;
        public final Capture source;

        Item(String text, Capture source) {
            this.text = text.trim();
            this.source = source;
        }
    }

    /** What each tile counts, so a tap can list exactly those items. */
    public final List<Capture> noteList;
    public final List<Item> actionItems;
    public final List<Item> decisionItems;
    public final List<Item> insightItems;

    private NoteInsights(List<Capture> noteList, List<Item> actionItems,
                         List<Item> decisionItems, List<Item> insightItems) {
        this.noteList = noteList;
        this.actionItems = actionItems;
        this.decisionItems = decisionItems;
        this.insightItems = insightItems;
        this.notes = noteList.size();
        this.actions = actionItems.size();
        this.decisions = decisionItems.size();
        this.insights = insightItems.size();
    }

    private static final Pattern DECISION = Pattern.compile(
            "\\b(decide|decides|decided|deciding|decision|decisions|approve|approves|approval|"
            + "sign[- ]?off|agree on|choose|choice|pick between|go/no[- ]go|whether|or not)\\b"
            + "|\\bvs\\.?\\b|\\?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACTION = Pattern.compile(
            "\\b(need to|needs to|have to|has to|must|should|to do|todo|to-do|action|follow[- ]?up|"
            + "remind|deadline|due|by (today|tomorrow|monday|tuesday|wednesday|thursday|friday|"
            + "saturday|sunday|eod|end of|next week))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Imperative openers: "Send the deck", "Open two roles", "Schedule a call". */
    private static final Pattern IMPERATIVE = Pattern.compile(
            "^(please\\s+)?(send|call|email|mail|schedule|book|arrange|prepare|draft|write|review|"
            + "check|confirm|share|submit|update|finish|complete|fix|ship|launch|open|close|hire|"
            + "contact|meet|plan|set up|setup|organise|organize|follow|ask|get|buy|order|pay|"
            + "create|build|run|reuse|extend|add|remove|move|cancel|invite|notify|reply|respond|"
            + "prioritise|prioritize|start|stop|sign|file|collect|verify|test|deliver|co-market)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\r?\\n+");

    public static NoteInsights of(List<Capture> captures) {
        long weekStart = startOfWeekMillis();
        List<Capture> notes = new ArrayList<>();
        List<Item> actions = new ArrayList<>(), decisions = new ArrayList<>(), insights = new ArrayList<>();
        if (captures != null) {
            for (Capture c : captures) {
                if (c == null) continue;
                // Rows with no timestamp (demo, legacy labels) can't be dated; keep them.
                if (c.createdAtMillis > 0 && c.createdAtMillis < weekStart) continue;
                notes.add(c);
                boolean hasPoints = c.keyPoints != null && !c.keyPoints.isEmpty();
                if (hasPoints) addAll(insights, c.keyPoints, c);
                if (c.actions != null || c.decisions != null) {
                    // The AI classified this note: trust its lists, even when they are empty.
                    addAll(actions, c.actions, c);
                    addAll(decisions, c.decisions, c);
                    continue;
                }
                for (String item : hasPoints ? c.keyPoints : sentences(c.content)) {
                    if (item == null || item.isBlank()) continue;
                    if (isDecision(item)) decisions.add(new Item(item, c));
                    else if (isAction(item)) actions.add(new Item(item, c));
                }
            }
        }
        return new NoteInsights(notes, actions, decisions, insights);
    }

    private static void addAll(List<Item> out, List<String> items, Capture source) {
        if (items != null) for (String s : items) if (s != null && !s.isBlank()) out.add(new Item(s, source));
    }

    static boolean isDecision(String item) {
        return DECISION.matcher(item.trim()).find();
    }

    static boolean isAction(String item) {
        String t = item.trim().replaceFirst("^[-*•\\d.)\\s]+", "");
        return IMPERATIVE.matcher(t).find() || ACTION.matcher(t).find();
    }

    private static List<String> sentences(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;
        for (String s : SENTENCE_SPLIT.split(content.trim())) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** Midnight at the start of the current week, in the device's locale and zone. */
    private static long startOfWeekMillis() {
        ZoneId zone = ZoneId.systemDefault();
        DayOfWeek first = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
        LocalDate start = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(first));
        return start.atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
