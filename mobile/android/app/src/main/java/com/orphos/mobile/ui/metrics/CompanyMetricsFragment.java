package com.orphos.mobile.ui.metrics;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.CompanyMetrics;
import com.orphos.mobile.ui.advisors.AskOrphosActivity;
import com.orphos.mobile.ui.common.HeaderActions;
import com.orphos.mobile.ui.widget.SparklineView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * CEO "Metrics" tab (company view): the revenue run-rate hero, an AI cross-functional alert,
 * headline KPIs, plus outcome-only
 * Growth / Strategy / People tabs and quick tiles that open detail sheets. Everything is
 * built from {@link CompanyMetrics}; the CFO keeps the finance dashboard in FinancialsFragment.
 */
public class CompanyMetricsFragment extends Fragment {

    /** Business host inner tab for Tasks (see MainActivity#openBusinessTab). */
    private static final int BUSINESS_TASKS = 1;

    private HeaderActions header;
    private CompanyMetrics data;
    private boolean sample;
    private String tab;

    @Override
    public void onResume() {
        super.onResume();
        header.refreshStatus();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_company_metrics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        v.findViewById(R.id.cm_back).setOnClickListener(x -> {
            if (getActivity() instanceof MainActivity m) m.selectTab(R.id.nav_home);
        });
        header = HeaderActions.bind(this, v, v.findViewById(R.id.cm_search));

        String today = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date());
        ((TextView) v.findViewById(R.id.cm_subtitle)).setText(getString(R.string.cm_view_fmt, today));

        tab = getString(R.string.cm_tab_overview);
        OrphosApp.get().repo().companyMetrics((m, fromMock) -> {
            if (!isAdded() || getView() == null) return;
            data = m;
            sample = fromMock;
            TextView status = getView().findViewById(R.id.cm_status);
            if (m == null || m.isEmpty()) {
                // Reached the server but the board is empty ≠ couldn't reach it.
                status.setText(m != null && !fromMock ? R.string.cm_board_empty : R.string.data_unavailable);
                return;
            }
            status.setVisibility(View.GONE);
            renderTabs();
            renderTiles();
            renderContent();
        });
    }

    // --- chrome: section tabs + quick tiles ---

    private void renderTabs() {
        LinearLayout tabs = requireView().findViewById(R.id.cm_tabs);
        tabs.removeAllViews();
        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(getString(R.string.cm_tab_overview));
        labels.addAll(data.sections.keySet());
        for (String label : labels) {
            boolean active = label.equals(tab);
            TextView t = text(label, active ? R.color.on_primary : R.color.text_secondary, 13f, SEMI);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            // A live board can bring up to five tabs; shrink the label rather than clip it.
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, 9, 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            if (active) t.setBackgroundResource(R.drawable.bg_segment_active);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            t.setLayoutParams(lp);
            t.setOnClickListener(x -> {
                tab = label;
                renderTabs();
                renderContent();
            });
            tabs.addView(t);
        }
    }

    private void renderTiles() {
        LinearLayout tiles = requireView().findViewById(R.id.cm_tiles);
        tiles.removeAllViews();
        int i = 0;
        for (Map.Entry<String, CompanyMetrics.Sheet> e : data.sheets.entrySet()) {
            TileStyle s = TileStyle.of(e.getKey());
            FrameLayout tile = new FrameLayout(requireContext());
            tile.setBackgroundResource(R.drawable.bg_metric_tile);
            tile.setClickable(true);
            tile.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i++ > 0) lp.leftMargin = dp(8);
            tile.setLayoutParams(lp);

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(2), dp(12), dp(2), dp(10));
            col.addView(iconBox(s, 32, 17));
            TextView label = text(e.getKey(), 0, 11f, SEMI);
            label.setTextColor(Color.parseColor("#B9C3DA"));
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(label, 8, 11, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(16));
            llp.topMargin = dp(7);
            label.setLayoutParams(llp);
            col.addView(label);
            tile.addView(col);

            if (e.getValue().badge > 0) {
                TextView badge = text(String.valueOf(e.getValue().badge), R.color.on_primary, 10f, BOLD);
                badge.setGravity(Gravity.CENTER);
                badge.setMinWidth(dp(16));
                badge.setPadding(dp(4), 0, dp(4), 0);
                badge.setBackground(rounded(Color.parseColor("#F05D5E"), 8));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.END);
                blp.topMargin = dp(6);
                blp.rightMargin = dp(8);
                badge.setLayoutParams(blp);
                tile.addView(badge);
            }
            String key = e.getKey();
            tile.setOnClickListener(x -> openSheet(key, e.getValue()));
            tiles.addView(tile);
        }
    }

    // --- content: Overview, or one function's outcome rows ---

    private void renderContent() {
        LinearLayout content = requireView().findViewById(R.id.cm_content);
        content.removeAllViews();
        CompanyMetrics.Section section = data.sections.get(tab);
        if (section == null) {
            if (data.heroValue != null) content.addView(tappable(heroCard(), data.heroDefinition));
            if (data.alertTitle != null) content.addView(alertCard());
            if (!data.kpis.isEmpty()) content.addView(kpiRow());
        } else {
            TextView intro = text(section.intro, R.color.metric_label, 12f, NORMAL);
            intro.setPadding(dp(4), 0, dp(4), 0);
            intro.setLayoutParams(block());
            content.addView(intro);
            for (CompanyMetrics.Row r : section.rows) content.addView(tappable(sectionRow(r), r.definition));
            // A sample board is not the web dashboard, so it gets only the sample note below.
            String who = section.owner != null ? getString(R.string.cm_detail_owner_fmt, section.owner)
                    : !sample ? getString(R.string.cm_board_note) : null;
            if (who != null) {
                SpannableStringBuilder foot = new SpannableStringBuilder(who);
                int start = who.indexOf("in ") + 3;
                if (start > 2) {
                    foot.setSpan(new ForegroundColorSpan(color(R.color.link)), start, who.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                TextView f = text(foot, R.color.text_muted, 12f, NORMAL);
                f.setGravity(Gravity.CENTER);
                f.setPadding(dp(8), dp(8), dp(8), dp(8));
                f.setLayoutParams(block());
                content.addView(f);
            }
        }
        if (sample) {
            TextView note = text(getString(R.string.cm_sample_note), R.color.text_muted, 11f, NORMAL);
            note.setGravity(Gravity.CENTER);
            note.setLayoutParams(block());
            content.addView(note);
        }
    }

    private View heroCard() {
        LinearLayout box = card(R.drawable.bg_metrics_hero, 18, 18);

        // Label + Live pill, updated time on the right.
        LinearLayout top = hRow();
        TextView label = text(data.heroLabel, 0, 13f, SEMI);
        label.setTextColor(Color.parseColor("#9AA6C2"));
        top.addView(label);
        top.addView(livePill());
        top.addView(weightSpacer());
        if (data.heroUpdated != null) top.addView(text(data.heroUpdated, R.color.text_muted, 11f, NORMAL));
        box.addView(top);

        // Value + target, sparkline on the right.
        LinearLayout mid = hRow();
        mid.setGravity(Gravity.BOTTOM);
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).topMargin = dp(10);
        LinearLayout left = new LinearLayout(requireContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(data.heroValue, R.color.text, 40f, BOLD);
        value.setLetterSpacing(-0.03f);
        value.setIncludeFontPadding(false);
        value.setMaxLines(1);
        left.addView(value);
        if (data.heroTarget != null) {
            String line = getString(R.string.cm_of_target_fmt, data.heroTarget,
                    data.heroTargetSuffix == null ? "" : data.heroTargetSuffix);
            TextView target = text(emphasise(line, data.heroTarget, color(R.color.text)),
                    0, 13f, NORMAL);
            target.setTextColor(Color.parseColor("#9AA6C2"));
            target.setPadding(0, dp(6), 0, 0);
            left.addView(target);
        }
        mid.addView(left);
        if (data.heroTrend.size() > 1) {
            SparklineView spark = new SparklineView(requireContext());
            spark.setPoints(data.heroTrend);
            spark.setLineColor(color(R.color.metric_good));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(112), dp(48));
            slp.leftMargin = dp(12);
            spark.setLayoutParams(slp);
            mid.addView(spark);
        }
        box.addView(mid);

        // Achieved vs target, with the "expected by now" marker — only when there is a target.
        if (data.heroTarget != null) {
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{color(R.color.primary), Color.parseColor("#5B8CFF")});
            fill.setCornerRadius(dp(99));
            View bar = progressBar(data.heroAchievedPct, fill, 8, data.heroExpectedPct, 16,
                    Color.parseColor("#7D89A6"));
            ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin = dp(12);
            box.addView(bar);
            LinearLayout caps = hRow();
            ((LinearLayout.LayoutParams) caps.getLayoutParams()).topMargin = dp(6);
            String achieved = getString(R.string.cm_achieved_fmt, data.heroAchievedPct);
            SpannableStringBuilder a = emphasise(achieved, data.heroAchievedPct + "%", color(R.color.metric_info));
            caps.addView(text(a, R.color.metric_label, 11f, NORMAL));
            caps.addView(weightSpacer());
            if (data.heroExpectedNote != null) caps.addView(text(data.heroExpectedNote, R.color.metric_label, 11f, NORMAL));
            box.addView(caps);
        }

        // Runway.
        if (data.runwayValue != null) {
            View div = new View(requireContext());
            div.setBackgroundColor(Color.parseColor("#1C2640"));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dlp.topMargin = dp(14);
            dlp.bottomMargin = dp(12);
            div.setLayoutParams(dlp);
            box.addView(div);
            LinearLayout rw = hRow();
            ImageView ic = new ImageView(requireContext());
            ic.setImageResource(R.drawable.ic_runway);
            ic.setColorFilter(Color.parseColor("#9AA6C2"));
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(15), dp(15)));
            rw.addView(ic);
            TextView rl = text(getString(R.string.cm_runway), 0, 13f, NORMAL);
            rl.setTextColor(Color.parseColor("#9AA6C2"));
            rl.setPadding(dp(8), 0, 0, 0);
            rw.addView(rl);
            rw.addView(weightSpacer());
            SpannableStringBuilder rv = new SpannableStringBuilder(data.runwayValue);
            rv.setSpan(new StyleSpan(Typeface.BOLD), 0, rv.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (data.runwayNote != null) {
                int s = rv.length();
                rv.append(" · ").append(data.runwayNote);
                rv.setSpan(new ForegroundColorSpan(color(R.color.text_muted)), s, rv.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            rw.addView(text(rv, R.color.text, 13f, NORMAL));
            box.addView(rw);
        }
        return box;
    }

    private View livePill() {
        LinearLayout pill = new LinearLayout(requireContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), dp(2), dp(8), dp(2));
        int green = color(R.color.metric_good);
        pill.setBackground(rounded(withAlpha(green, 0.1f), 99));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        pill.setLayoutParams(lp);
        View dot = new View(requireContext());
        dot.setBackground(rounded(green, 3));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        pill.addView(dot);
        TextView t = text(getString(R.string.cm_live), R.color.metric_good, 11f, SEMI);
        t.setPadding(dp(5), 0, 0, 0);
        pill.addView(t);
        return pill;
    }

    private View alertCard() {
        LinearLayout box = card(R.drawable.bg_metrics_alert, 16, 16);
        LinearLayout top = hRow();
        TextView kicker = text(data.alertKicker, R.color.metric_ai, 10f, BOLD);
        kicker.setLetterSpacing(0.1f);
        kicker.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(kicker);
        if (data.alertBadge != null) {
            TextView badge = text(data.alertBadge, R.color.metric_ai, 10f, SEMI);
            badge.setAlpha(0.7f);
            top.addView(badge);
        }
        box.addView(top);
        TextView title = text(data.alertTitle, R.color.metric_ai_title, 15f, BOLD);
        title.setLineSpacing(0f, 1.2f);
        title.setPadding(0, dp(8), 0, 0);
        box.addView(title);
        if (data.alertBody != null) {
            TextView body = text(data.alertBody, R.color.metric_ai_body, 13f, NORMAL);
            body.setLineSpacing(0f, 1.3f);
            body.setPadding(0, dp(4), 0, 0);
            box.addView(body);
        }
        if (!data.alertTags.isEmpty()) {
            LinearLayout tags = hRow();
            ((LinearLayout.LayoutParams) tags.getLayoutParams()).topMargin = dp(10);
            for (String tag : data.alertTags) {
                TextView chip = text(tag, R.color.metric_ai_chip, 11f, SEMI);
                chip.setPadding(dp(9), dp(3), dp(9), dp(3));
                chip.setBackground(rounded(withAlpha(color(R.color.metric_ai), 0.12f), 99));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(6);
                chip.setLayoutParams(clp);
                tags.addView(chip);
            }
            box.addView(tags);
        }
        LinearLayout actions = hRow();
        ((LinearLayout.LayoutParams) actions.getLayoutParams()).topMargin = dp(14);
        TextView scenarios = button(getString(R.string.cm_see_scenarios), R.color.on_primary,
                rounded(color(R.color.metric_ai_button), 11), 38);
        scenarios.setOnClickListener(x -> startActivity(new Intent(requireContext(), AskOrphosActivity.class)));
        TextView assign = button(getString(R.string.cm_assign_owner), R.color.metric_ai_chip,
                outlined(Color.TRANSPARENT, color(R.color.metric_ai_outline), 11), 38);
        assign.setOnClickListener(x -> {
            if (getActivity() instanceof MainActivity m) m.openBusinessTab(BUSINESS_TASKS);
        });
        ((LinearLayout.LayoutParams) assign.getLayoutParams()).leftMargin = dp(8);
        actions.addView(scenarios);
        actions.addView(assign);
        box.addView(actions);
        return box;
    }

    /**
     * Headline KPIs: one row of up to three, or — for a live widget board, which can hold
     * any number of widgets — a two-column grid so each caption stays readable.
     */
    private View kpiRow() {
        int n = data.kpis.size();
        if (n <= 3) return kpiRow(0, n, 3);
        LinearLayout grid = new LinearLayout(requireContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < n; i += 2) grid.addView(kpiRow(i, Math.min(n, i + 2), 2));
        return grid;
    }

    private View kpiRow(int from, int to, int columns) {
        LinearLayout row = hRow();
        row.setGravity(Gravity.NO_GRAVITY);
        row.setLayoutParams(block());
        for (int i = from; i < from + columns; i++) {
            if (i >= to) {
                // Keep a short last row's cards the same width as the rows above. MATCH_PARENT
                // like the cards: a fixed-height pad makes the row wrap to the pad, not the card.
                View pad = new View(requireContext());
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                plp.leftMargin = dp(8);
                pad.setLayoutParams(plp);
                row.addView(pad);
                continue;
            }
            CompanyMetrics.Kpi k = data.kpis.get(i);
            LinearLayout c = new LinearLayout(requireContext());
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackground(outlined(color(R.color.surface), color(R.color.border), 16));
            c.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > from) lp.leftMargin = dp(8);
            c.setLayoutParams(lp);
            TextView l = text(k.label, R.color.metric_label, 10f, SEMI);
            l.setLetterSpacing(0.08f);
            l.setMaxLines(1);
            c.addView(l);
            TextView v = text(k.value, R.color.text, 20f, BOLD);
            v.setLetterSpacing(-0.02f);
            v.setMaxLines(1);
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(v, 14, 22, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            vlp.topMargin = dp(6);
            v.setLayoutParams(vlp);
            c.addView(v);
            if (k.delta != null) {
                TextView dl = text(k.delta, 0, 11f, SEMI);
                dl.setTextColor(tone(k.tone));
                dl.setPadding(0, dp(2), 0, 0);
                dl.setMaxLines(2);
                c.addView(dl);
            }
            row.addView(tappable(c, k.definition));
        }
        return row;
    }

    private View sectionRow(CompanyMetrics.Row r) {
        if (r.heading) {
            TextView h = text(r.label.toUpperCase(Locale.getDefault()), R.color.metric_label, 11f, BOLD);
            h.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams lp = block();
            lp.topMargin = dp(18);
            h.setLayoutParams(lp);
            h.setPadding(dp(4), 0, dp(4), 0);
            return h;
        }
        LinearLayout row = card(0, 16, 16);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(requireContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(text(r.label, R.color.text, 14f, SEMI));
        if (r.note != null) {
            TextView n = text(r.note, R.color.text_muted, 11f, NORMAL);
            n.setPadding(0, dp(3), 0, 0);
            left.addView(n);
        }
        row.addView(left);
        LinearLayout right = new LinearLayout(requireContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setPadding(dp(12), 0, 0, 0);
        TextView v = text(r.value, R.color.text, 18f, BOLD);
        v.setLetterSpacing(-0.02f);
        right.addView(v);
        if (r.delta != null) {
            TextView dl = text(r.delta, 0, 11f, SEMI);
            dl.setTextColor(tone(r.tone));
            right.addView(dl);
        }
        row.addView(right);
        return row;
    }

    // --- metric definition sheet ---

    /** Opens {@code def} when {@code v} is tapped; a null definition leaves the view inert. */
    private View tappable(View v, @Nullable CompanyMetrics.Definition def) {
        if (def == null) return v;
        v.setClickable(true);
        v.setFocusable(true);
        v.setContentDescription(def.title);
        v.setOnClickListener(x -> openDefinition(def));
        return v;
    }

    /** What a metric means, where it comes from and how it reads now. Absent lines are skipped. */
    private void openDefinition(CompanyMetrics.Definition def) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet_top);
        root.setPadding(dp(18), dp(10), dp(18), dp(30));

        View handle = new View(requireContext());
        handle.setBackground(rounded(Color.parseColor("#2A3550"), 2));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(14);
        handle.setLayoutParams(hlp);
        root.addView(handle);

        LinearLayout head = hRow();
        TextView title = text(def.title, R.color.text, 19f, BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        TextView close = text("×", R.color.text_secondary, 18f, NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(getString(R.string.cm_close));
        close.setBackground(outlined(color(R.color.bg), color(R.color.border), 10));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        close.setOnClickListener(x -> dialog.dismiss());
        head.addView(close);
        root.addView(head);

        if (def.description != null) {
            TextView what = text(getString(R.string.cm_def_what).toUpperCase(Locale.getDefault()),
                    R.color.metric_label, 10f, BOLD);
            what.setLetterSpacing(0.08f);
            what.setPadding(0, dp(14), 0, dp(4));
            root.addView(what);
            TextView desc = text(def.description, R.color.text, 14f, NORMAL);
            desc.setLineSpacing(0f, 1.25f);
            desc.setMaxLines(6);
            root.addView(desc);
        }

        LinearLayout facts = new LinearLayout(requireContext());
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setBackground(outlined(color(R.color.surface), color(R.color.border), 14));
        facts.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams flp = block();
        flp.topMargin = dp(16);
        facts.setLayoutParams(flp);
        boolean first = true;
        String[][] lines = {
                {getString(R.string.cm_def_now), def.value},
                {getString(R.string.cm_def_target), def.target},
                {getString(R.string.cm_def_period), def.period},
                {getString(R.string.cm_def_status), def.status},
                {getString(R.string.cm_def_measured), def.measured},
                {getString(R.string.cm_def_direction), def.direction},
                {getString(R.string.cm_def_source), def.source},
        };
        for (int i = 0; i < lines.length; i++) {
            if (lines[i][1] == null || lines[i][1].isEmpty()) continue;
            if (!first) facts.addView(divider());
            first = false;
            LinearLayout row = hRow();
            row.setPadding(0, dp(11), 0, dp(11));
            TextView k = text(lines[i][0], R.color.metric_label, 12f, NORMAL);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(k);
            // The reading and its status carry the figure's own colour; the rest stay neutral.
            boolean toned = i == 0 || i == 3;
            TextView v = text(lines[i][1], toned ? 0 : R.color.text, 13f, toned ? BOLD : SEMI);
            if (toned) v.setTextColor(i == 0 ? color(R.color.text) : tone(def.tone));
            v.setGravity(Gravity.END);
            v.setMaxLines(2);
            v.setPadding(dp(12), 0, 0, 0);
            v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
            row.addView(v);
            facts.addView(row);
        }
        if (!first) root.addView(facts);

        TextView ask = button(getString(R.string.cm_ask), R.color.on_primary, rounded(color(R.color.primary), 12), 44);
        ask.setTextSize(14f);
        ((LinearLayout.LayoutParams) ask.getLayoutParams()).weight = 0;
        ask.getLayoutParams().width = LinearLayout.LayoutParams.MATCH_PARENT;
        ((LinearLayout.LayoutParams) ask.getLayoutParams()).topMargin = dp(16);
        ask.setOnClickListener(x -> {
            dialog.dismiss();
            startActivity(new Intent(requireContext(), AskOrphosActivity.class));
        });
        root.addView(ask);

        dialog.setContentView(root);
        dialog.setOnShowListener(x -> {
            View host = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) host.setBackgroundColor(Color.TRANSPARENT);
        });
        dialog.show();
    }

    // --- quick-tile sheet ---

    private void openSheet(String key, CompanyMetrics.Sheet sheet) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        TileStyle s = TileStyle.of(key);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet_top);
        root.setPadding(dp(16), dp(10), dp(16), dp(34));

        View handle = new View(requireContext());
        handle.setBackground(rounded(Color.parseColor("#2A3550"), 2));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(14);
        handle.setLayoutParams(hlp);
        root.addView(handle);

        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconBox(s, 34, 18));
        LinearLayout titles = new LinearLayout(requireContext());
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titles.addView(text(key, R.color.text, 18f, BOLD));
        if (sheet.subtitle != null) titles.addView(text(sheet.subtitle, R.color.metric_label, 12f, NORMAL));
        head.addView(titles);
        TextView close = text("×", R.color.text_secondary, 18f, NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(getString(R.string.cm_close));
        close.setBackground(outlined(color(R.color.bg), color(R.color.border), 10));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        close.setOnClickListener(x -> dialog.dismiss());
        head.addView(close);
        root.addView(head);

        LinearLayout rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, 0);
        for (CompanyMetrics.Row r : sheet.rows) {
            LinearLayout row = hRow();
            row.setPadding(0, dp(13), 0, dp(13));
            LinearLayout left = new LinearLayout(requireContext());
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            left.addView(text(r.label, R.color.text, 14f, SEMI));
            if (r.note != null) {
                TextView n = text(r.note, R.color.text_muted, 11f, NORMAL);
                n.setPadding(0, dp(3), 0, 0);
                left.addView(n);
            }
            row.addView(left);
            TextView v = text(r.value, 0, 12f, BOLD);
            v.setTextColor(tone(r.tone));
            v.setPadding(dp(12), 0, 0, 0);
            row.addView(v);
            rows.addView(row);
            rows.addView(divider());
        }
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(requireContext());
        scroll.addView(rows);
        root.addView(scroll);

        TextView cta = button(sheet.cta, R.color.on_primary, rounded(color(R.color.primary), 12), 44);
        cta.setTextSize(14f);
        ((LinearLayout.LayoutParams) cta.getLayoutParams()).weight = 0;
        cta.getLayoutParams().width = LinearLayout.LayoutParams.MATCH_PARENT;
        ((LinearLayout.LayoutParams) cta.getLayoutParams()).topMargin = dp(16);
        // Live sheets are real records, so every one of them can be taken to Ask Orphos.
        boolean asks = s == TileStyle.INSIGHTS || !sample;
        cta.setOnClickListener(x -> {
            dialog.dismiss();
            if (asks) startActivity(new Intent(requireContext(), AskOrphosActivity.class));
        });
        root.addView(cta);

        dialog.setContentView(root);
        dialog.setOnShowListener(x -> {
            View host = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) host.setBackgroundColor(Color.TRANSPARENT);
        });
        dialog.show();
    }

    /** Presentation of each quick tile (icon + accent), keyed by the tile label. */
    private enum TileStyle {
        OKRS(R.drawable.ic_metric_okr, "#8FB0FF", "#262F6BFF"),
        BOARD(R.drawable.ic_metric_board, "#C4A6FF", "#269D6CFF"),
        RISKS(R.drawable.ic_metric_risk, "#FF8A8B", "#24F05D5E"),
        INSIGHTS(R.drawable.ic_metric_insight, "#FCC766", "#24F5A524"),
        SETTINGS(R.drawable.ic_metric_settings, "#B9C3DA", "#1AB9C3DA");

        final int icon;
        final int color;
        final int tint;

        TileStyle(int icon, String color, String tint) {
            this.icon = icon;
            this.color = Color.parseColor(color);
            this.tint = Color.parseColor(tint);
        }

        static TileStyle of(String label) {
            switch (label.toLowerCase(Locale.ROOT)) {
                case "okrs":       return OKRS;
                case "board pack":
                case "projects":   return BOARD;
                case "risks":      return RISKS;
                case "settings":   return SETTINGS;
                default:           return INSIGHTS;
            }
        }
    }

    // --- building blocks ---

    private static final int NORMAL = 0, SEMI = 1, BOLD = 2;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @ColorInt
    private int color(int res) {
        return requireContext().getColor(res);
    }

    @ColorInt
    private static int withAlpha(@ColorInt int c, float alpha) {
        return (Math.round(alpha * 255) << 24) | (c & 0x00FFFFFF);
    }

    @ColorInt
    private int tone(CompanyMetrics.Tone t) {
        if (t == null) return color(R.color.text_secondary);
        switch (t) {
            case GOOD: return color(R.color.metric_good);
            case WARN: return color(R.color.orange);
            case BAD:  return color(R.color.red);
            case AI:   return color(R.color.metric_ai);
            default:   return color(R.color.metric_info);
        }
    }

    private GradientDrawable rounded(@ColorInt int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable outlined(@ColorInt int fill, @ColorInt int stroke, float radiusDp) {
        GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(CharSequence s, int colorRes, float sp, int weight) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        if (colorRes != 0) t.setTextColor(color(colorRes));
        t.setTextSize(sp);
        if (weight == SEMI) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        else if (weight == BOLD) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Bolds and recolours {@code part} inside {@code whole}. */
    private SpannableStringBuilder emphasise(String whole, String part, @ColorInt int c) {
        SpannableStringBuilder b = new SpannableStringBuilder(whole);
        int i = part == null ? -1 : whole.indexOf(part);
        if (i >= 0) {
            b.setSpan(new ForegroundColorSpan(c), i, i + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new StyleSpan(Typeface.BOLD), i, i + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private TextView button(String label, int fgRes, android.graphics.drawable.Drawable bg, int heightDp) {
        TextView b = text(label, fgRes, 13f, BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        b.setMaxLines(1);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
        return b;
    }

    private LinearLayout.LayoutParams block() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    /** A 20dp-radius card; {@code bgRes} 0 = the standard surface + hairline border. */
    private LinearLayout card(int bgRes, int padDp, int bottomPadDp) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        if (bgRes != 0) box.setBackgroundResource(bgRes);
        else box.setBackground(outlined(color(R.color.surface), color(R.color.border), 20));
        box.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(bottomPadDp));
        box.setLayoutParams(block());
        return box;
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View weightSpacer() {
        View s = new View(requireContext());
        s.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return s;
    }

    private View divider() {
        View d = new View(requireContext());
        d.setBackgroundColor(color(R.color.metric_divider));
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return d;
    }

    private View iconBox(TileStyle s, int boxDp, int iconDp) {
        FrameLayout box = new FrameLayout(requireContext());
        box.setBackground(rounded(s.tint, 10));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
        ImageView ic = new ImageView(requireContext());
        ic.setImageResource(s.icon);
        ic.setColorFilter(s.color);
        ic.setLayoutParams(new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        box.addView(ic);
        return box;
    }

    /**
     * A rounded progress bar filled to {@code pct}, with a thin marker at {@code markerPct}
     * (the "expected by now" point). Both are laid out with weights, so no measuring needed.
     */
    private View progressBar(int pct, android.graphics.drawable.Drawable fillBg, int barDp,
                             int markerPct, int markerDp, @ColorInt int markerColor) {
        int p = Math.max(0, Math.min(100, pct));
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(Math.max(barDp, markerDp))));

        LinearLayout track = new LinearLayout(requireContext());
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(Color.parseColor("#1C2640"), 99));
        track.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(barDp), Gravity.CENTER_VERTICAL));
        View fill = new View(requireContext());
        fill.setBackground(fillBg);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p));
        View rest = new View(requireContext());
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100 - p));
        track.addView(fill);
        track.addView(rest);
        frame.addView(track);

        if (markerPct > 0 && markerPct < 100) {
            LinearLayout overlay = new LinearLayout(requireContext());
            overlay.setOrientation(LinearLayout.HORIZONTAL);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            View before = new View(requireContext());
            before.setLayoutParams(new LinearLayout.LayoutParams(0, 1, markerPct));
            View marker = new View(requireContext());
            marker.setBackground(rounded(markerColor, 1));
            marker.setLayoutParams(new LinearLayout.LayoutParams(dp(2), dp(markerDp)));
            View after = new View(requireContext());
            after.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 100 - markerPct));
            overlay.addView(before);
            overlay.addView(marker);
            overlay.addView(after);
            frame.addView(overlay);
        }
        return frame;
    }
}
