package com.orphos.mobile.data.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A role's work-tab dashboard described as data: section tabs, each a stack of
 * {@link Block}s (cards, stat-card grids, alerts), plus quick actions that open a detail view.
 * {@code RoleDashboardFragment} renders any dashboard of this shape, so a new persona's
 * screen is a new data set rather than a new screen. Colours travel as a {@link Tone}.
 */
public class RoleDashboard {

    /** Semantic colour, mapped onto the design system by the renderer. */
    public enum Tone { NEUTRAL, MUTED, GOOD, WARN, BAD, INFO, AI }

    public String title;
    public String searchHint;
    public List<Tab> tabs = new ArrayList<>();
    /** Quick actions, shown as tiles on the first tab. */
    public List<Action> actions = new ArrayList<>();

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    public static class Tab {
        public String label;
        public List<Block> blocks = new ArrayList<>();
        public Tab(String label) { this.label = label; }
        public Tab add(Block b) { blocks.add(b); return this; }
    }

    public static class Block {
        public enum Kind { CARD, STAT_CARDS, ALERT }
        public enum AlertStyle { AI, DANGER }

        public Kind kind;
        // CARD
        public String title;
        public String trailing;
        public Tone trailingTone = Tone.INFO;
        /** Draws the trailing text as a tinted status pill. */
        public boolean trailingPill;
        /** Shows a green "Live" marker beside the title. */
        public boolean live;
        /** Hero styling: accent border and tinted surface. */
        public boolean highlight;
        public List<Element> elements = new ArrayList<>();
        // STAT_CARDS
        public int columns = 2;
        public List<Stat> stats = new ArrayList<>();
        // ALERT
        public AlertStyle alertStyle;
        public String kicker;
        public String body;

        public static Block card(String title, String trailing, Tone trailingTone, Element... els) {
            Block b = new Block();
            b.kind = Kind.CARD;
            b.title = title;
            b.trailing = trailing;
            if (trailingTone != null) b.trailingTone = trailingTone;
            b.elements.addAll(Arrays.asList(els));
            return b;
        }

        public static Block hero(String title, Element... els) {
            Block b = card(title, null, null, els);
            b.highlight = true;
            return b;
        }

        public static Block statCards(int columns, Stat... stats) {
            Block b = new Block();
            b.kind = Kind.STAT_CARDS;
            b.columns = columns;
            b.stats.addAll(Arrays.asList(stats));
            return b;
        }

        public static Block alert(AlertStyle style, String kicker, String body) {
            Block b = new Block();
            b.kind = Kind.ALERT;
            b.alertStyle = style;
            b.kicker = kicker;
            b.body = body;
            return b;
        }
    }

    /** One line of content inside a card. Which fields matter depends on {@link #kind}. */
    public static class Element {
        public enum Kind {
            /** Large value with an optional note beside it (tone colours the note). */
            BIG_VALUE,
            /** Label left, value right. Optional pill, tag, caps label, divider above. */
            ROW,
            /** A labelled bar: label + value above, bar filled to pct below. */
            METER,
            /** A bare progress bar with an optional marker and captions under it. */
            PROGRESS,
            /** A stacked bar of segments with a legend under it. */
            SEGMENTS,
            /** A grid of small inset tiles (key over value). */
            TILES,
            /** Three-column table row (label, plan, actual); header=true for the heading. */
            TABLE_ROW,
            /** Vertical bar chart; the last bar is highlighted in {@link #tone}. */
            COLUMNS,
            /** Small muted footnote. */
            CAPTION
        }

        public Kind kind;
        public String label, value, note;
        public Tone tone = Tone.NEUTRAL;
        /** Colour of the value (ROW / METER); tone colours the bar or note. */
        public Tone valueTone = Tone.NEUTRAL;
        public int pct = -1;
        public int marker = -1;
        public boolean bold, pill, caps, divider, header;
        public String tag;
        public Tone tagTone = Tone.WARN;
        /** PROGRESS captions: left, right, and an accent appended to the right one. */
        public String captionLeft, captionRight, captionAccent;
        public int columns = 3;
        public List<Stat> tiles = new ArrayList<>();
        public List<Segment> segments = new ArrayList<>();
        public List<String> legend = new ArrayList<>();
        public List<String> cells = new ArrayList<>();
        public List<Bar> bars = new ArrayList<>();
        /** BIG_VALUE size in sp. */
        public float size = 26f;

        private static Element of(Kind k) { Element e = new Element(); e.kind = k; return e; }

        public static Element bigValue(String value, String note, Tone noteTone, float size) {
            Element e = of(Kind.BIG_VALUE);
            e.value = value; e.note = note; e.tone = noteTone == null ? Tone.MUTED : noteTone; e.size = size;
            return e;
        }

        public static Element row(String label, String value, Tone valueTone, boolean bold) {
            Element e = of(Kind.ROW);
            e.label = label; e.value = value; e.valueTone = valueTone; e.bold = bold;
            return e;
        }

        /** Same as {@link #row} with a divider above it. */
        public static Element rowDiv(String label, String value, Tone valueTone, boolean bold) {
            Element e = row(label, value, valueTone, bold);
            e.divider = true;
            return e;
        }

        /** A row whose value is shown as a tone-tinted pill. */
        public static Element pillRow(String label, String status, Tone tone, boolean divider) {
            Element e = row(label, status, tone, false);
            e.pill = true; e.divider = divider;
            return e;
        }

        /** An uppercase event row ("OCT 5 · PAYROLL RUN"), optionally tagged. */
        public static Element eventRow(String label, String value, Tone valueTone, String tag, boolean divider) {
            Element e = row(label, value, valueTone, true);
            e.caps = true; e.tag = tag; e.divider = divider;
            return e;
        }

        public static Element meter(String label, String value, Tone valueTone, int pct, Tone barTone) {
            Element e = of(Kind.METER);
            e.label = label; e.value = value; e.valueTone = valueTone; e.pct = pct; e.tone = barTone;
            return e;
        }

        public static Element progress(int pct, Tone tone, int marker, String left, String right, String accent) {
            Element e = of(Kind.PROGRESS);
            e.pct = pct; e.tone = tone; e.marker = marker;
            e.captionLeft = left; e.captionRight = right; e.captionAccent = accent;
            return e;
        }

        public static Element segments(List<Segment> segments, String... legend) {
            Element e = of(Kind.SEGMENTS);
            e.segments.addAll(segments);
            e.legend.addAll(Arrays.asList(legend));
            return e;
        }

        public static Element tiles(int columns, Stat... tiles) {
            Element e = of(Kind.TILES);
            e.columns = columns;
            e.tiles.addAll(Arrays.asList(tiles));
            return e;
        }

        public static Element tableRow(boolean header, Tone actualTone, String... cells) {
            Element e = of(Kind.TABLE_ROW);
            e.header = header; e.valueTone = actualTone; e.divider = !header;
            e.cells.addAll(Arrays.asList(cells));
            return e;
        }

        public static Element columns(Tone highlight, Bar... bars) {
            Element e = of(Kind.COLUMNS);
            e.tone = highlight;
            e.bars.addAll(Arrays.asList(bars));
            return e;
        }

        public static Element caption(String text) {
            Element e = of(Kind.CAPTION);
            e.label = text;
            return e;
        }
    }

    public static class Stat {
        public String key, value, sub;
        public Tone tone, subTone;
        public Stat(String key, String value, Tone tone) { this(key, value, tone, null, null); }
        public Stat(String key, String value, Tone tone, String sub, Tone subTone) {
            this.key = key; this.value = value; this.tone = tone == null ? Tone.NEUTRAL : tone;
            this.sub = sub; this.subTone = subTone == null ? Tone.MUTED : subTone;
        }
    }

    public static class Segment {
        public int pct;
        public Tone tone;
        public Segment(int pct, Tone tone) { this.pct = pct; this.tone = tone; }
    }

    public static class Bar {
        public String label;
        public float value;
        public Bar(String label, float value) { this.label = label; this.value = value; }
    }

    /** A quick action: a tile on the first tab that opens a detail view in its place. */
    public static class Action {
        /** Icon key, resolved by the renderer (e.g. "person_add", "star", "event"). */
        public String icon;
        public String label, sub, cta;
        public List<Stat> stats = new ArrayList<>();
        public List<ActionRow> rows = new ArrayList<>();
        public Action(String icon, String label, String sub, String cta) {
            this.icon = icon; this.label = label; this.sub = sub; this.cta = cta;
        }
    }

    public static class ActionRow {
        public String title, meta, status;
        public Tone tone;
        public ActionRow(String title, String meta, String status, Tone tone) {
            this.title = title; this.meta = meta; this.status = status; this.tone = tone;
        }
    }
}
