package com.orphos.mobile.ui.advisors;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.ui.voice.VoiceAvailability;
import com.orphos.mobile.ui.voice.VoiceDictation;

/**
 * Ask Orphos — the dedicated AI assistant screen opened from the floating orb.
 * A composer plus suggested prompts; questions route to nl-query via the repository
 * and answers stream into an in-screen conversation thread.
 */
public class AskOrphosActivity extends AppCompatActivity implements VoiceDictation.Listener {

    /** Optional question to ask as soon as the screen opens (e.g. an Advisors suggestion tap). */
    public static final String EXTRA_QUESTION = "com.orphos.mobile.extra.QUESTION";

    /** Opens Ask Orphos and asks {@code question} straight away. */
    public static android.content.Intent askIntent(android.content.Context ctx, String question) {
        return new android.content.Intent(ctx, AskOrphosActivity.class).putExtra(EXTRA_QUESTION, question);
    }

    private NestedScrollView scroll;
    private LinearLayout thread;
    private EditText input;
    /** One history session per visit to this screen. */
    private CopilotConversation conversation;

    // ── Voice ──────────────────────────────────────────────────────────────────
    private ImageView orb;
    private VoiceDictation dictation;
    /** Whether voice is running AND enabled for this tenant; gates the double tap. */
    private boolean voiceEnabled;
    private ActivityResultLauncher<String> micPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opened with a question: the user is here to read the answer, so the composer's
        // focus must not throw the keyboard up over it. A tap on the composer still opens it.
        if (!TextUtils.isEmpty(getIntent().getStringExtra(EXTRA_QUESTION))) {
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        setContentView(R.layout.activity_ask_orphos);
        applyInsets();
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);

        scroll = findViewById(R.id.ask_scroll);
        thread = findViewById(R.id.ask_thread);
        input = findViewById(R.id.ask_input);
        conversation = new CopilotConversation(OrphosApp.get().repo());

        findViewById(R.id.ask_back).setOnClickListener(v -> finish());
        orb = findViewById(R.id.ask_send);
        setUpOrb();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); return true; }
            return false;
        });

        wireSuggestion(R.id.ask_suggest_q1, R.string.ask_q1);
        wireSuggestion(R.id.ask_suggest_q2, R.string.ask_q2);
        wireSuggestion(R.id.ask_suggest_q3, R.string.ask_q3);
        wireSuggestion(R.id.ask_suggest_q4, R.string.ask_q4);

        // Only on first creation: a rotation must not ask the same question twice.
        String initial = getIntent().getStringExtra(EXTRA_QUESTION);
        if (savedInstanceState == null && !TextUtils.isEmpty(initial)) ask(initial);
    }

    /**
     * Draws edge to edge on every API level (targetSdk 36 forces it on 35+ anyway, where
     * adjustResize no longer resizes), then pads the root so the header clears the status
     * bar and the pinned composer rides above the gesture bar or the keyboard.
     */
    private void applyInsets() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.ask_root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            // Keyboard just opened: keep the latest message in view above it.
            if (ime.bottom > 0 && thread != null && thread.getChildCount() > 0) scrollToEnd();
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
    }

    // ── Voice dictation ────────────────────────────────────────────────────────

    /**
     * Wires the orb's two gestures and resolves whether voice is even available.
     *
     * <p>Single tap sends, double tap toggles dictation. {@link GestureDetector}
     * does the disambiguation natively: onSingleTapConfirmed fires only once the
     * platform double-tap window has passed, so a double tap never also sends the
     * draft on its way to opening the mic.</p>
     */
    private void setUpOrb() {
        android.util.Log.i("OrphosVoice", "setUpOrb() running, orb=" + orb);
        micPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) dictation().toggle();
                    else toast(getString(R.string.voice_need_mic));
                });

        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;   // claim the stream, or no later gesture is delivered
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                // While the mic is live a stray tap must not fire the half-dictated
                // draft off to the model — the user is still talking.
                if (dictation != null && (dictation.isRecording() || dictation.isTranscribing())) return true;
                send();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (!voiceEnabled) return true;   // no voice here: the gesture simply does nothing
                startOrStopDictation();
                return true;
            }
        });
        orb.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return taps.onTouchEvent(event);
        });

        // Resolve availability in the background. Until it answers the orb rests
        // white and a double tap does nothing, which is also the permanent state
        // wherever voice is off — so there is nothing to undo if it never arrives.
        VoiceAvailability.check(OrphosApp.get().api(), on -> {
            if (isFinishing()) return;
            voiceEnabled = on;
            android.util.Log.i("OrphosVoice", "voiceEnabled=" + on);
        });
    }

    private VoiceDictation dictation() {
        if (dictation == null) {
            dictation = new VoiceDictation(this, OrphosApp.get().api(), this);
        }
        return dictation;
    }

    private void startOrStopDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Asked at the moment of use rather than on screen open: a microphone
            // prompt that appears before the user has reached for voice reads as the
            // app listening uninvited.
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        dictation().toggle();
    }

    /**
     * The orb RESTS white and returns there once a question is sent — dark is not a
     * badge for "this tenant has voice", it is the sign that voice is engaged right
     * now. Mirrors the web composer exactly (see AICopilot.jsx).
     */
    private void renderOrb() {
        int art = R.drawable.orb_white;
        if (dictation != null) {
            if (dictation.isRecording())         art = R.drawable.orb_dark_selected;
            else if (dictation.isTranscribing()) art = R.drawable.orb_dark;
        }
        orb.setImageResource(art);
    }

    // --- VoiceDictation.Listener ---

    @Override
    public void onRecordingStarted() {
        renderOrb();
        input.setHint(R.string.ask_voice_listening);
    }

    @Override
    public void onTranscribing() {
        renderOrb();
        input.setHint(R.string.ask_voice_transcribing);
    }

    @Override
    public void onTranscript(String text) {
        renderOrb();
        input.setHint(R.string.ask_hint);
        // Appended, never substituted: dictating after typing should add to the
        // thought rather than eat it. The transcript lands in the composer for the
        // user to correct — speech-to-text mishears names and numbers, and sending
        // stays a separate, explicit act.
        String existing = input.getText() != null ? input.getText().toString().trim() : "";
        input.setText(existing.isEmpty() ? text : existing + " " + text);
        input.setSelection(input.getText().length());
    }

    @Override
    public void onVoiceError(String message) {
        renderOrb();
        input.setHint(R.string.ask_hint);
        toast(message);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen must never leave the microphone open.
        if (dictation != null && dictation.isRecording()) {
            dictation.cancel();
            renderOrb();
            input.setHint(R.string.ask_hint);
        }
    }

    @Override
    protected void onDestroy() {
        if (dictation != null) dictation.cancel();
        super.onDestroy();
    }

    private void wireSuggestion(int viewId, int stringId) {
        findViewById(viewId).setOnClickListener(v -> ask(getString(stringId)));
    }

    private void send() {
        String q = input.getText() != null ? input.getText().toString().trim() : "";
        if (TextUtils.isEmpty(q)) return;
        input.setText("");
        ask(q);
    }

    /** Post the question, show a pending reply, then fill it from the repository. */
    private void ask(String question) {
        hideKeyboard();
        clearFollowups();
        // The greeting and starter prompts are the empty state; once a question is asked
        // the conversation owns the screen.
        findViewById(R.id.ask_intro).setVisibility(View.GONE);
        addBubble(question, true);
        TextView reply = addBubble(getString(R.string.ask_thinking), false);

        // Through the conversation, so this chat is saved to the shared AI history (the
        // Advisors tab and the web dashboard's History ▸ AI both list it). The save goes
        // ahead even if the user leaves before the answer arrives.
        conversation.ask(question, msg -> {
            if (isFinishing()) return;
            boolean failed = conversation.isFailed(msg);
            reply.setText(!failed ? msg.answer
                    : (msg.answer != null && !msg.answer.isEmpty() ? msg.answer : getString(R.string.ask_unavailable)));
            // Only the latest answer offers follow-ups: a slow earlier one must not.
            boolean latest = thread.indexOfChild(reply) == thread.getChildCount() - 1;
            if (!failed && latest) showFollowups(msg.followups);
            scrollToEnd();
        });
        scrollToEnd();
    }

    /** Follow-up chips under the latest answer; older answers drop theirs. */
    private LinearLayout followupRow;

    private void clearFollowups() {
        if (followupRow != null) thread.removeView(followupRow);
        followupRow = null;
    }

    /** Tapping a follow-up asks it here, in the same conversation. */
    private void showFollowups(@androidx.annotation.Nullable java.util.List<String> followups) {
        clearFollowups();
        if (followups == null || followups.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        for (String f : followups) {
            if (f == null || f.trim().isEmpty()) continue;
            TextView chip = new TextView(this);
            chip.setText(f);
            chip.setTextSize(12f);
            chip.setTextColor(ContextCompat.getColor(this, R.color.link));
            chip.setBackgroundResource(R.drawable.chip_soft);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setOnClickListener(v -> ask(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.addView(chip, lp);
        }
        if (row.getChildCount() == 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(48);
        thread.addView(row, lp);
        followupRow = row;
    }

    /** Adds a chat bubble and returns its TextView (so a pending reply can be updated). */
    private TextView addBubble(String text, boolean fromUser) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.25f);
        int pad = dp(13);
        tv.setPadding(pad, dp(11), pad, dp(11));
        tv.setBackgroundResource(fromUser ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_ai);
        tv.setTextColor(ContextCompat.getColor(this,
                fromUser ? R.color.on_primary : R.color.text));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // A question opens a new turn, so it gets more air above it than its reply does.
        lp.topMargin = dp(fromUser ? (thread.getChildCount() == 0 ? 8 : 20) : 8);
        lp.gravity = fromUser ? Gravity.END : Gravity.START;
        // Questions stay a compact bubble on the right; answers may use most of the width.
        if (fromUser) lp.leftMargin = dp(56); else lp.rightMargin = dp(24);
        thread.addView(tv, lp);
        return tv;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
