package com.orphos.mobile.ui.scribbles;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;

import java.util.List;

/**
 * Notes ▸ AI Insights detail. Opened from one of the four tiles on the Notes screen, it lists
 * exactly what that tile counts ({@link NoteInsights}, this week's captures): the notes, or
 * the action items, decisions or AI insights found in them. A pill row switches tiles in
 * place, and tapping a row opens the note it came from.
 */
public class NoteInsightsActivity extends AppCompatActivity {

    /** Which tile was tapped; one of the CATEGORY_* constants. */
    public static final String EXTRA_CATEGORY = "com.orphos.mobile.extra.INSIGHT_CATEGORY";

    public static final int CATEGORY_NOTES = 0;
    public static final int CATEGORY_ACTIONS = 1;
    public static final int CATEGORY_DECISIONS = 2;
    public static final int CATEGORY_INSIGHTS = 3;

    public static Intent intent(Context ctx, int category) {
        return new Intent(ctx, NoteInsightsActivity.class).putExtra(EXTRA_CATEGORY, category);
    }

    private static final int[] TAB_LABELS = {
            R.string.scr_stat_notes, R.string.scr_stat_actions,
            R.string.scr_stat_decisions, R.string.scr_stat_insights};
    private static final int[] EMPTY = {
            R.string.ni_empty_notes, R.string.ni_empty_actions,
            R.string.ni_empty_decisions, R.string.ni_empty_insights};
    /** Glyph and accent per category, matching the tiles on the Notes screen. */
    private static final int[] GLYPH = {
            R.drawable.ic_note, R.drawable.ic_check_circle, R.drawable.ic_help, R.drawable.ic_bulb};
    private static final int[] ACCENT = {R.color.link, R.color.green, R.color.purple, R.color.orange};
    private static final int[] WASH = {
            R.color.wash_blue, R.color.wash_green, R.color.wash_purple, R.color.wash_orange};

    private int category;
    private NoteInsights insights;
    private boolean unreachable;

    private LinearLayout tabs;
    private LinearLayout container;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_note_insights);
        density = getResources().getDisplayMetrics().density;

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ni_root), (v, in) -> {
            Insets bars = in.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        category = savedInstanceState != null
                ? savedInstanceState.getInt(EXTRA_CATEGORY, CATEGORY_NOTES)
                : getIntent().getIntExtra(EXTRA_CATEGORY, CATEGORY_NOTES);

        findViewById(R.id.ni_back).setOnClickListener(v -> finish());
        tabs = findViewById(R.id.ni_tabs);
        container = findViewById(R.id.ni_container);
        renderTabs();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_CATEGORY, category);
    }

    /** Re-read on every visit: a note edited or deleted from its summary changes the lists. */
    @Override
    protected void onResume() {
        super.onResume();
        OrphosApp.get().repo().captures((data, fromMock) -> {
            if (isFinishing()) return;
            unreachable = fromMock && !OrphosApp.get().config().isDemoMode();
            insights = NoteInsights.of(data);
            renderTabs();
            renderList();
        });
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private void renderTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int cat = i;
            boolean selected = cat == category;
            TextView pill = new TextView(this);
            String label = getString(TAB_LABELS[cat]);
            pill.setText(insights == null ? label : label + "  " + count(cat));
            pill.setTextSize(13f);
            pill.setTextColor(ContextCompat.getColor(this, selected ? R.color.on_primary : R.color.text_secondary));
            pill.setBackgroundResource(selected ? R.drawable.pill_active : R.drawable.pill_inactive);
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setOnClickListener(v -> {
                if (category == cat) return;
                category = cat;
                renderTabs();
                renderList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            tabs.addView(pill, lp);
        }
    }

    private int count(int cat) {
        switch (cat) {
            case CATEGORY_ACTIONS:   return insights.actions;
            case CATEGORY_DECISIONS: return insights.decisions;
            case CATEGORY_INSIGHTS:  return insights.insights;
            default:                 return insights.notes;
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private void renderList() {
        container.removeAllViews();
        if (insights == null) return;
        if (category == CATEGORY_NOTES) {
            if (insights.noteList.isEmpty()) { showEmpty(); return; }
            LayoutInflater inflater = LayoutInflater.from(this);
            for (Capture c : insights.noteList) container.addView(noteRow(inflater, c));
            return;
        }
        List<NoteInsights.Item> items = category == CATEGORY_ACTIONS ? insights.actionItems
                : category == CATEGORY_DECISIONS ? insights.decisionItems : insights.insightItems;
        if (items.isEmpty()) { showEmpty(); return; }
        for (NoteInsights.Item item : items) container.addView(itemRow(item));
    }

    private void showEmpty() {
        TextView t = text(getString(unreachable ? R.string.data_unavailable : EMPTY[category]),
                14, R.color.text_muted, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(32), 0, 0);
        container.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** A note: the same row as Recent Captures. */
    private View noteRow(LayoutInflater inflater, Capture c) {
        View v = inflater.inflate(R.layout.item_capture, container, false);
        ((TextView) v.findViewById(R.id.capture_title)).setText(title(c));
        ((TextView) v.findViewById(R.id.capture_meta)).setText(
                CaptureAdapter.label(c.type) + " · " + (c.createdAt == null ? "" : c.createdAt));
        ((ImageView) v.findViewById(R.id.capture_icon)).setImageResource(CaptureAdapter.iconFor(c.type));
        v.setOnClickListener(x -> open(c));
        return v;
    }

    /** An action, decision or insight: the line itself, the note it came from, a chevron. */
    private View itemRow(NoteInsights.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(ContextCompat.getDrawable(this, ripple.resourceId));
        card.setOnClickListener(v -> open(item.source));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(GLYPH[category]);
        icon.setBackgroundResource(R.drawable.circle_icon);
        icon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, WASH[category])));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, ACCENT[category])));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(12);
        llp.rightMargin = dp(8);
        TextView line = text(item.text, 14, R.color.text, false);
        line.setLineSpacing(0f, 1.2f);
        col.addView(line);
        String from = getString(R.string.ni_from_fmt, title(item.source))
                + (item.source.createdAt != null && !item.source.createdAt.isEmpty() ? " · " + item.source.createdAt : "");
        TextView src = text(from, 12, R.color.text_muted, false);
        src.setPadding(0, dp(4), 0, 0);
        src.setMaxLines(1);   // a note named after its first sentence would otherwise run on
        src.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(src);
        card.addView(col, llp);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setColorFilter(ContextCompat.getColor(this, R.color.text_muted));
        card.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        return card;
    }

    private String title(Capture c) {
        return c.title != null && !c.title.trim().isEmpty() ? c.title : getString(R.string.ni_untitled);
    }

    private void open(Capture c) {
        if (c == null || c.id == null) return;
        startActivity(new Intent(this, CaptureSummaryActivity.class)
                .putExtra(CaptureSummaryActivity.EXTRA_ID, c.id));
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) { return Math.round(v * density); }
}
