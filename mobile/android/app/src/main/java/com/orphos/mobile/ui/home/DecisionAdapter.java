package com.orphos.mobile.ui.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.DecisionItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders decision-queue cards and forwards item / analysis / approve interactions. */
public class DecisionAdapter extends RecyclerView.Adapter<DecisionAdapter.VH> {

    public interface Listener {
        void onOpen(DecisionItem d);
        void onAnalysis(DecisionItem d);
        void onApprove(DecisionItem d);
    }

    private final List<DecisionItem> items = new ArrayList<>();
    private final Listener listener;

    public DecisionAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<DecisionItem> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_decision, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DecisionItem d = items.get(position);
        h.title.setText(d.title);
        h.urgency.setText(d.priorityLevel != null ? d.priorityLevel : "");
        int pct = d.confidencePercent();
        h.confidence.setText(String.format(Locale.getDefault(), "%d%%", pct));
        h.confidenceBar.setMax(100);
        h.confidenceBar.setProgressCompat(pct, false);
        int col = pct >= 80 ? 0xFF34D399 : (pct >= 60 ? 0xFFF5A623 : 0xFFFB6A82);
        h.confidenceBar.setIndicatorColor(col);
        h.impact.setText(h.ctx().getString(R.string.decision_impact, d.impactLabel()));
        h.risk.setText(h.ctx().getString(R.string.decision_risk, d.risk() == null ? "—" : d.risk()));

        boolean actionable = d.status == null
                || d.status.equalsIgnoreCase("PENDING") || d.status.equalsIgnoreCase("DETECTED");
        h.approve.setEnabled(actionable);
        h.approve.setText(actionable ? R.string.decision_approve : R.string.decision_done);

        h.itemView.setOnClickListener(v -> listener.onOpen(d));
        h.analysis.setOnClickListener(v -> listener.onAnalysis(d));
        h.approve.setOnClickListener(v -> listener.onApprove(d));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, urgency, confidence, impact, risk;
        final com.google.android.material.progressindicator.LinearProgressIndicator confidenceBar;
        final MaterialButton analysis, approve;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.decision_title);
            urgency = v.findViewById(R.id.decision_urgency);
            confidence = v.findViewById(R.id.decision_confidence);
            confidenceBar = v.findViewById(R.id.decision_confidence_bar);
            impact = v.findViewById(R.id.decision_impact);
            risk = v.findViewById(R.id.decision_risk);
            analysis = v.findViewById(R.id.decision_analysis);
            approve = v.findViewById(R.id.decision_approve);
        }

        android.content.Context ctx() { return itemView.getContext(); }
    }
}
