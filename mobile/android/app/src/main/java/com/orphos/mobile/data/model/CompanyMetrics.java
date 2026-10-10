package com.orphos.mobile.data.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CEO "Metrics" screen: company-level outcomes (revenue run-rate, runway, KPIs, OKRs,
 * decisions awaiting the CEO) plus the outcome-only views of the Growth, Strategy and People
 * functions and the quick-tile sheets. Colours are carried as a {@link Tone} so the screen
 * maps them onto the design system rather than the data carrying hex values.
 */
public class CompanyMetrics {

    /** Semantic colour of a figure: good / warning / bad / neutral-info / AI. */
    public enum Tone { GOOD, WARN, BAD, INFO, AI }

    // --- Overview hero: revenue run-rate against the fiscal-year target ---
    public String heroLabel;
    public String heroUpdated;
    public String heroValue;
    public String heroTarget;          // e.g. "₹20 Cr"
    public String heroTargetSuffix;    // e.g. "FY27 target"
    public int heroAchievedPct;
    public int heroExpectedPct;
    public String heroExpectedNote;    // e.g. "75% expected by Sep"
    /** Sparkline points (oldest first), any scale; drawn normalised. */
    public List<Float> heroTrend = new ArrayList<>();
    public String runwayValue;
    public String runwayNote;
    /** What the hero figure means, shown when the card is tapped; null = not tappable. */
    public Definition heroDefinition;

    // --- AI cross-functional alert (null title = none) ---
    public String alertKicker;
    public String alertBadge;
    public String alertTitle;
    public String alertBody;
    public List<String> alertTags = new ArrayList<>();

    public List<Kpi> kpis = new ArrayList<>();

    public String okrPeriod;
    /** Where "expected progress today" sits on every OKR bar, 0-100. */
    public int okrExpectedPct;
    public List<Okr> okrs = new ArrayList<>();

    public List<Decision> decisions = new ArrayList<>();
    /** Card title for {@link #decisions}; null = "Decisions waiting for you". */
    public String decisionsTitle;
    /** False when {@link #decisions} are recent outcomes rather than items awaiting the CEO. */
    public boolean decisionsPending = true;

    /** Section tab label -> its outcome-only rows (Overview is built from the fields above). */
    public Map<String, Section> sections = new LinkedHashMap<>();

    /** Quick-tile label -> its sheet. Tile order follows insertion order. */
    public Map<String, Sheet> sheets = new LinkedHashMap<>();

    public boolean isEmpty() {
        return heroValue == null && kpis.isEmpty() && okrs.isEmpty() && decisions.isEmpty()
                && sections.isEmpty();
    }

    public static class Kpi {
        public String label, value, delta;
        public Tone tone;
        public Definition definition;
        public Kpi(String label, String value, String delta, Tone tone) {
            this.label = label; this.value = value; this.delta = delta; this.tone = tone;
        }
    }

    public static class Okr {
        public String title, owner;
        public int pct;
        public Okr(String title, String owner, int pct) {
            this.title = title; this.owner = owner; this.pct = pct;
        }
    }

    public static class Decision {
        public String id, kind, title, from, due, action;
        public Tone dueTone;
        public Decision(String id, String kind, String title, String from, String due,
                        Tone dueTone, String action) {
            this.id = id; this.kind = kind; this.title = title; this.from = from;
            this.due = due; this.dueTone = dueTone; this.action = action;
        }
    }

    /** A label / note / value / delta row, used by the section tabs. */
    public static class Row {
        public String label, note, value, delta;
        public Tone tone;
        /** A sub-heading inside a section ("Projects in flight"), not a figure. */
        public boolean heading;
        /** Shown when the row is tapped; null = not tappable. */
        public Definition definition;
        public Row(String label, String note, String value, String delta, Tone tone) {
            this.label = label; this.note = note; this.value = value; this.delta = delta; this.tone = tone;
        }

        public static Row heading(String label) {
            Row r = new Row(label, null, null, null, null);
            r.heading = true;
            return r;
        }
    }

    public static class Section {
        public String intro;
        /** Who owns the full detail (e.g. "the CRO"); null when the CEO owns it. */
        public String owner;
        public List<Row> rows = new ArrayList<>();
        public Section(String intro, String owner) { this.intro = intro; this.owner = owner; }
    }

    /** A quick-tile bottom sheet: subtitle, rows (value shown as a tone-coloured tag) and a CTA. */
    public static class Sheet {
        public String subtitle, cta;
        public int badge;
        public List<Row> rows = new ArrayList<>();
        public Sheet(String subtitle, String cta, int badge) {
            this.subtitle = subtitle; this.cta = cta; this.badge = badge;
        }
    }

    /**
     * What a figure means: the catalog description, where it is read from, how it is measured
     * and its current reading against target. Every line is optional; absent ones are not shown.
     */
    public static class Definition {
        public String title, description, source, measured, direction;
        public String value, target, period, status;
        public Tone tone;
    }
}
