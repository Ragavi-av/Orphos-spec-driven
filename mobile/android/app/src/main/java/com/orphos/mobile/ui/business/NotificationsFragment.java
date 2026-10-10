package com.orphos.mobile.ui.business;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.MailInsightDto;
import com.orphos.mobile.ui.common.HeaderState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Business ▸ Notifications — deliberately the SAME view the web dashboard shows.
 *
 * <p>Reads {@code /api/mail-insight/notifications}, the identical endpoint behind
 * the web {@code NotificationsPanel}, and groups by {@code criticality} into the
 * same three sections in the same order: Alerts, Warnings, Information. Each
 * section carries a count badge and its own empty line, so an empty section still
 * reads as "nothing here" rather than vanishing.</p>
 *
 * <p>This replaced a materially different screen. It used to derive its rows from
 * {@code /api/mobile/home}'s summary points — a different source with a different
 * shape ({@code severity}/{@code text} rather than {@code criticality}/{@code title}) —
 * and then filter to CRITICAL, with HIGH admitted only via a local
 * "alerts include high" preference. So the phone and the dashboard could disagree
 * about what a notification even was, and INFORMATION items were unreachable on
 * mobile entirely. Grouping by level shows everything without the filter, which is
 * what the web has always done.</p>
 */
public class NotificationsFragment extends Fragment {

    /** Section order and styling, mirroring LEVELS in NotificationsPanel.jsx. */
    private enum Level {
        ALERT      (R.string.notif_alerts,      R.color.red,    R.color.red_deep,    R.string.notif_no_alerts,        "ALERT"),
        WARNING    (R.string.notif_warnings,    R.color.orange, R.color.orange_deep, R.string.notif_no_warnings,      "WARNING"),
        INFORMATION(R.string.notif_information, R.color.link,   R.color.blue_deep,   R.string.notif_nothing_to_note,  "INFO");

        final int titleRes, colorRes, chipBgRes, emptyRes;
        /** Row badge text, matching the web chip: ALERT / WARNING / INFO. */
        final String badge;
        Level(int titleRes, int colorRes, int chipBgRes, int emptyRes, String badge) {
            this.titleRes = titleRes; this.colorRes = colorRes; this.chipBgRes = chipBgRes;
            this.emptyRes = emptyRes; this.badge = badge;
        }
    }

    private float density;
    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        density = getResources().getDisplayMetrics().density;
        View v = inflater.inflate(R.layout.fragment_biz_list, container, false);
        list = v.findViewById(R.id.biz_list);
        return v;
    }

    /**
     * Renders the app-wide feed the header bell counts from ({@link HeaderState}), so the
     * list and the bell badge on every screen are always the same set.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        OrphosApp.get().headerState().notifications().observe(getViewLifecycleOwner(), this::render);
    }

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    public void onResume() {
        super.onResume();
        // What is on screen now counts as read — clears the bell badge app-wide.
        OrphosApp.get().headerState().markNotificationsSeen();
        load();
    }

    private void load() {
        OrphosApp.get().headerState().refreshNotifications();
    }

    private void render(HeaderState.Notifications n) {
        if (!isAdded() || list == null || n == null) return;
        // Only while actually shown: the Business pager keeps neighbouring tabs started.
        if (isResumed()) OrphosApp.get().headerState().markNotificationsSeen();
        list.removeAllViews();

        List<MailInsightDto> all = n.items;
        // offline is the only way to tell a live feed from a stale one —
        // the rows look identical either way (see the decision log for the same trap).
        if (n.offline) list.addView(offlineNote());

        list.addView(screenHeader(all.size()));

        for (Level lvl : Level.values()) {
            List<MailInsightDto> inLevel = byLevel(all, lvl);
            list.addView(sectionHeader(lvl, inLevel.size()));
            if (inLevel.isEmpty()) {
                list.addView(emptyLine(lvl));
            } else {
                for (MailInsightDto d : inLevel) list.addView(card(d, lvl));
            }
        }
    }

    /**
     * Items whose criticality matches. Anything with a missing or unrecognised
     * criticality falls into INFORMATION rather than being dropped — the web panel
     * would likewise never show it under Alerts, and silently discarding a
     * notification is worse than filing it low.
     */
    private static List<MailInsightDto> byLevel(List<MailInsightDto> items, Level lvl) {
        List<MailInsightDto> out = new ArrayList<>();
        for (MailInsightDto d : items) {
            String c = d.criticality == null ? "" : d.criticality.toUpperCase(Locale.ROOT);
            boolean known = c.equals("ALERT") || c.equals("WARNING") || c.equals("INFORMATION");
            if (c.equals(lvl.name()) || (!known && lvl == Level.INFORMATION)) out.add(d);
        }
        return out;
    }

    /**
     * How many items land in the Alerts section. Home's Alerts stat uses this, so the two
     * can only ever show the same number.
     */
    public static int alertCount(List<MailInsightDto> items) {
        return items == null ? 0 : byLevel(items, Level.ALERT).size();
    }

    // ── Views ──────────────────────────────────────────────────────────────────

    /** Title + active count, mirroring the web panel's header strip. */
    private View screenHeader(int total) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(requireContext());
        title.setText(getString(R.string.notif_title));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.text));
        row.addView(title);

        TextView active = new TextView(requireContext());
        active.setText(getString(R.string.notif_active_fmt, total));
        active.setTextSize(10);
        active.setTypeface(active.getTypeface(), android.graphics.Typeface.BOLD);
        active.setTextColor(ContextCompat.getColor(requireContext(),
                total > 0 ? R.color.red : R.color.text_muted));
        active.setBackgroundResource(R.drawable.chip_soft);
        active.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        row.addView(active, alp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private View sectionHeader(Level lvl, int count) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Filled band, as the web draws each section head — without it the three
        // groups run together into one list and the grouping stops being legible.
        row.setBackgroundResource(R.drawable.card_bg_2);
        row.setPadding(dp(13), dp(9), dp(13), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        View dot = new View(requireContext());
        dot.setBackgroundResource(R.drawable.circle_icon);
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), lvl.colorRes)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.rightMargin = dp(10);
        row.addView(dot, dlp);

        TextView title = new TextView(requireContext());
        title.setText(getString(lvl.titleRes));
        title.setTextSize(13);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.text));
        row.addView(title);

        TextView badge = new TextView(requireContext());
        badge.setText(String.valueOf(count));
        badge.setTextSize(10);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setTextColor(ContextCompat.getColor(requireContext(),
                count > 0 ? lvl.colorRes : R.color.text_muted));
        badge.setBackgroundResource(R.drawable.chip_soft);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        row.addView(badge, blp);
        return row;
    }

    /**
     * One notification row, laid out like the web dashboard's: a coloured level chip in
     * a left gutter, then title, summary and the sender on the right. The chip is what
     * makes a row scannable out of its section — a card carrying only "Critical · Email"
     * read as metadata rather than as a severity.
     */
    private View card(MailInsightDto d, Level lvl) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        // Left gutter: the level chip.
        TextView chip = new TextView(requireContext());
        chip.setText(lvl.badge);
        chip.setTextSize(9);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setAllCaps(true);
        chip.setLetterSpacing(0.04f);
        chip.setTextColor(ContextCompat.getColor(requireContext(), lvl.colorRes));
        chip.setBackgroundResource(R.drawable.chip_soft);
        chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), lvl.chipBgRes)));
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(11);
        row.addView(chip, clp);

        // Right column: title, summary, sender.
        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(requireContext());
        title.setText(d.title != null ? d.title : "");
        title.setTextSize(13.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.text));
        title.setLineSpacing(0f, 1.2f);
        col.addView(title);

        if (d.summary != null && !d.summary.trim().isEmpty()) {
            TextView sum = new TextView(requireContext());
            sum.setText(d.summary.trim());
            sum.setTextSize(12);
            sum.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            sum.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(3);
            sum.setLayoutParams(slp);
            col.addView(sum);
        }

        // "From: …" — the web row's sender line. Falls back to the channel when the
        // item has no display name (data-source items have a source but no sender).
        String from = d.senderDisplay != null && !d.senderDisplay.trim().isEmpty()
                ? d.senderDisplay.trim()
                : (d.source != null ? cap(d.source.replace('_', ' ')) : null);
        if (from != null && !from.isEmpty()) {
            TextView f = new TextView(requireContext());
            f.setText(getString(R.string.notif_from_fmt, from));
            f.setTextSize(11);
            f.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(5);
            f.setLayoutParams(flp);
            col.addView(f);
        }

        row.addView(col);
        return row;
    }

    private View emptyLine(Level lvl) {
        TextView t = new TextView(requireContext());
        t.setText(getString(lvl.emptyRes));
        t.setTextSize(12);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private View offlineNote() {
        TextView t = new TextView(requireContext());
        t.setText(getString(R.string.notif_offline));
        t.setTextSize(12);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.orange));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private int dp(int v) { return Math.round(v * density); }
}
