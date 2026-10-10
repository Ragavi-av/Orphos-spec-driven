package com.orphos.mobile.data.repository;

import android.content.Context;

import com.orphos.mobile.R;
import com.orphos.mobile.data.model.CompanyMetrics;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.data.model.MetricDetails;
import com.orphos.mobile.data.model.PersonaWidget;
import com.orphos.mobile.data.model.RoleDashboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Metrics-tab screens from the caller's own widget board and the records behind
 * it (see {@link OrphosRepository#metricsBoard}). Only what the board, the ontology records
 * and the decision log actually hold is placed on screen: a card with no source is left
 * out rather than filled, and the "needs attention" card states facts rather than a forecast.
 */
final class WidgetScreens {

    /** Widgets that make the best CEO hero, strongest first (a headline figure with a target). */
    private static final String[] HERO_PREFERENCE = {"revenue-arr", "pipeline-value", "net-cashflow"};
    /** Section tabs beyond Overview; more do not fit the segmented control. */
    private static final int MAX_SECTIONS = 4;
    private static final int MAX_DECISIONS = 4;
    /** Residual severity (likelihood × impact, 1-25) from which a risk reads as high. */
    private static final int HIGH_SEVERITY = 15, MEDIUM_SEVERITY = 9;

    private static final String AREA_PROJECTS = "Projects & Governance", AREA_FINANCE = "Finance",
            AREA_RISK = "Risk & Compliance", AREA_COMMERCIAL = "Commercial";

    private WidgetScreens() { }

    static CompanyMetrics company(Context ctx, PersonaWidget.Board board, List<DecisionItem> decisions) {
        List<PersonaWidget> widgets = board.widgets;
        MetricDetails d = board.details;
        CompanyMetrics m = new CompanyMetrics();

        PersonaWidget hero = pickHero(widgets);
        if (hero != null) {
            hero(ctx, m, hero, d);
            m.heroDefinition = definition(ctx, hero);
        }
        attention(ctx, m, widgets, d);
        for (PersonaWidget w : widgets) {
            if (w == hero) continue;
            CompanyMetrics.Kpi k = new CompanyMetrics.Kpi(w.label.toUpperCase(Locale.getDefault()),
                    w.hasValue() ? w.value : "—", w.caption, tone(w.status));
            k.definition = definition(ctx, w);
            m.kpis.add(k);
        }
        for (MetricDetails.Objective o : d.objectives) {
            m.okrs.add(new CompanyMetrics.Okr(o.name,
                    ctx.getString(R.string.cm_okr_krs_fmt, o.keyResults.size()), o.progress));
        }
        m.okrPeriod = d.fiscalYear;
        decisions(ctx, m, decisions);
        sections(ctx, m, widgets, d);
        return m;
    }

    // --- Overview ---

    private static void hero(Context ctx, CompanyMetrics m, PersonaWidget hero, MetricDetails d) {
        m.heroLabel = hero.label;
        m.heroValue = hero.value;
        m.heroTarget = hero.target;
        m.heroTargetSuffix = hero.period;
        m.heroAchievedPct = Math.max(0, hero.attainmentPct);
        if (hero.periodElapsedPct >= 0) {
            m.heroExpectedPct = hero.periodElapsedPct;
            m.heroExpectedNote = ctx.getString(R.string.cm_period_elapsed_fmt, hero.periodElapsedPct);
        }
        // A cash hero gets its real month-by-month trend.
        if (hero.id != null && hero.id.startsWith("cash") || "net-cashflow".equals(hero.id)) {
            for (MetricDetails.CashMonth mo : d.cashflow) {
                double v = "cash-inflow".equals(hero.id) ? mo.inflow
                        : "cash-outflow".equals(hero.id) ? mo.outflow : mo.nett;
                m.heroTrend.add((float) v);
            }
            if (!d.cashflow.isEmpty()) m.heroUpdated = d.cashflow.get(d.cashflow.size() - 1).label;
        }
    }

    /**
     * The one thing most off plan, stated plainly: the red widget furthest from its target,
     * backed by the top open risk (or the largest budget overrun). Null when nothing is red
     * and no risk is high — the card is omitted rather than invented.
     */
    private static void attention(Context ctx, CompanyMetrics m, List<PersonaWidget> widgets, MetricDetails d) {
        List<PersonaWidget> red = new ArrayList<>();
        for (PersonaWidget w : widgets) if (w.hasValue() && w.status == PersonaWidget.Status.BAD) red.add(w);
        MetricDetails.Risk topRisk = d.risks.isEmpty() ? null : d.risks.get(0);
        if (red.isEmpty() && (topRisk == null || topRisk.severity < HIGH_SEVERITY)) return;

        PersonaWidget worst = null;
        for (PersonaWidget w : red) {
            if (worst == null || distance(w) > distance(worst)) worst = w;
        }
        if (worst != null) {
            m.alertKicker = ctx.getString(R.string.cm_attention_kicker_fmt, shortArea(area(worst)).toUpperCase(Locale.getDefault()));
            m.alertTitle = worst.target != null
                    ? ctx.getString(R.string.cm_attention_title_fmt, worst.label, worst.value, worst.target)
                    : worst.label + ": " + worst.value;
            if (red.size() > 1) m.alertBadge = ctx.getString(R.string.cm_attention_count_fmt, red.size());
            for (PersonaWidget w : red) {
                String tag = shortArea(area(w));
                if (!m.alertTags.contains(tag)) m.alertTags.add(tag);
            }
            if (topRisk != null) {
                m.alertBody = ctx.getString(R.string.cm_attention_body_risk_fmt, topRisk.name, topRisk.severity,
                        money(topRisk.exposure, topRisk.currency), pretty(topRisk.status));
            }
        } else {
            m.alertKicker = ctx.getString(R.string.cm_attention_kicker_fmt, shortArea(AREA_RISK).toUpperCase(Locale.getDefault()));
            m.alertTitle = ctx.getString(R.string.cm_attention_risk_title_fmt, topRisk.name);
            m.alertBody = ctx.getString(R.string.cm_attention_body_risk_short_fmt, topRisk.severity,
                    money(topRisk.exposure, topRisk.currency), pretty(topRisk.status));
            m.alertTags.add(shortArea(AREA_RISK));
        }
    }

    /** How far a widget sits from its target, in percentage points (either direction). */
    private static int distance(PersonaWidget w) {
        return w.attainmentPct < 0 ? 0 : Math.abs(w.attainmentPct - 100);
    }

    /** Pending decisions when there are any; otherwise the latest outcomes, so the card is never a blank. */
    private static void decisions(Context ctx, CompanyMetrics m, List<DecisionItem> decisions) {
        if (decisions == null || decisions.isEmpty()) return;
        List<DecisionItem> pending = new ArrayList<>();
        for (DecisionItem d : decisions) if ("PENDING".equals(d.status) || "DETECTED".equals(d.status)) pending.add(d);
        boolean waiting = !pending.isEmpty();
        List<DecisionItem> shown = waiting ? pending : new ArrayList<>(decisions);
        if (!waiting) shown.sort(Comparator.comparing((DecisionItem x) -> x.decidedAt != null ? x.decidedAt
                : x.updatedAt != null ? x.updatedAt : "").reversed());
        m.decisionsPending = waiting;
        if (!waiting) m.decisionsTitle = ctx.getString(R.string.cm_recent_decisions);
        for (DecisionItem d : shown) {
            if (m.decisions.size() >= MAX_DECISIONS) break;
            String kind = waiting ? (d.priorityLevel != null ? d.priorityLevel : ctx.getString(R.string.cm_decision_kind))
                    : pretty(d.status).toUpperCase(Locale.getDefault());
            String when = waiting ? shortDate(d.dueDate) : shortDate(d.decidedAt != null ? d.decidedAt : d.updatedAt);
            m.decisions.add(new CompanyMetrics.Decision(d.id, kind, d.title,
                    orEmpty(firstNonBlank(d.decisionArea, d.domain)), orEmpty(when),
                    waiting ? priorityTone(d.priorityLevel) : statusTone(d.status),
                    ctx.getString(waiting ? R.string.cm_review : R.string.cm_view)));
        }
    }

    // --- Section tabs: each area's widgets, then the records behind them ---

    private static void sections(Context ctx, CompanyMetrics m, List<PersonaWidget> widgets, MetricDetails d) {
        Map<String, List<CompanyMetrics.Row>> byArea = new LinkedHashMap<>();
        for (PersonaWidget w : widgets) {
            List<CompanyMetrics.Row> rows = byArea.computeIfAbsent(area(w), k -> new ArrayList<>());
            if (rows.isEmpty()) rows.add(CompanyMetrics.Row.heading(ctx.getString(R.string.cm_h_board)));
            CompanyMetrics.Row row = new CompanyMetrics.Row(w.label, w.caption, w.hasValue() ? w.value : "—", null, tone(w.status));
            row.definition = definition(ctx, w);
            rows.add(row);
        }

        // Project and cost-centre lists are deliberately not shown: in this tenant those
        // records come from scripts/sample-data/SampleDataGenerator.java (generated filler
        // such as "Data Lake Modernisation 1"), not from the tenant's governance profile.
        if (!d.cashflow.isEmpty()) {
            List<CompanyMetrics.Row> rows = byArea.computeIfAbsent(AREA_FINANCE, k -> new ArrayList<>());
            rows.add(CompanyMetrics.Row.heading(ctx.getString(R.string.cm_h_cash)));
            for (int i = d.cashflow.size() - 1; i >= 0; i--) {
                MetricDetails.CashMonth mo = d.cashflow.get(i);
                rows.add(new CompanyMetrics.Row(mo.label,
                        ctx.getString(R.string.cm_cash_note_fmt, money(mo.inflow, d.cashCurrency), money(mo.outflow, d.cashCurrency)),
                        money(mo.nett, d.cashCurrency), null,
                        mo.nett >= 0 ? CompanyMetrics.Tone.GOOD : CompanyMetrics.Tone.BAD));
            }
        }
        if (!d.risks.isEmpty()) {
            List<CompanyMetrics.Row> rows = byArea.computeIfAbsent(AREA_RISK, k -> new ArrayList<>());
            rows.add(CompanyMetrics.Row.heading(ctx.getString(R.string.cm_h_risks)));
            for (MetricDetails.Risk r : d.risks) {
                rows.add(new CompanyMetrics.Row(r.name, joinDot(pretty(r.category), pretty(r.status)),
                        money(r.exposure, r.currency), ctx.getString(R.string.cm_severity_fmt, r.severity),
                        severityTone(r.severity)));
            }
        }
        if (!d.pipeline.isEmpty()) {
            List<CompanyMetrics.Row> rows = byArea.computeIfAbsent(AREA_COMMERCIAL, k -> new ArrayList<>());
            rows.add(CompanyMetrics.Row.heading(ctx.getString(R.string.cm_h_pipeline)));
            for (MetricDetails.Opportunity o : d.pipeline) rows.add(opportunityRow(ctx, o));
        }
        if (!d.objectives.isEmpty()) {
            List<CompanyMetrics.Row> rows = byArea.computeIfAbsent(AREA_PROJECTS, k -> new ArrayList<>());
            rows.add(CompanyMetrics.Row.heading(ctx.getString(R.string.cm_h_okrs)));
            for (MetricDetails.Objective o : d.objectives) {
                rows.add(new CompanyMetrics.Row(o.name, ctx.getString(R.string.cm_okr_krs_fmt, o.keyResults.size()),
                        o.progress + "%", null, okrTone(o.progress)));
            }
        }

        for (Map.Entry<String, List<CompanyMetrics.Row>> e : byArea.entrySet()) {
            if (m.sections.size() >= MAX_SECTIONS) break;
            CompanyMetrics.Section s = new CompanyMetrics.Section(
                    areaDefinition(ctx, e.getKey()), null);
            s.rows.addAll(e.getValue());
            m.sections.put(shortArea(e.getKey()), s);
        }
    }

    private static CompanyMetrics.Row projectRow(Context ctx, MetricDetails.Project p) {
        String note = joinDot(p.owner, p.endDate != null ? ctx.getString(R.string.cm_due_fmt, shortDate(p.endDate)) : null);
        String delta = !Double.isNaN(p.spent) && !Double.isNaN(p.budget)
                ? ctx.getString(R.string.cm_spent_of_fmt, money(p.spent, p.currency), money(p.budget, p.currency)) : null;
        String value = p.progress >= 0 ? p.progress + "% · " + pretty(p.status) : pretty(p.status);
        return new CompanyMetrics.Row(p.name, note, value, delta, projectTone(p.status));
    }

    private static CompanyMetrics.Row opportunityRow(Context ctx, MetricDetails.Opportunity o) {
        String prob = Double.isNaN(o.probability) ? null : Math.round(o.probability * 100) + "%";
        String note = joinDot(pretty(o.stage), prob,
                o.closeDate != null ? ctx.getString(R.string.cm_closes_fmt, shortDate(o.closeDate)) : null);
        return new CompanyMetrics.Row(o.name, note, money(o.weightedValue, o.currency),
                Double.isNaN(o.dealValue) ? null : ctx.getString(R.string.cm_of_deal_fmt, money(o.dealValue, o.currency)),
                CompanyMetrics.Tone.INFO);
    }

    // --- Quick tiles: each opens the full list behind one figure ---

    private static void sheets(Context ctx, CompanyMetrics m, MetricDetails d) {
        String ask = ctx.getString(R.string.cm_ask);
        if (!d.objectives.isEmpty()) {
            int onTrack = 0;
            for (MetricDetails.Objective o : d.objectives) if (o.progress >= 60) onTrack++;
            CompanyMetrics.Sheet s = new CompanyMetrics.Sheet(
                    ctx.getString(R.string.cm_sheet_okrs_sub_fmt, d.objectives.size(), onTrack), ask, 0);
            for (MetricDetails.Objective o : d.objectives) {
                for (MetricDetails.KeyResult k : o.keyResults) {
                    s.rows.add(new CompanyMetrics.Row(k.name, o.name, k.progress + "%", null, okrTone(k.progress)));
                }
            }
            m.sheets.put(ctx.getString(R.string.cm_tile_okrs), s);
        }
        if (!d.projects.isEmpty()) {
            int troubled = 0;
            for (MetricDetails.Project p : d.projects) if (p.troubled()) troubled++;
            CompanyMetrics.Sheet s = new CompanyMetrics.Sheet(
                    ctx.getString(R.string.cm_sheet_projects_sub_fmt, d.projects.size(), troubled), ask, troubled);
            for (MetricDetails.Project p : d.projects) {
                s.rows.add(new CompanyMetrics.Row(p.name, joinDot(p.owner,
                        p.endDate != null ? ctx.getString(R.string.cm_due_fmt, shortDate(p.endDate)) : null),
                        pretty(p.status), null, projectTone(p.status)));
            }
            m.sheets.put(ctx.getString(R.string.cm_tile_projects), s);
        }
        if (!d.risks.isEmpty()) {
            int high = 0;
            double total = 0;
            String cur = null;
            for (MetricDetails.Risk r : d.risks) {
                if (r.severity >= HIGH_SEVERITY) high++;
                if (!Double.isNaN(r.exposure)) total += r.exposure;
                if (cur == null) cur = r.currency;
            }
            CompanyMetrics.Sheet s = new CompanyMetrics.Sheet(
                    ctx.getString(R.string.cm_sheet_risks_sub_fmt, d.risks.size(), money(total, cur)), ask, high);
            for (MetricDetails.Risk r : d.risks) {
                s.rows.add(new CompanyMetrics.Row(r.name, joinDot(pretty(r.category), money(r.exposure, r.currency)),
                        ctx.getString(R.string.cm_severity_fmt, r.severity), null, severityTone(r.severity)));
            }
            m.sheets.put(ctx.getString(R.string.cm_tile_risks), s);
        }
        if (!d.pipeline.isEmpty()) {
            double weighted = 0;
            String cur = null;
            for (MetricDetails.Opportunity o : d.pipeline) {
                if (!Double.isNaN(o.weightedValue)) weighted += o.weightedValue;
                if (cur == null) cur = o.currency;
            }
            CompanyMetrics.Sheet s = new CompanyMetrics.Sheet(
                    ctx.getString(R.string.cm_sheet_pipeline_sub_fmt, d.pipeline.size(), money(weighted, cur)), ask, 0);
            for (MetricDetails.Opportunity o : d.pipeline) {
                s.rows.add(new CompanyMetrics.Row(o.name, pretty(o.stage), money(o.weightedValue, o.currency),
                        null, CompanyMetrics.Tone.INFO));
            }
            m.sheets.put(ctx.getString(R.string.cm_tile_pipeline), s);
        }
    }

    // --- Role dashboard (CHRO and any persona on the generic renderer) ---

    static RoleDashboard role(Context ctx, List<PersonaWidget> widgets) {
        RoleDashboard d = new RoleDashboard();
        if (widgets.isEmpty()) return d;
        RoleDashboard.Tab overview = new RoleDashboard.Tab(ctx.getString(R.string.cm_tab_overview));
        overview.add(RoleDashboard.Block.statCards(2, stats(widgets)));
        overview.add(RoleDashboard.Block.card(null, null, null,
                RoleDashboard.Element.caption(ctx.getString(R.string.cm_board_note))));
        d.tabs.add(overview);
        Map<String, List<PersonaWidget>> areas = new LinkedHashMap<>();
        for (PersonaWidget w : widgets) areas.computeIfAbsent(area(w), k -> new ArrayList<>()).add(w);
        if (areas.size() > 1) {
            for (Map.Entry<String, List<PersonaWidget>> e : areas.entrySet()) {
                RoleDashboard.Tab t = new RoleDashboard.Tab(shortArea(e.getKey()));
                t.add(RoleDashboard.Block.statCards(2, stats(e.getValue())));
                d.tabs.add(t);
            }
        }
        return d;
    }

    private static RoleDashboard.Stat[] stats(List<PersonaWidget> widgets) {
        RoleDashboard.Stat[] out = new RoleDashboard.Stat[widgets.size()];
        for (int i = 0; i < out.length; i++) {
            PersonaWidget w = widgets.get(i);
            out[i] = new RoleDashboard.Stat(w.label, w.hasValue() ? w.value : "—",
                    roleTone(w.status), w.caption, RoleDashboard.Tone.MUTED);
        }
        return out;
    }

    // --- definitions ---

    /** What a widget measures and how it reads today — the sheet shown when it is tapped. */
    private static CompanyMetrics.Definition definition(Context ctx, PersonaWidget w) {
        CompanyMetrics.Definition def = new CompanyMetrics.Definition();
        def.title = w.label;
        def.description = w.description;
        def.source = w.source;
        def.measured = aggregation(ctx, w.aggregation);
        if (w.higherIsBetter != null) {
            def.direction = ctx.getString(w.higherIsBetter ? R.string.cm_def_higher : R.string.cm_def_lower);
        }
        def.value = w.hasValue() ? w.value : (w.caption != null ? w.caption : "—");
        def.target = w.target != null ? w.target : w.hasValue() ? ctx.getString(R.string.cm_def_no_target) : null;
        def.period = w.period;
        def.tone = tone(w.status);
        switch (w.status) {
            case GOOD: def.status = ctx.getString(R.string.cm_def_status_good); break;
            case WARN: def.status = ctx.getString(R.string.cm_def_status_warn); break;
            case BAD:  def.status = ctx.getString(R.string.cm_def_status_bad); break;
            default:   def.status = null;
        }
        return def;
    }

    private static String aggregation(Context ctx, String agg) {
        if (agg == null) return null;
        switch (agg.toUpperCase(Locale.ROOT)) {
            case "AVG":   return ctx.getString(R.string.cm_def_agg_avg);
            case "SUM":   return ctx.getString(R.string.cm_def_agg_sum);
            case "LAST":  return ctx.getString(R.string.cm_def_agg_last);
            case "COUNT": return ctx.getString(R.string.cm_def_agg_count);
            default:      return pretty(agg);
        }
    }

    /** One line on what a tab (a catalog area) covers. */
    private static String areaDefinition(Context ctx, String area) {
        switch (area) {
            case "IT Operations":   return ctx.getString(R.string.cm_area_def_it);
            case AREA_PROJECTS:     return ctx.getString(R.string.cm_area_def_projects);
            case AREA_FINANCE:      return ctx.getString(R.string.cm_area_def_finance);
            case "People":          return ctx.getString(R.string.cm_area_def_people);
            case AREA_RISK:         return ctx.getString(R.string.cm_area_def_risk);
            case AREA_COMMERCIAL:   return ctx.getString(R.string.cm_area_def_commercial);
            default:                return ctx.getString(R.string.cm_board_intro_fmt, area);
        }
    }

    // --- helpers ---

    private static PersonaWidget pickHero(List<PersonaWidget> widgets) {
        for (String id : HERO_PREFERENCE) {
            for (PersonaWidget w : widgets) if (id.equals(w.id) && w.hasValue()) return w;
        }
        for (PersonaWidget w : widgets) if (w.hasValue() && w.target != null) return w;
        for (PersonaWidget w : widgets) if (w.hasValue()) return w;
        return null;
    }

    private static String area(PersonaWidget w) {
        return w.area != null ? w.area : "Other";
    }

    /** Catalog area → a label that fits a segmented tab. */
    private static String shortArea(String area) {
        switch (area) {
            case "IT Operations":         return "IT";
            case AREA_PROJECTS:           return "Projects";
            case AREA_RISK:               return "Risk";
            case AREA_COMMERCIAL:         return "Growth";
            case "Onboarded Data":        return "Data";
            default:                      return area;
        }
    }

    private static String money(double v, String currency) {
        return Double.isNaN(v) ? "—" : PersonaWidget.money(v, currency);
    }

    /** "AT_RISK" → "At risk". */
    private static String pretty(String code) {
        if (code == null || code.isEmpty()) return "";
        String s = code.replace('_', ' ').toLowerCase(Locale.getDefault());
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String joinDot(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static CompanyMetrics.Tone tone(PersonaWidget.Status s) {
        switch (s) {
            case GOOD: return CompanyMetrics.Tone.GOOD;
            case WARN: return CompanyMetrics.Tone.WARN;
            case BAD:  return CompanyMetrics.Tone.BAD;
            default:   return CompanyMetrics.Tone.INFO;
        }
    }

    private static RoleDashboard.Tone roleTone(PersonaWidget.Status s) {
        switch (s) {
            case GOOD: return RoleDashboard.Tone.GOOD;
            case WARN: return RoleDashboard.Tone.WARN;
            case BAD:  return RoleDashboard.Tone.BAD;
            default:   return RoleDashboard.Tone.NEUTRAL;
        }
    }

    private static CompanyMetrics.Tone priorityTone(String priority) {
        if ("CRITICAL".equals(priority)) return CompanyMetrics.Tone.BAD;
        if ("HIGH".equals(priority)) return CompanyMetrics.Tone.WARN;
        return CompanyMetrics.Tone.INFO;
    }

    private static CompanyMetrics.Tone statusTone(String status) {
        if ("APPROVED".equals(status) || "COMPLETED".equals(status)) return CompanyMetrics.Tone.GOOD;
        if ("REJECTED".equals(status)) return CompanyMetrics.Tone.BAD;
        return CompanyMetrics.Tone.INFO;
    }

    private static CompanyMetrics.Tone projectTone(String status) {
        if ("AT_RISK".equals(status) || "DELAYED".equals(status)) return CompanyMetrics.Tone.BAD;
        if ("ON_TRACK".equals(status) || "COMPLETED".equals(status)) return CompanyMetrics.Tone.GOOD;
        return CompanyMetrics.Tone.INFO;
    }

    private static CompanyMetrics.Tone severityTone(int severity) {
        return severity >= HIGH_SEVERITY ? CompanyMetrics.Tone.BAD
                : severity >= MEDIUM_SEVERITY ? CompanyMetrics.Tone.WARN : CompanyMetrics.Tone.INFO;
    }

    private static CompanyMetrics.Tone okrTone(int pct) {
        return pct >= 60 ? CompanyMetrics.Tone.GOOD : pct >= 40 ? CompanyMetrics.Tone.WARN : CompanyMetrics.Tone.BAD;
    }

    /** "2026-10-05…" → "5 OCT"; anything unparseable is returned unchanged. */
    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return iso;
        try {
            return java.time.LocalDate.parse(iso.substring(0, 10))
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                    .toUpperCase(Locale.getDefault());
        } catch (Exception e) {
            return iso;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return null;
    }
}
