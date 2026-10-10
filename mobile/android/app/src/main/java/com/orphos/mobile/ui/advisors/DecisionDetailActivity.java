package com.orphos.mobile.ui.advisors;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.DecisionAnnotation;
import com.orphos.mobile.data.model.DecisionItem;

import java.util.List;
import java.util.Locale;

/** Decision detail + AI analysis ("See Analysis"), with Approve / Reject actions. */
public class DecisionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "decision_id";

    private String decisionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_decision_detail);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);

        decisionId = getIntent().getStringExtra(EXTRA_ID);

        findViewById(R.id.detail_back).setOnClickListener(v -> finish());

        findViewById(R.id.detail_approve).setOnClickListener(v -> confirm("APPROVED", R.string.confirm_approve_title));
        findViewById(R.id.detail_reject).setOnClickListener(v -> confirm("REJECTED", R.string.confirm_reject_title));

        bindDecision();
        bindAnalysis();
    }

    private void bindDecision() {
        OrphosApp.get().repo().decision(decisionId, (d, fromMock) -> {
            if (d == null) {
                ((TextView) findViewById(R.id.detail_name)).setText(R.string.data_unavailable);
                return;
            }
            ((TextView) findViewById(R.id.detail_urgency)).setText(
                    d.priorityLevel == null ? "" : d.priorityLevel);
            ((TextView) findViewById(R.id.detail_name)).setText(d.title);
            ((TextView) findViewById(R.id.detail_meta)).setText(String.format(Locale.getDefault(),
                    "%s · AI %d%% · %s: %s",
                    d.status == null ? "" : d.status,
                    d.confidencePercent(),
                    getString(R.string.detail_risk_label),
                    d.risk() == null ? "—" : d.risk()));
            ((TextView) findViewById(R.id.detail_description)).setText(
                    d.description != null ? d.description : (d.rationale != null ? d.rationale : ""));
        });
    }

    private void bindAnalysis() {
        OrphosApp.get().repo().annotation(decisionId, (a, fromMock) -> {
            if (a == null) return;
            ((TextView) findViewById(R.id.analysis_summary)).setText(
                    a.executiveSummary != null ? a.executiveSummary : "");
            fillBullets(findViewById(R.id.impacts_container), a.keyImpacts);
            fillBullets(findViewById(R.id.recommendations_container), a.recommendations);
        });
    }

    private void fillBullets(LinearLayout container, List<String> items) {
        container.removeAllViews();
        if (items == null) return;
        float d = getResources().getDisplayMetrics().density;
        for (String s : items) {
            TextView t = new TextView(this);
            t.setText("•  " + s);
            t.setTextColor(getColor(R.color.text_secondary));
            t.setTextSize(14f);
            t.setLineSpacing(0f, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * d);
            t.setLayoutParams(lp);
            container.addView(t);
        }
    }

    private void confirm(String status, int titleRes) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(getString(R.string.confirm_status_msg, status.toLowerCase()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (d, w) ->
                        OrphosApp.get().repo().updateDecisionStatus(decisionId, status, (updated, fromMock) -> {
                            if (updated == null) {
                                Toast.makeText(this, R.string.action_not_saved, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            Toast.makeText(this, getString(R.string.status_updated, status), Toast.LENGTH_SHORT).show();
                            finish();
                        }))
                .show();
    }
}
