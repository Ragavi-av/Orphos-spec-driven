package com.orphos.mobile.ui.business;

import android.content.Intent;
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
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.ui.advisors.DecisionDetailActivity;

import java.util.List;
import java.util.Locale;

/**
 * Decision Log (decision-log-service) — the role-based Business view for executive roles.
 * Lists decisions with status and priority; tapping one opens its detail. Replaces Team
 * Intelligence for non-CHRO roles.
 */
public class DecisionLogFragment extends Fragment {

    private float density;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_decision_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        density = getResources().getDisplayMetrics().density;
    }

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    public void onResume() {
        super.onResume();
        OrphosApp.get().repo().decisions((list, fromMock) -> {
            if (!isAdded() || getView() == null) return;
            LinearLayout container = getView().findViewById(R.id.dlog_container);
            container.removeAllViews();
            // fromMock is the ONLY way to tell a live decision list from the offline
            // cache or the bundled samples — the cards look identical either way, and a
            // populated screen has repeatedly been mistaken for a working integration.
            TextView summary = getView().findViewById(R.id.dlog_summary);
            summary.setText(fromMock
                    ? getString(R.string.dlog_summary_fmt, list.size())
                      + "  ·  " + getString(R.string.dlog_offline)
                    : getString(R.string.dlog_summary_fmt, list.size()));
            summary.setTextColor(ContextCompat.getColor(requireContext(),
                    fromMock ? R.color.orange : R.color.text_muted));
            for (DecisionItem d : list) container.addView(buildCard(d));
        });
    }

    private View buildCard(DecisionItem d) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);
        card.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                DecisionDetailActivity.class).putExtra(DecisionDetailActivity.EXTRA_ID, d.id)));

        // Title + status chip
        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(d.title, 16, R.color.text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        top.addView(title);
        top.addView(statusChip(d.status));
        card.addView(top);

        // Meta: priority · area · due
        StringBuilder meta = new StringBuilder();
        if (d.priorityLevel != null) meta.append(cap(d.priorityLevel));
        String area = d.decisionArea != null ? d.decisionArea : d.domain;
        if (area != null) meta.append(meta.length() > 0 ? " · " : "").append(cap(area));
        if (d.dueDate != null) meta.append(meta.length() > 0 ? " · " : "").append(d.dueDate);
        TextView metaTv = text(meta.toString(), 12, R.color.text_muted, false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(4);
        metaTv.setLayoutParams(mlp);
        card.addView(metaTv);
        return card;
    }

    private TextView statusChip(String status) {
        String s = status == null ? "OPEN" : status.toUpperCase(Locale.ROOT);
        int fg, bg;
        if (s.equals("APPROVED") || s.equals("COMPLETED")) { fg = R.color.green; bg = R.color.green_deep; }
        else if (s.equals("REJECTED")) { fg = R.color.red; bg = R.color.red_deep; }
        else { fg = R.color.orange; bg = R.color.orange_deep; }
        TextView chip = text(cap(s), 10, fg, true);
        chip.setBackgroundResource(R.drawable.chip_soft);
        chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), bg)));
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setAllCaps(true);
        chip.setLetterSpacing(0.04f);
        return chip;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(requireContext());
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) { return Math.round(v * density); }
}
