package com.orphos.mobile.ui.scribbles;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.ui.voice.VoiceAvailability;
import com.orphos.mobile.ui.voice.VoiceDictation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice Note (Figma "ORPHOS-1"), reached from Notes → Voice Note. Records the user's own
 * voice (live level waveform + timer); Done hands the clip to processing — Whisper
 * transcription, then the AI summary — and on to the review screen, where the user plays
 * it back, edits the transcript and summary, names the note and saves it
 * (see {@link ProcessingActivity}, {@link CaptureSummaryActivity}).
 *
 * <p>Nothing is stored until that Save. The clip stays on this screen until then, so a
 * failed transcription or a back-out of the review can be retried without re-recording.</p>
 */
public class VoiceNoteActivity extends AppCompatActivity {

    /**
     * Longest note. voice-service gives Whisper 120 s per clip, which CPU transcription of
     * about five minutes of speech fits; longer would time out after the user had spoken.
     */
    private static final int MAX_DURATION_MS = 5 * 60 * 1000;
    /** Shorter than this is a mis-tap, not a note. */
    private static final long MIN_DURATION_MS = 1000;
    private static final int BARS = 20;
    private static final int MIN_BAR_PCT = 8;

    private static final String STATE_CLIP = "vn_clip";
    private static final String STATE_ELAPSED = "vn_elapsed";

    private enum State { IDLE, RECORDING, PAUSED, RECORDED }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> bars = new ArrayList<>();
    private final int[] levels = new int[BARS];

    private State state = State.IDLE;
    private MediaRecorder recorder;
    private File clip;
    /** Start of the current recording stretch; the time before it (across pauses) is in recordedMs. */
    private long startedAt;
    private long recordedMs;
    private long elapsedMs;
    private boolean autoStopped;
    private int maxBarH;

    private TextView timer, status;
    private MaterialButton primary, pause, cancel, recordAgain;

    private ActivityResultLauncher<String> micPermission;
    /** Processing forwards the review's result: RESULT_OK once the note is saved. */
    private ActivityResultLauncher<Intent> processing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_voice_note);
        float density = getResources().getDisplayMetrics().density;

        findViewById(R.id.vn_back).setOnClickListener(v -> finish());
        timer = findViewById(R.id.vn_timer);
        status = findViewById(R.id.vn_status);
        primary = findViewById(R.id.vn_primary);
        pause = findViewById(R.id.vn_pause);
        cancel = findViewById(R.id.vn_cancel);
        recordAgain = findViewById(R.id.vn_record_again);

        LinearLayout wave = findViewById(R.id.vn_wave);
        int purple = ContextCompat.getColor(this, R.color.ai_purple);
        int barW = Math.round(5 * density), gap = Math.round(4 * density);
        maxBarH = Math.round(90 * density);
        for (int i = 0; i < BARS; i++) {
            View bar = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(purple);
            g.setCornerRadius(barW / 2f);
            bar.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barW, 0);
            lp.leftMargin = gap / 2;
            lp.rightMargin = gap / 2;
            bar.setLayoutParams(lp);
            wave.addView(bar);
            bars.add(bar);
        }

        micPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startRecording();
            else status.setText(R.string.vn_mic_denied);
        });
        processing = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) { finish(); return; }
            // Back from processing or review without saving: the recording is kept for a retry.
            String error = result.getData() != null
                    ? result.getData().getStringExtra(ProcessingActivity.EXTRA_ERROR) : null;
            setState(State.RECORDED);
            if (error != null) status.setText(error);
        });

        primary.setOnClickListener(v -> {
            switch (state) {
                case IDLE:      requestRecording(); break;
                case RECORDING:
                case PAUSED:    if (stopRecording()) process(); break;
                case RECORDED:  process(); break;
            }
        });
        pause.setOnClickListener(v -> {
            if (state == State.RECORDING) pauseRecording();
            else if (state == State.PAUSED) resumeRecording();
        });
        cancel.setOnClickListener(v -> confirmCancel());
        recordAgain.setOnClickListener(v -> {
            deleteClip();
            requestRecording();
        });

        if (savedInstanceState != null) {
            String path = savedInstanceState.getString(STATE_CLIP);
            if (path != null && new File(path).length() > 0L) {
                clip = new File(path);
                elapsedMs = savedInstanceState.getLong(STATE_ELAPSED);
            }
        }
        setState(clip != null ? State.RECORDED : State.IDLE);

        // Demo mode records for real but transcribes to a sample (no Whisper); the idle hint
        // says so up front (see setState). Connected, warn early if Voice is switched off.
        if (!OrphosApp.get().config().isDemoMode() && clip == null) {
            VoiceAvailability.check(OrphosApp.get().api(), on -> {
                if (!on && !isDestroyed() && state == State.IDLE) status.setText(R.string.vn_err_not_enabled);
            });
        }
    }

    // ── Recording ──────────────────────────────────────────────────────────────

    private void requestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startRecording() {
        if (state == State.RECORDING) return;
        File dir = new File(getCacheDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // The .m4a extension is what tells Whisper how to decode the clip.
        clip = new File(dir, "voice-" + System.currentTimeMillis() + ".m4a");
        try {
            recorder = VoiceDictation.newWhisperRecorder(this, clip);
            recorder.setMaxDuration(MAX_DURATION_MS);
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    handler.post(() -> {
                        if (state != State.RECORDING) return;
                        // The recorder has already finalised the file itself.
                        autoStopped = true;
                        if (stopRecording()) status.setText(R.string.vn_max_reached);
                    });
                }
            });
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            releaseRecorder();
            deleteClip();
            setState(State.IDLE);
            status.setText(R.string.vn_record_failed);
            return;
        }
        java.util.Arrays.fill(levels, 0);
        startedAt = SystemClock.elapsedRealtime();
        recordedMs = 0;
        elapsedMs = 0;
        setState(State.RECORDING);
        handler.post(meter);
    }

    /** Holds the mic without ending the note; Resume appends to the same clip. */
    private void pauseRecording() {
        try {
            recorder.pause();
        } catch (RuntimeException e) {
            return; // still recording — nothing changes
        }
        handler.removeCallbacks(meter);
        recordedMs += SystemClock.elapsedRealtime() - startedAt;
        java.util.Arrays.fill(levels, 0);
        setState(State.PAUSED);
        showElapsed(recordedMs);
    }

    private void resumeRecording() {
        try {
            recorder.resume();
        } catch (RuntimeException e) {
            // The recorder can't continue; keep what was said so far rather than lose it.
            if (stopRecording()) status.setText(R.string.vn_record_failed);
            return;
        }
        startedAt = SystemClock.elapsedRealtime();
        setState(State.RECORDING);
        handler.post(meter);
    }

    /**
     * Cancel: asks before throwing the recording away. The mic is paused while the user
     * decides, so the question isn't recorded; "Keep recording" picks up where it was.
     */
    private void confirmCancel() {
        if (state != State.RECORDING && state != State.PAUSED) return;
        boolean wasRecording = state == State.RECORDING;
        if (wasRecording) pauseRecording();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vn_cancel_title)
                .setMessage(R.string.vn_cancel_message)
                .setPositiveButton(R.string.vn_cancel_confirm, (d, w) -> cancelRecording())
                .setNegativeButton(R.string.vn_keep_recording, (d, w) -> {
                    if (wasRecording && state == State.PAUSED) resumeRecording();
                })
                .setOnCancelListener(d -> {
                    if (wasRecording && state == State.PAUSED) resumeRecording();
                })
                .show();
    }

    /** Stops the mic and deletes the clip; nothing was saved, so nothing else needs undoing. */
    private void cancelRecording() {
        // RECORDED too: leaving the app while the dialog was open already stopped the mic.
        if (state == State.IDLE) return;
        handler.removeCallbacks(meter);
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Nothing usable was written — it's being discarded anyway.
            }
            releaseRecorder();
        }
        deleteClip();
        recordedMs = 0;
        elapsedMs = 0;
        setState(State.IDLE);
        status.setText(R.string.vn_cancelled);
    }

    /** Time actually recorded, excluding pauses. */
    private long recordedSoFar() {
        return recordedMs + (state == State.RECORDING ? SystemClock.elapsedRealtime() - startedAt : 0);
    }

    /**
     * Stops the mic and keeps the clip. False (and the clip discarded) when nothing usable
     * was captured — MediaRecorder.stop() throws when stopped right after start().
     */
    private boolean stopRecording() {
        if (state != State.RECORDING && state != State.PAUSED) return clip != null;
        handler.removeCallbacks(meter);
        elapsedMs = recordedSoFar();
        boolean usable = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // After an automatic stop at the length limit, stop() may throw on a file that
            // is complete; otherwise it means no frames were written.
            usable = autoStopped;
        } finally {
            autoStopped = false;
            releaseRecorder();
        }
        if (!usable || elapsedMs < MIN_DURATION_MS || clip == null || clip.length() == 0L) {
            deleteClip();
            elapsedMs = 0;
            setState(State.IDLE);
            status.setText(R.string.vn_too_short);
            return false;
        }
        setState(State.RECORDED);
        return true;
    }

    /** Done: transcribe → AI summary → review, all in processing. */
    private void process() {
        if (clip == null || !clip.exists()) { setState(State.IDLE); return; }
        Intent i = new Intent(this, ProcessingActivity.class);
        i.putExtra(ProcessingActivity.EXTRA_AUDIO, clip.getAbsolutePath());
        processing.launch(i);
    }

    /** Samples the mic level into the waveform and advances the timer while recording. */
    private final Runnable meter = new Runnable() {
        @Override public void run() {
            if (state != State.RECORDING || recorder == null) return;
            int amp = 0;
            try { amp = recorder.getMaxAmplitude(); } catch (RuntimeException ignored) { }
            System.arraycopy(levels, 1, levels, 0, BARS - 1);
            // Square root: speech sits in the bottom tenth of the linear range.
            levels[BARS - 1] = (int) Math.round(Math.sqrt(Math.min(amp, 32767) / 32767.0) * 100);
            showElapsed(recordedSoFar());
            renderWave();
            handler.postDelayed(this, 100);
        }
    };

    // ── UI ─────────────────────────────────────────────────────────────────────

    private void setState(State s) {
        state = s;
        switch (s) {
            case IDLE:
                java.util.Arrays.fill(levels, 0);
                showElapsed(0);
                status.setText(OrphosApp.get().config().isDemoMode()
                        ? R.string.vn_status_idle_demo : R.string.vn_status_idle);
                primary.setText(R.string.vn_record);
                primary.setIconResource(R.drawable.ic_mic);
                pause.setVisibility(View.GONE);
                cancel.setVisibility(View.GONE);
                recordAgain.setVisibility(View.GONE);
                break;
            case RECORDING:
            case PAUSED:
                boolean paused = s == State.PAUSED;
                status.setText(paused ? R.string.vn_status_paused : R.string.vn_status_recording);
                primary.setText(R.string.vn_done);
                primary.setIconResource(R.drawable.ic_stop);
                pause.setText(paused ? R.string.vn_resume : R.string.vn_pause);
                pause.setIconResource(paused ? R.drawable.ic_mic : R.drawable.ic_pause);
                pause.setVisibility(View.VISIBLE);
                cancel.setVisibility(View.VISIBLE);
                recordAgain.setVisibility(View.GONE);
                break;
            case RECORDED:
                showElapsed(elapsedMs);
                status.setText(R.string.vn_status_ready);
                primary.setText(R.string.vn_done);
                primary.setIconResource(R.drawable.ic_check_circle);
                pause.setVisibility(View.GONE);
                cancel.setVisibility(View.GONE);
                recordAgain.setVisibility(View.VISIBLE);
                break;
        }
        renderWave();
    }

    private void renderWave() {
        for (int i = 0; i < BARS; i++) {
            View bar = bars.get(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.height = maxBarH * Math.max(MIN_BAR_PCT, levels[i]) / 100;
            bar.setLayoutParams(lp);
        }
    }

    private void showElapsed(long ms) {
        long s = ms / 1000;
        timer.setText(String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60));
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        // The mic is not held in the background; what was said so far is kept.
        if (state == State.RECORDING || state == State.PAUSED) stopRecording();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (clip != null && state == State.RECORDED) {
            out.putString(STATE_CLIP, clip.getAbsolutePath());
            out.putLong(STATE_ELAPSED, elapsedMs);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releaseRecorder();
        // Leaving the screen ends the note: a saved note's audio is on the server, and an
        // unsaved recording is more personal than to be left behind in the cache.
        if (isFinishing()) deleteClip();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void deleteClip() {
        if (clip != null) {
            //noinspection ResultOfMethodCallIgnored
            clip.delete();
            clip = null;
        }
    }
}
