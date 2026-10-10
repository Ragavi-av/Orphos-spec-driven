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
import com.orphos.mobile.data.model.TravelItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Business ▸ Travel — like the Meetings tab: the next trip's details at the top and the full
 * list of upcoming travels below. A calendar button (top-right) filters the list to trips in
 * the chosen month. Reached from the Business tabs and from Home's Next Travel card.
 */
public class TravelListFragment extends Fragment {

    private float density;
    private LinearLayout list;
    private final List<TravelItem> all = new ArrayList<>();
    /** Month abbreviation to filter by (e.g. "Sep"); null = show all. */
    private String filterMonth;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        density = getResources().getDisplayMetrics().density;
        View v = inflater.inflate(R.layout.fragment_biz_list, container, false);
        list = v.findViewById(R.id.biz_list);
        return v;
    }

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    public void onResume() {
        super.onResume();
        OrphosApp.get().repo().travel((trips, fromMock) -> {
            if (!isAdded() || list == null) return;
            all.clear();
            if (trips != null) all.addAll(trips);
            render();
        });
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();

        // Header: title + calendar filter button (week/month).
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.travel_upcoming), 18, R.color.text, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        android.widget.ImageView cal = new android.widget.ImageView(requireContext());
        cal.setImageResource(R.drawable.ic_calendar);
        cal.setColorFilter(ContextCompat.getColor(requireContext(), R.color.link));
        cal.setBackgroundResource(R.drawable.card_bg_2);
        cal.setPadding(dp(9), dp(9), dp(9), dp(9));
        cal.setContentDescription(getString(R.string.cal_pick_date));
        cal.setClickable(true);
        cal.setOnClickListener(v -> pickMonth());
        header.addView(cal, new LinearLayout.LayoutParams(dp(40), dp(40)));
        list.addView(header);

        // Filtered set.
        List<TravelItem> shown = new ArrayList<>();
        for (TravelItem t : all) {
            if (filterMonth == null || (t.date != null
                    && t.date.toLowerCase(Locale.ROOT).contains(filterMonth.toLowerCase(Locale.ROOT)))) {
                shown.add(t);
            }
        }

        // Scope chip when filtered.
        if (filterMonth != null) {
            TextView scope = text(getString(R.string.travel_in_month_fmt, filterMonth), 12, R.color.link, true);
            scope.setBackgroundResource(R.drawable.chip_soft);
            scope.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.blue_deep)));
            scope.setPadding(dp(10), dp(4), dp(10), dp(4));
            list.addView(marginTop(scope, 12));
        }

        if (shown.isEmpty()) { list.addView(empty()); return; }

        // Next trip — full detail card.
        list.addView(marginTop(detailCard(shown.get(0)), 14));

        // Full list of upcoming travels.
        list.addView(marginTop(text(getString(R.string.travel_next_list), 14, R.color.text_secondary, true), 20));
        for (int i = 0; i < shown.size(); i++) list.addView(marginTop(row(shown.get(i)), 10));
    }

    /** Detailed card for the next trip. */
    private View detailCard(TravelItem t) {
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_navigation);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary));
        top.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView route = text(t.route(), 18, R.color.text, true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(10);
        route.setLayoutParams(rlp);
        top.addView(route);
        top.addView(statusChip(t));
        c.addView(top);

        StringBuilder meta = new StringBuilder();
        if (t.mode != null && !t.mode.isEmpty()) meta.append(cap(t.mode));
        if (t.date != null && !t.date.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(t.date);
        if (t.time != null && !t.time.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(t.time);
        if (meta.length() > 0) c.addView(marginTop(text(meta.toString(), 13, R.color.text_secondary, false), 10));
        if (t.purpose != null && !t.purpose.isEmpty())
            c.addView(marginTop(text(t.purpose, 13, R.color.text_muted, false), 6));
        return c;
    }

    /** Compact row for the upcoming-travel list. */
    private View row(TravelItem t) {
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg_2);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(t.route(), 14, R.color.text, true));
        String sub = (t.date != null ? t.date : "") + (t.time != null && !t.time.isEmpty() ? " · " + t.time : "");
        if (!sub.isEmpty()) col.addView(marginTop(text(sub, 11, R.color.text_muted, false), 2));
        c.addView(col);
        if (t.mode != null && !t.mode.isEmpty()) {
            TextView mode = text(cap(t.mode), 10, R.color.link, true);
            mode.setAllCaps(true);
            mode.setBackgroundResource(R.drawable.chip_soft);
            mode.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.blue_deep)));
            mode.setPadding(dp(8), dp(3), dp(8), dp(3));
            c.addView(mode);
        }
        return c;
    }

    private TextView statusChip(TravelItem t) {
        boolean confirmed = t.confirmationStatus != null && t.confirmationStatus.toUpperCase(Locale.ROOT).contains("CONFIRM");
        TextView chip = text(confirmed ? "CONFIRMED" : "TENTATIVE", 10,
                confirmed ? R.color.green : R.color.orange, true);
        chip.setAllCaps(true);
        chip.setBackgroundResource(R.drawable.chip_soft);
        chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), confirmed ? R.color.green_deep : R.color.orange_deep)));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        return chip;
    }

    /** Themed month picker — the chosen date filters the list to that month. */
    private void pickMonth() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(requireContext(), R.style.Orphos_DatePicker,
                (view, year, month, day) -> {
                    java.util.Calendar picked = java.util.Calendar.getInstance();
                    picked.set(year, month, day);
                    filterMonth = new java.text.SimpleDateFormat("MMM", Locale.getDefault()).format(picked.getTime());
                    render();
                }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH),
                now.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    // --- view helpers ---
    private View empty() {
        TextView t = text(getString(R.string.travel_empty), 14, R.color.text_muted, false);
        t.setGravity(Gravity.CENTER);
        return marginTop(t, 28);
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        String low = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    private View marginTop(View v, int m) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(m); v.setLayoutParams(lp); return v;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(requireContext());
        tv.setText(s); tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setLineSpacing(0f, 1.25f);
        return tv;
    }

    private int dp(int v) { return Math.round(v * density); }
}
