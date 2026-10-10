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

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.ApprovalItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Business ▸ Approvals — the auto-inferred approvals from /api/mobile/approvals. Pending
 * items get Approve / Reject actions that post the executive's decision to the BFF.
 */
public class ApprovalsFragment extends Fragment {

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
        load();
    }

    private void load() {
        OrphosApp.get().repo().approvals((items, fromMock) -> {
            if (!isAdded() || list == null) return;
            list.removeAllViews();
            // Only items still awaiting a decision. Once approved/rejected an approval is done,
            // so it leaves this list — and Home's Approvals count, which counts the same rule.
            List<ApprovalItem> pending = new ArrayList<>();
            if (items != null) for (ApprovalItem a : items) if (a.isPending()) pending.add(a);
            if (pending.isEmpty()) {
                boolean unreachable = fromMock && !OrphosApp.get().config().isDemoMode();
                list.addView(empty(getString(unreachable ? R.string.data_unavailable : R.string.approvals_empty)));
                return;
            }
            for (ApprovalItem a : pending) list.addView(card(a));
        });
    }

    private View card(ApprovalItem a) {
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);

        c.addView(text(a.subject != null ? a.subject : "Approval required", 16, R.color.text, true));
        if (a.summary != null && !a.summary.isEmpty())
            c.addView(marginTop(text(a.summary, 13, R.color.text_secondary, false), 6));

        boolean pending = a.isPending();
        // Status chip
        TextView chip = text(pending ? a.approvalType != null ? a.approvalType : "APPROVE_REJECT" : a.status,
                10, pending ? R.color.ai_purple : R.color.text_muted, true);
        chip.setAllCaps(true);
        chip.setBackgroundResource(R.drawable.chip_soft);
        chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(),
                        pending ? R.color.purple_deep : R.color.surface_3)));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        c.addView(marginTop(chip, 10));

        if (pending) {
            String[] labels = labelsFor(a.approvalType);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(14);
            row.setLayoutParams(rlp);
            row.addView(button(labels[1], false, () -> decide(a, labels[3])));
            row.addView(gap());
            row.addView(button(labels[0], true, () -> decide(a, labels[2])));
            c.addView(row);
        }
        return c;
    }

    /** {positiveLabel, negativeLabel, positiveDecision, negativeDecision} for the approval style. */
    private String[] labelsFor(String type) {
        if ("YES_NO".equals(type)) return new String[]{"Yes", "No", "YES", "NO"};
        if ("GO_AHEAD".equals(type)) return new String[]{"Go", "Hold", "GO", "HOLD"};
        return new String[]{"Approve", "Reject", "APPROVE", "REJECT"};
    }

    private void decide(ApprovalItem a, String decision) {
        OrphosApp.get().repo().decideApproval(a.id, decision, (ok, fromMock) -> {
            if (!isAdded()) return;
            toast(Boolean.TRUE.equals(ok)
                    ? getString(R.string.tasks_decided_fmt, decision, a.subject != null ? a.subject : "")
                    : getString(R.string.action_not_saved));
            load();
        });
    }

    // --- tiny view helpers ---
    private View gap() { View g = new View(requireContext());
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1)); return g; }

    private TextView button(String label, boolean primary, Runnable onClick) {
        TextView b = new TextView(requireContext());
        b.setText(label); b.setGravity(Gravity.CENTER); b.setTextSize(13f);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(ContextCompat.getColor(requireContext(),
                primary ? R.color.on_primary : R.color.text_secondary));
        b.setBackgroundResource(primary ? R.drawable.bg_btn_primary_sm : R.drawable.card_bg_2);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private TextView empty(String s) {
        TextView t = text(s, 14, R.color.text_muted, false);
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

    private void toast(String msg) {
        if (getView() != null) Snackbar.make(getView(), msg, Snackbar.LENGTH_SHORT).show();
    }

    private int dp(int v) { return Math.round(v * density); }
}
