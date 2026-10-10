package com.orphos.mobile.ui.metrics;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.role.Role;
import com.orphos.mobile.data.model.RoleDashboard;
import com.orphos.mobile.data.model.RoleDashboard.Block;
import com.orphos.mobile.data.model.RoleDashboard.Element;
import com.orphos.mobile.data.model.RoleDashboard.Tone;
import com.orphos.mobile.ui.common.HeaderActions;

import java.util.List;

/**
 * Renders a {@link RoleDashboard} as a role's work tab (the CHRO's People screen today):
 * scrolling section tabs, quick-action tiles on the first tab that open a detail view in
 * place, and a stack of cards per tab. Nothing is role-specific here; the data decides.
 */
public class RoleDashboardFragment extends Fragment {

    private HeaderActions header;
    private RoleDashboard data;
    private boolean sample;
    private int tab;
    /** Open quick action on the first tab, or null for the dashboard itself. */
    @Nullable private RoleDashboard.Action action;

    @Override
    public void onResume() {
        super.onResume();
        header.refreshStatus();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_role_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        v.findViewById(R.id.rd_back).setOnClickListener(x -> {
            if (getActivity() instanceof MainActivity m) m.selectTab(R.id.nav_home);
        });
        header = HeaderActions.bind(this, v, v.findViewById(R.id.rd_search));

        Role role = Role.of(OrphosApp.get().config().userRole());
        ((TextView) v.findViewById(R.id.rd_title)).setText(role.workTitleRes());

        OrphosApp.get().repo().roleDashboard(role.name(), (d, fromMock) -> {
            if (!isAdded() || getView() == null) return;
            TextView status = getView().findViewById(R.id.rd_status);
            if (d == null || d.isEmpty()) {
                // Reached the server but the board is empty ≠ couldn't reach it.
                status.setText(d != null && !fromMock ? R.string.cm_board_empty : R.string.data_unavailable);
                return;
            }
            data = d;
            sample = fromMock;
            status.setVisibility(View.GONE);
            if (d.title != null) ((TextView) getView().findViewById(R.id.rd_title)).setText(d.title);
            if (d.searchHint != null) ((TextView) getView().findViewById(R.id.search_input)).setHint(d.searchHint);
            renderTabs();
            renderContent();
        });
    }

    // --- tabs ---

    private void renderTabs() {
        LinearLayout tabs = requireView().findViewById(R.id.rd_tabs);
        tabs.removeAllViews();
        for (int i = 0; i < data.tabs.size(); i++) {
            int index = i;
            boolean active = i == tab;
            TextView t = text(data.tabs.get(i).label, active ? R.color.on_primary : R.color.text_secondary, 14f, SEMI);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setBackground(active ? rounded(color(R.color.primary), 18)
                    : outlined(Color.TRANSPARENT, Color.parseColor("#2A3854"), 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            if (i > 0) lp.leftMargin = dp(8);
            t.setLayoutParams(lp);
            t.setOnClickListener(x -> {
                tab = index;
                action = null;
                renderTabs();
                renderContent();
            });
            tabs.addView(t);
        }
    }

    // --- content ---

    private void renderContent() {
        LinearLayout content = requireView().findViewById(R.id.rd_content);
        content.removeAllViews();
        if (action != null) {
            renderAction(content, action);
        } else {
            if (tab == 0 && !data.actions.isEmpty()) content.addView(actionTiles());
            for (Block b : data.tabs.get(tab).blocks) content.addView(block(b));
        }
        if (sample) {
            TextView note = text(getString(R.string.cm_sample_note), R.color.text_muted, 11f, NORMAL);
            note.setGravity(Gravity.CENTER);
            note.setLayoutParams(gap(12));
            content.addView(note);
        }
        NestedScrollView scroll = requireView().findViewById(R.id.rd_scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private View actionTiles() {
        LinearLayout row = hRow();
        row.setLayoutParams(gap(12));
        for (int i = 0; i < data.actions.size(); i++) {
            RoleDashboard.Action a = data.actions.get(i);
            LinearLayout tile = new LinearLayout(requireContext());
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(2), 0, dp(2), 0);
            tile.setBackgroundResource(R.drawable.bg_metric_tile);
            tile.setClickable(true);
            tile.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            tile.setLayoutParams(lp);
            ImageView ic = icon(a.icon, 18, color(R.color.text));
            tile.addView(ic);
            TextView l = text(a.label, 0, 10f, NORMAL);
            l.setTextColor(Color.parseColor("#C9D1E0"));
            l.setGravity(Gravity.CENTER);
            l.setMaxLines(2);
            l.setLineSpacing(0f, 1.05f);
            l.setPadding(0, dp(4), 0, 0);
            tile.addView(l);
            tile.setOnClickListener(x -> {
                action = a;
                renderContent();
            });
            row.addView(tile);
        }
        return row;
    }

    private void renderAction(LinearLayout content, RoleDashboard.Action a) {
        TextView back = text("‹  " + getString(R.string.rd_back), R.color.link, 13f, SEMI);
        back.setPadding(0, dp(4), dp(12), dp(4));
        back.setLayoutParams(gap(12));
        back.setOnClickListener(x -> {
            action = null;
            renderContent();
        });
        content.addView(back);

        LinearLayout head = hRow();
        head.setLayoutParams(gap(12));
        FrameLayout box = new FrameLayout(requireContext());
        box.setBackground(outlined(Color.parseColor("#0F1A30"), color(R.color.primary), 10));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView ic = icon(a.icon, 22, color(R.color.text));
        ic.setLayoutParams(new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        box.addView(ic);
        head.addView(box);
        LinearLayout titles = vBox();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text(a.label, R.color.text, 18f, BOLD));
        if (a.sub != null) titles.addView(text(a.sub, R.color.text_secondary, 12f, NORMAL));
        head.addView(titles);
        content.addView(head);

        if (!a.stats.isEmpty()) {
            Block stats = Block.statCards(a.stats.size(), a.stats.toArray(new RoleDashboard.Stat[0]));
            content.addView(block(stats));
        }
        if (!a.rows.isEmpty()) {
            LinearLayout card = cardBox(false);
            card.setPadding(dp(16), dp(6), dp(16), dp(6));
            for (int i = 0; i < a.rows.size(); i++) {
                RoleDashboard.ActionRow r = a.rows.get(i);
                if (i > 0) card.addView(divider());
                LinearLayout row = hRow();
                row.setPadding(0, dp(12), 0, dp(12));
                LinearLayout left = weighted(vBox());
                left.addView(text(r.title, R.color.text, 14f, SEMI));
                if (r.meta != null) left.addView(text(r.meta, R.color.text_secondary, 12f, NORMAL));
                row.addView(left);
                row.addView(pill(r.status, r.tone));
                card.addView(row);
            }
            content.addView(card);
        }
        if (a.cta != null) {
            TextView cta = text(a.cta, R.color.on_primary, 14f, SEMI);
            cta.setGravity(Gravity.CENTER);
            cta.setBackground(rounded(color(R.color.primary), 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.topMargin = dp(12);
            cta.setLayoutParams(lp);
            cta.setOnClickListener(x ->
                    Toast.makeText(requireContext(), R.string.rd_not_on_mobile, Toast.LENGTH_SHORT).show());
            content.addView(cta);
        }
    }

    // --- blocks ---

    private View block(Block b) {
        switch (b.kind) {
            case STAT_CARDS: return statCards(b);
            case ALERT:      return alert(b);
            default:         return card(b);
        }
    }

    private View card(Block b) {
        LinearLayout box = cardBox(b.highlight);
        if (b.title != null) {
            LinearLayout head = hRow();
            TextView t = b.highlight
                    ? text(b.title, 0, 14f, NORMAL)
                    : text(b.title, R.color.text, 16f, BOLD);
            if (b.highlight) t.setTextColor(Color.parseColor("#C9D1E0"));
            head.addView(t);
            if (b.live) head.addView(liveMark());
            head.addView(spacer());
            if (b.trailing != null) {
                head.addView(b.trailingPill ? pill(b.trailing, b.trailingTone)
                        : text(b.trailing, 0, 12f, NORMAL, tone(b.trailingTone)));
            }
            box.addView(head);
        }
        Element prev = null;
        for (Element e : b.elements) {
            View v = element(e);
            // Rows sit tight under one another; everything else gets the card's 10dp rhythm.
            boolean tight = prev != null && isRowish(prev) && isRowish(e);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            if (lp == null) lp = full();
            lp.topMargin = (box.getChildCount() == 0) ? 0 : dp(tight ? 0 : 10);
            v.setLayoutParams(lp);
            box.addView(v);
            prev = e;
        }
        return box;
    }

    private static boolean isRowish(Element e) {
        return e.kind == Element.Kind.ROW || e.kind == Element.Kind.TABLE_ROW;
    }

    private View statCards(Block b) {
        LinearLayout grid = vBox();
        grid.setLayoutParams(gap(12));
        LinearLayout row = null;
        for (int i = 0; i < b.stats.size(); i++) {
            if (i % b.columns == 0) {
                row = hRow();
                row.setGravity(Gravity.NO_GRAVITY);
                LinearLayout.LayoutParams rlp = full();
                if (i > 0) rlp.topMargin = dp(8);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            RoleDashboard.Stat s = b.stats.get(i);
            LinearLayout c = vBox();
            c.setBackground(outlined(color(R.color.surface), color(R.color.border), 14));
            c.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i % b.columns > 0) lp.leftMargin = dp(8);
            c.setLayoutParams(lp);
            c.addView(text(s.key, R.color.text_secondary, 12f, NORMAL));
            c.addView(text(s.value, 0, 22f, BOLD, tone(s.tone)));
            if (s.sub != null) c.addView(text(s.sub, 0, 11f, NORMAL, tone(s.subTone)));
            row.addView(c);
        }
        // Pad a short last row so its cards keep the column width.
        if (row != null) {
            int fill = (b.columns - b.stats.size() % b.columns) % b.columns;
            for (int i = 0; i < fill; i++) {
                View pad = new View(requireContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = dp(8);
                pad.setLayoutParams(lp);
                row.addView(pad);
            }
        }
        return grid;
    }

    private View alert(Block b) {
        boolean ai = b.alertStyle == Block.AlertStyle.AI;
        int accent = ai ? Color.parseColor("#A78BFA") : Color.parseColor("#F87171");
        LinearLayout box = hRow();
        box.setGravity(Gravity.TOP);
        box.setBackground(outlined(Color.parseColor(ai ? "#231A40" : "#2A1416"),
                Color.parseColor(ai ? "#4B3A7A" : "#5A2A2A"), 16));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setLayoutParams(gap(12));
        if (ai) {
            TextView bang = text("!", R.color.text, 14f, BOLD);
            bang.setGravity(Gravity.CENTER);
            bang.setBackground(rounded(Color.parseColor("#140F26"), 8));
            bang.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
            box.addView(bang);
        } else {
            ImageView ic = icon("report", 22, accent);
            box.addView(ic);
        }
        LinearLayout body = weighted(vBox());
        body.setPadding(dp(12), 0, 0, 0);
        TextView k = text(b.kicker, 0, 10f, BOLD, accent);
        k.setLetterSpacing(0.1f);
        body.addView(k);
        TextView t = text(b.body, R.color.text, 13f, NORMAL);
        t.setLineSpacing(0f, 1.3f);
        t.setPadding(0, dp(4), 0, 0);
        t.setEllipsize(null);
        body.addView(t);
        box.addView(body);
        return box;
    }

    // --- elements ---

    private View element(Element e) {
        switch (e.kind) {
            case BIG_VALUE: return bigValue(e);
            case METER:     return meter(e);
            case PROGRESS:  return progress(e);
            case SEGMENTS:  return segments(e);
            case TILES:     return tiles(e);
            case TABLE_ROW: return tableRow(e);
            case COLUMNS:   return columns(e);
            case CAPTION: {
                TextView t = text(e.label, R.color.text_muted, 11f, NORMAL);
                t.setEllipsize(null);
                t.setLayoutParams(full());
                return t;
            }
            default:        return row(e);
        }
    }

    private View bigValue(Element e) {
        LinearLayout row = hRow();
        row.setGravity(Gravity.BOTTOM);
        TextView v = text(e.value, R.color.text, e.size, BOLD);
        v.setIncludeFontPadding(false);
        row.addView(v);
        if (e.note != null) {
            TextView n = text(e.note, 0, e.size >= 30 ? 14f : 13f, SEMI, tone(e.tone));
            n.setPadding(dp(8), 0, 0, dp(2));
            row.addView(n);
        }
        return row;
    }

    private View row(Element e) {
        LinearLayout wrap = vBox();
        wrap.setLayoutParams(full());
        if (e.divider) wrap.addView(divider());
        LinearLayout row = hRow();
        row.setPadding(0, e.divider ? dp(10) : dp(e.caps ? 12 : 4), 0, e.caps ? dp(12) : dp(4));
        LinearLayout left = weighted(hRow());
        TextView l = text(e.label, 0, e.caps ? 12f : 13f, NORMAL);
        l.setTextColor(Color.parseColor("#C9D1E0"));
        if (e.caps) l.setLetterSpacing(0.03f);
        l.setMaxLines(2);
        left.addView(l);
        if (e.tag != null) {
            TextView tag = pill(e.tag, e.tagTone);
            tag.setTextSize(10f);
            ((LinearLayout.LayoutParams) tag.getLayoutParams()).leftMargin = dp(8);
            left.addView(tag);
        }
        row.addView(left);
        if (e.value != null) {
            if (e.pill) {
                row.addView(pill(e.value, e.valueTone));
            } else {
                TextView v = text(e.value, 0, 13f, e.bold ? BOLD : SEMI, tone(e.valueTone));
                v.setPadding(dp(12), 0, 0, 0);
                row.addView(v);
            }
        }
        wrap.addView(row);
        return wrap;
    }

    private View meter(Element e) {
        LinearLayout box = vBox();
        box.setLayoutParams(full());
        LinearLayout head = hRow();
        TextView l = weighted(text(e.label, 0, 13f, NORMAL));
        l.setTextColor(Color.parseColor("#C9D1E0"));
        head.addView(l);
        head.addView(text(e.value, 0, 13f, SEMI, tone(e.valueTone)));
        box.addView(head);
        View bar = bar(e.pct, tone(e.tone), 6, -1);
        ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin = dp(6);
        box.addView(bar);
        return box;
    }

    private View progress(Element e) {
        LinearLayout box = vBox();
        box.setLayoutParams(full());
        box.addView(bar(e.pct, barTone(e.tone), 8, e.marker));
        if (e.captionLeft != null || e.captionRight != null) {
            LinearLayout caps = hRow();
            LinearLayout.LayoutParams clp = full();
            clp.topMargin = dp(8);
            caps.setLayoutParams(clp);
            caps.addView(weighted(text(e.captionLeft == null ? "" : e.captionLeft, R.color.text_secondary, 12f, NORMAL)));
            SpannableStringBuilder right = new SpannableStringBuilder(e.captionRight == null ? "" : e.captionRight);
            if (e.captionAccent != null) {
                int s = right.length();
                right.append(e.captionAccent);
                right.setSpan(new ForegroundColorSpan(tone(Tone.WARN)), s, right.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            caps.addView(text(right, R.color.text_secondary, 12f, NORMAL));
            box.addView(caps);
        }
        return box;
    }

    private View segments(Element e) {
        LinearLayout box = vBox();
        box.setLayoutParams(full());
        LinearLayout strip = new LinearLayout(requireContext());
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        for (int i = 0; i < e.segments.size(); i++) {
            RoleDashboard.Segment s = e.segments.get(i);
            View seg = new View(requireContext());
            seg.setBackground(roundedEnds(barTone(s.tone), i == 0, i == e.segments.size() - 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, s.pct));
            if (i > 0) lp.leftMargin = dp(2);
            seg.setLayoutParams(lp);
            strip.addView(seg);
        }
        box.addView(strip);
        if (!e.legend.isEmpty()) {
            LinearLayout legend = hRow();
            LinearLayout.LayoutParams llp = full();
            llp.topMargin = dp(8);
            legend.setLayoutParams(llp);
            for (int i = 0; i < e.legend.size(); i++) {
                TextView t = weighted(text(e.legend.get(i), 0, 12f, NORMAL));
                t.setTextColor(Color.parseColor("#C9D1E0"));
                t.setGravity(i == 0 ? Gravity.START
                        : i == e.legend.size() - 1 ? Gravity.END : Gravity.CENTER_HORIZONTAL);
                legend.addView(t);
            }
            box.addView(legend);
        }
        return box;
    }

    private View tiles(Element e) {
        LinearLayout grid = vBox();
        grid.setLayoutParams(full());
        LinearLayout row = null;
        for (int i = 0; i < e.tiles.size(); i++) {
            if (i % e.columns == 0) {
                row = hRow();
                row.setGravity(Gravity.NO_GRAVITY);
                LinearLayout.LayoutParams rlp = full();
                if (i > 0) rlp.topMargin = dp(8);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            RoleDashboard.Stat s = e.tiles.get(i);
            LinearLayout t = vBox();
            t.setBackground(rounded(Color.parseColor("#0B1220"), 10));
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i % e.columns > 0) lp.leftMargin = dp(8);
            t.setLayoutParams(lp);
            t.addView(text(s.key, R.color.text_secondary, 11f, NORMAL));
            t.addView(text(s.value, 0, 15f, BOLD, tone(s.tone)));
            row.addView(t);
        }
        if (row != null) {
            int fill = (e.columns - e.tiles.size() % e.columns) % e.columns;
            for (int i = 0; i < fill; i++) {
                View pad = new View(requireContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = dp(8);
                pad.setLayoutParams(lp);
                row.addView(pad);
            }
        }
        return grid;
    }

    private View tableRow(Element e) {
        LinearLayout wrap = vBox();
        wrap.setLayoutParams(full());
        if (e.divider) wrap.addView(divider());
        LinearLayout row = hRow();
        row.setPadding(0, dp(10), 0, dp(10));
        List<String> c = e.cells;
        TextView name = weighted(text(c.size() > 0 ? c.get(0) : "", 0, 13f, NORMAL));
        name.setTextColor(e.header ? tone(Tone.MUTED) : Color.parseColor("#C9D1E0"));
        row.addView(name);
        TextView plan = text(c.size() > 1 ? c.get(1) : "", R.color.text_secondary, 12f, NORMAL);
        plan.setGravity(Gravity.END);
        plan.setPadding(dp(12), 0, 0, 0);
        row.addView(plan);
        TextView actual = text(c.size() > 2 ? c.get(2) : "", 0, 13f, e.header ? NORMAL : BOLD, tone(e.valueTone));
        actual.setGravity(Gravity.END);
        actual.setMinWidth(dp(56));
        row.addView(actual);
        wrap.addView(row);
        return wrap;
    }

    private View columns(Element e) {
        LinearLayout row = hRow();
        row.setGravity(Gravity.BOTTOM);
        row.setLayoutParams(full());
        float max = 0;
        for (RoleDashboard.Bar b : e.bars) max = Math.max(max, b.value);
        int chart = 64;
        for (int i = 0; i < e.bars.size(); i++) {
            RoleDashboard.Bar b = e.bars.get(i);
            boolean last = i == e.bars.size() - 1;
            LinearLayout col = vBox();
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            col.setLayoutParams(lp);
            View bar = new View(requireContext());
            GradientDrawable g = new GradientDrawable();
            g.setColor(last ? tone(e.tone) : Color.parseColor("#2A3854"));
            float r = dp(4);
            g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            bar.setBackground(g);
            int h = max > 0 ? Math.round(chart * b.value / max) : 0;
            bar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(Math.max(2, h))));
            col.addView(bar);
            TextView l = text(b.label, R.color.text_muted, 10f, NORMAL);
            l.setPadding(0, dp(4), 0, 0);
            col.addView(l);
            row.addView(col);
        }
        return row;
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
    private int tone(Tone t) {
        if (t == null) return color(R.color.text);
        switch (t) {
            case GOOD:  return color(R.color.metric_good);
            case WARN:  return Color.parseColor("#F59E0B");
            case BAD:   return Color.parseColor("#F87171");
            case INFO:  return Color.parseColor("#5B8CFF");
            case AI:    return Color.parseColor("#A78BFA");
            case MUTED: return Color.parseColor("#8A94A8");
            default:    return color(R.color.text);
        }
    }

    /** Bars use a slightly deeper blue than blue text. */
    @ColorInt
    private int barTone(Tone t) {
        return t == Tone.INFO ? Color.parseColor("#3B82F6") : tone(t);
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

    private GradientDrawable roundedEnds(@ColorInt int fill, boolean start, boolean end) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float s = start ? dp(5) : 0, e = end ? dp(5) : 0;
        g.setCornerRadii(new float[]{s, s, e, e, e, e, s, s});
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

    private TextView text(CharSequence s, int colorRes, float sp, int weight, @ColorInt int c) {
        TextView t = text(s, colorRes, sp, weight);
        t.setTextColor(c);
        return t;
    }

    private TextView pill(String label, Tone t) {
        int c = tone(t);
        TextView p = text(label, 0, 11f, SEMI, c);
        p.setMaxLines(1);
        p.setPadding(dp(9), dp(3), dp(9), dp(3));
        p.setBackground(rounded((0x29 << 24) | (c & 0x00FFFFFF), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        p.setLayoutParams(lp);
        return p;
    }

    private View liveMark() {
        LinearLayout m = hRow();
        m.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        m.setPadding(dp(8), 0, 0, 0);
        View dot = new View(requireContext());
        dot.setBackground(rounded(color(R.color.metric_good), 3));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        m.addView(dot);
        TextView t = text(getString(R.string.rd_live), R.color.metric_good, 12f, NORMAL);
        t.setPadding(dp(4), 0, 0, 0);
        m.addView(t);
        return m;
    }

    private ImageView icon(String key, int sizeDp, @ColorInt int c) {
        ImageView ic = new ImageView(requireContext());
        ic.setImageResource(iconRes(key));
        ic.setColorFilter(c);
        ic.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return ic;
    }

    private static int iconRes(String key) {
        if (key == null) return R.drawable.ic_bulb;
        switch (key) {
            case "person_add":    return R.drawable.ic_person_add;
            case "star":          return R.drawable.ic_star_outline;
            case "event":         return R.drawable.ic_event;
            case "verified_user": return R.drawable.ic_verified_user;
            case "settings":      return R.drawable.ic_settings;
            case "report":        return R.drawable.ic_report;
            case "people":        return R.drawable.ic_people;
            default:              return R.drawable.ic_bulb;
        }
    }

    private LinearLayout cardBox(boolean highlight) {
        LinearLayout box = vBox();
        if (highlight) {
            GradientDrawable g = rounded(Color.parseColor("#0F1A30"), 16);
            g.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density), color(R.color.primary));
            box.setBackground(g);
        } else {
            box.setBackground(outlined(color(R.color.surface), color(R.color.border), 16));
        }
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setLayoutParams(gap(12));
        return box;
    }

    private LinearLayout vBox() {
        LinearLayout l = new LinearLayout(requireContext());
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(full());
        return row;
    }

    private <T extends View> T weighted(T v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private View spacer() {
        return weighted(new View(requireContext()));
    }

    private View divider() {
        View d = new View(requireContext());
        d.setBackgroundColor(Color.parseColor("#1E2A40"));
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return d;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(topDp);
        return lp;
    }

    /**
     * A rounded bar filled to {@code pct} with an optional light marker at {@code marker},
     * laid out with weights so no measuring is needed.
     */
    private View bar(int pct, @ColorInt int fillColor, int heightDp, int marker) {
        int p = Math.max(0, Math.min(100, pct));
        boolean hasMarker = marker > 0 && marker < 100;
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(hasMarker ? heightDp + 6 : heightDp)));
        LinearLayout track = new LinearLayout(requireContext());
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(Color.parseColor("#1E2A40"), 99));
        track.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp), Gravity.CENTER_VERTICAL));
        View fill = new View(requireContext());
        fill.setBackground(rounded(fillColor, 99));
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p));
        View rest = new View(requireContext());
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100 - p));
        track.addView(fill);
        track.addView(rest);
        frame.addView(track);
        if (hasMarker) {
            LinearLayout overlay = new LinearLayout(requireContext());
            overlay.setOrientation(LinearLayout.HORIZONTAL);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            View before = new View(requireContext());
            before.setLayoutParams(new LinearLayout.LayoutParams(0, 1, marker));
            View m = new View(requireContext());
            m.setBackground(rounded(color(R.color.text), 1));
            m.setLayoutParams(new LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT));
            View after = new View(requireContext());
            after.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 100 - marker));
            overlay.addView(before);
            overlay.addView(m);
            overlay.addView(after);
            frame.addView(overlay);
        }
        return frame;
    }
}
