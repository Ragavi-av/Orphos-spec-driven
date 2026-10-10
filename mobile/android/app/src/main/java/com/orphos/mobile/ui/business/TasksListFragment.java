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
import com.orphos.mobile.data.model.ActionTask;
import com.orphos.mobile.ui.tasks.TasksActivity;

/**
 * Business ▸ Tasks — the day's actions from /api/mobile (decisions). A compact list; a card
 * opens the full Tasks screen (with Critical / High / Approval tabs and the decide actions).
 */
public class TasksListFragment extends Fragment {

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

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    public void onResume() {
        super.onResume();
        OrphosApp.get().repo().actionTasks((tasks, fromMock) -> {
            if (!isAdded() || list == null) return;
            list.removeAllViews();
            if (tasks == null || tasks.isEmpty()) { list.addView(empty()); return; }
            for (ActionTask t : tasks) list.addView(card(t));
        });
    }

    private View card(ActionTask t) {
        boolean critical = "CRITICAL".equalsIgnoreCase(t.severity);
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setClickable(true);
        c.setOnClickListener(v -> startActivity(new Intent(requireContext(), TasksActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(t.title != null ? t.title : "Task", 15, R.color.text, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        TextView sev = text(critical ? "CRITICAL" : (t.severity != null ? t.severity.toUpperCase(java.util.Locale.ROOT) : "HIGH"),
                10, critical ? R.color.red : R.color.orange, true);
        sev.setAllCaps(true);
        sev.setBackgroundResource(R.drawable.chip_soft);
        sev.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), critical ? R.color.red_deep : R.color.orange_deep)));
        sev.setPadding(dp(8), dp(3), dp(8), dp(3));
        head.addView(sev);
        c.addView(head);

        if (t.meta != null) c.addView(marginTop(text(t.meta, 12, R.color.text_muted, false), 3));
        if (t.reason != null) c.addView(marginTop(text(t.reason, 13, R.color.text_secondary, false), 6));
        return c;
    }

    private TextView empty() {
        TextView t = text(getString(R.string.tasks_empty), 14, R.color.text_muted, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24); t.setGravity(Gravity.CENTER); t.setLayoutParams(lp);
        return t;
    }

    private View marginTop(View v, int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(m); v.setLayoutParams(lp); return v;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(requireContext());
        tv.setText(s); tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setLineSpacing(0f, 1.3f);
        return tv;
    }

    private int dp(int v) { return Math.round(v * density); }
}
