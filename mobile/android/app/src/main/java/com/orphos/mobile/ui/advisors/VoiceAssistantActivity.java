package com.orphos.mobile.ui.advisors;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.orphos.mobile.R;

/**
 * Voice Assistant (Figma "ORPHOS-1") — the "Advisors with Orphos" voice surface with a
 * pulsing mic, live "I'm listening…" state and spoken-prompt suggestions. Picking a
 * suggestion transitions to "Processing…" then hands off to Ask Orphos.
 */
public class VoiceAssistantActivity extends AppCompatActivity {

    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_voice_assistant);
        density = getResources().getDisplayMetrics().density;

        findViewById(R.id.va_back).setOnClickListener(v -> finish());

        pulse(findViewById(R.id.va_ring1), 0);
        pulse(findViewById(R.id.va_ring2), 700);

        int[] qs = { R.string.va_q1, R.string.va_q2, R.string.va_q3 };
        LinearLayout container = findViewById(R.id.va_suggestions);
        for (int q : qs) container.addView(chip(getString(q)));
    }

    /** Expanding, fading ring — the "listening" pulse. */
    private void pulse(View ring, long delay) {
        ScaleAnimation scale = new ScaleAnimation(0.85f, 1.15f, 0.85f, 1.15f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        AlphaAnimation alpha = new AlphaAnimation(0.7f, 0.05f);
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(scale);
        set.addAnimation(alpha);
        set.setDuration(2200);
        set.setStartOffset(delay);
        set.setRepeatCount(Animation.INFINITE);
        ring.startAnimation(set);
    }

    private TextView chip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text));
        tv.setBackgroundResource(R.drawable.card_bg_2);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> handoff(label));
        return tv;
    }

    /** Show "Processing…", then hand the prompt to Ask Orphos (mirrors the prototype). */
    private void handoff(String prompt) {
        ((TextView) findViewById(R.id.va_state)).setText(R.string.va_processing);
        findViewById(R.id.va_suggestions).setEnabled(false);
        View root = findViewById(android.R.id.content);
        root.postDelayed(() -> {
            startActivity(new Intent(this, AskOrphosActivity.class));
            finish();
        }, 1100);
    }

    private int dp(int v) { return Math.round(v * density); }
}
