package com.orphos.mobile.ui.scribbles;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;

/**
 * Saved items (Figma "ORPHOS-1") — every capture the user has saved, from the same store
 * the Notes screen lists (/api/mobile/captures; the local store in demo mode), newest
 * first. Reached from Notes → Saved items. Connected, each row says whether the capture has
 * reached Web Document Search yet. Tapping one opens its summary.
 */
public class AllCapturesActivity extends AppCompatActivity {

    private float density;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_all_captures);
        density = getResources().getDisplayMetrics().density;

        findViewById(R.id.ac_back).setOnClickListener(v -> finish());
        container = findViewById(R.id.ac_container);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OrphosApp.get().repo().captures((items, fromMock) -> {
            if (isFinishing()) return;
            container.removeAllViews();
            if (items == null || items.isEmpty()) {
                boolean unreachable = fromMock && !OrphosApp.get().config().isDemoMode();
                container.addView(text(getString(unreachable ? R.string.data_unavailable : R.string.captures_empty),
                        14, R.color.text_muted, false));
                return;
            }
            // fromMock with items is demo mode: on-device only, so there is no web status to show.
            for (Capture c : items) container.addView(buildRow(c, !fromMock));
        });
    }

    private View buildRow(Capture c, boolean showSearchStatus) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);
        card.setOnClickListener(v -> startActivity(new Intent(this, CaptureSummaryActivity.class)
                .putExtra(CaptureSummaryActivity.EXTRA_ID, c.id)));

        ImageView orb = new ImageView(this);
        orb.setImageResource(R.drawable.orphos_orb);
        card.addView(orb, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(13);
        col.setLayoutParams(llp);
        col.addView(text(c.title != null ? c.title : "", 16, R.color.text, true));
        String line = c.summary != null && !c.summary.isEmpty() ? c.summary : (c.type != null ? c.type : "");
        if (!line.isEmpty()) col.addView(marginTop(text(line, 12, R.color.ai_purple, false), 3));
        if (c.createdAt != null) col.addView(marginTop(text(c.createdAt, 11, R.color.text_muted, false), 3));
        if (showSearchStatus) {
            col.addView(marginTop(text(getString(c.ingested ? R.string.capture_searchable : R.string.capture_not_searchable),
                    11, c.ingested ? R.color.green : R.color.text_muted, false), 3));
        }
        card.addView(col);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setColorFilter(ContextCompat.getColor(this, R.color.text_muted));
        card.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        return card;
    }

    private View marginTop(View v, int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(m);
        v.setLayoutParams(lp);
        return v;
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
