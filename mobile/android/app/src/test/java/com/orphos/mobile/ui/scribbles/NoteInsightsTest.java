package com.orphos.mobile.ui.scribbles;

import static org.junit.Assert.assertEquals;

import com.orphos.mobile.data.model.Capture;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The Scribbles insight tiles count this week's notes, their actions, decisions and AI points. */
public class NoteInsightsTest {

    private static Capture note(long createdAt, String content, String... keyPoints) {
        Capture c = new Capture("id", "t", Capture.TYPE_TEXT, "label");
        c.createdAtMillis = createdAt;
        c.content = content;
        c.keyPoints = keyPoints.length == 0 ? null : Arrays.asList(keyPoints);
        return c;
    }

    @Test
    public void countsActionsDecisionsAndAiPoints() {
        Capture c = note(0, null,
                "Send the revised deck to finance",      // action
                "Decide whether to extend the APAC pilot", // decision
                "Budget needs CFO approval",             // decision
                "Revenue grew 12% in Q2");               // neither
        NoteInsights s = NoteInsights.of(Collections.singletonList(c));
        assertEquals(1, s.notes);
        assertEquals(1, s.actions);
        assertEquals(2, s.decisions);
        assertEquals(4, s.insights);
    }

    @Test
    public void aiListsWinOverKeywordReading() {
        Capture c = note(0, null, "Send the revised deck to finance", "Decide on the vendor");
        c.actions = Collections.emptyList();          // the AI found no actions
        c.decisions = Arrays.asList("Choose a vendor", "Approve the budget");
        NoteInsights s = NoteInsights.of(Collections.singletonList(c));
        assertEquals(0, s.actions);
        assertEquals(2, s.decisions);
        assertEquals(2, s.insights);
    }

    @Test
    public void noteWithoutAiPointsIsReadFromItsText() {
        Capture c = note(0, "Call the supplier tomorrow. Should we go with vendor A or not? Lunch was good.");
        NoteInsights s = NoteInsights.of(Collections.singletonList(c));
        assertEquals(1, s.notes);
        assertEquals(1, s.actions);
        assertEquals(1, s.decisions);
        assertEquals(0, s.insights);
    }

    @Test
    public void notesFromBeforeThisWeekAreLeftOut() {
        long old = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        NoteInsights s = NoteInsights.of(Arrays.asList(
                note(old, null, "Send the report"),
                note(System.currentTimeMillis(), null, "Send the report")));
        assertEquals(1, s.notes);
        assertEquals(1, s.actions);
    }

    @Test
    public void emptyListIsAllZero() {
        NoteInsights s = NoteInsights.of(null);
        assertEquals(0, s.notes + s.actions + s.decisions + s.insights);
    }
}
