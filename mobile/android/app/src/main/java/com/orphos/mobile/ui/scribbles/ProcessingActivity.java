package com.orphos.mobile.ui.scribbles;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;

import java.util.ArrayList;
import java.util.List;

/**
 * "Processing …" — a live timeline of the AI pipeline (reading → understanding →
 * extracting → categorizing → preparing summary) with per-step progress, an overall
 * progress bar, then the capture summary. Matches the Figma "ORPHOS-1" flow.
 *
 * <p>For handwriting ({@link #EXTRA_FILE}) the timeline tracks the real pipeline —
 * on-device recognition, then the server's AI summary — and ends on the review screen,
 * where the user corrects and names the note before anything is saved.</p>
 */
public class ProcessingActivity extends AppCompatActivity {

    /** A capture already saved (typed / voice / photo): replay the timeline, then its summary. */
    public static final String EXTRA_ID = "capture_id";
    /** Handwriting not yet saved: the page image, and the stroke file for recognition. */
    public static final String EXTRA_FILE = "capture_file";
    public static final String EXTRA_INK = "capture_ink";
    public static final String EXTRA_INK_WIDTH = "capture_ink_w";
    public static final String EXTRA_INK_HEIGHT = "capture_ink_h";
    /** Type Note not yet saved: the text exactly as typed. It's summarised as-is, with no OCR. */
    public static final String EXTRA_TEXT = "capture_text";
    /** Voice note not yet saved: the recording (.m4a). Transcribed by Whisper, then summarised. */
    public static final String EXTRA_AUDIO = "capture_audio";
    /**
     * Uploaded / photographed image not yet saved: the original file, its capture type
     * (IMAGE for gallery, PHOTO for camera) and mime type. The server OCRs it, then summarises.
     */
    public static final String EXTRA_IMAGE = "capture_image";
    public static final String EXTRA_IMAGE_TYPE = "capture_image_type";
    public static final String EXTRA_IMAGE_MIME = "capture_image_mime";
    /**
     * Result extra when a voice note could not be transcribed: the user-facing reason. The
     * recording screen shows it and keeps the clip, so the user can retry without re-recording.
     */
    public static final String EXTRA_ERROR = "capture_error";

    /** Overall progress the timeline may reach before the current stage reports back. */
    private static final int CEIL_READING = 18;
    private static final int CEIL_TRANSCRIBING = 23;
    private static final int CEIL_WAITING_AI = 90;

    private final List<Step> steps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearProgressIndicator bar;
    private TextView barPct;
    private int progress = 0;
    private int ceiling = 100;
    private Runnable onComplete;
    private float density;

    // Handwriting pipeline results, handed to the review screen.
    private String deviceText;
    private Capture draft;
    private boolean aiReached;

    /** One timeline row and its dynamic parts. */
    private static class Step {
        final int labelRes;
        final int subRes; // 0 = none
        ImageView node;
        View line;
        TextView label, sub, pct;
        Step(int labelRes, int subRes) { this.labelRes = labelRes; this.subRes = subRes; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_processing);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);
        density = getResources().getDisplayMetrics().density;

        String id = getIntent().getStringExtra(EXTRA_ID);
        findViewById(R.id.proc_back).setOnClickListener(v -> finish());
        bar = findViewById(R.id.proc_bar);
        barPct = findViewById(R.id.proc_bar_pct);

        String audio = getIntent().getStringExtra(EXTRA_AUDIO);
        if (audio != null) {
            steps.add(new Step(R.string.step_transcribing, R.string.step_transcribing_sub));
            steps.add(new Step(R.string.step_ai_processing, R.string.step_summary_sub));
            steps.add(new Step(R.string.step_extracting, 0));
            steps.add(new Step(R.string.step_preparing_review, 0));
        } else {
            steps.add(new Step(R.string.step_reading, 0));
            steps.add(new Step(R.string.step_understanding, 0));
            steps.add(new Step(R.string.step_extracting, 0));
            steps.add(new Step(R.string.step_categorizing, 0));
            steps.add(new Step(R.string.step_summary, R.string.step_summary_sub));
        }

        LinearLayout container = findViewById(R.id.steps_container);
        for (int i = 0; i < steps.size(); i++) {
            container.addView(buildRow(steps.get(i), i == steps.size() - 1));
        }

        render();
        String file = getIntent().getStringExtra(EXTRA_FILE);
        String typed = getIntent().getStringExtra(EXTRA_TEXT);
        String image = getIntent().getStringExtra(EXTRA_IMAGE);
        if (audio != null) {
            startVoice(audio);
        } else if (image != null) {
            startImage(image);
        } else if (file != null) {
            startHandwriting(file);
        } else if (typed != null) {
            startTyped(typed);
        } else {
            onComplete = () -> openSummary(id);
        }
        tick();
    }

    /**
     * Read the strokes on the device, then have the server summarise the text (or OCR the
     * page when the device could not read it). The timeline advances with each stage, and
     * completes only when the AI has answered or failed.
     */
    private void startHandwriting(String file) {
        ceiling = CEIL_READING;
        java.io.File page = new java.io.File(file);
        List<float[]> ink = com.orphos.mobile.core.capture.CaptureFiles.readInk(getIntent().getStringExtra(EXTRA_INK));
        float w = getIntent().getFloatExtra(EXTRA_INK_WIDTH, 0f);
        float h = getIntent().getFloatExtra(EXTRA_INK_HEIGHT, 0f);

        com.orphos.mobile.core.capture.HandwritingRecognizer.recognize(ink, w, h, text -> {
            if (isDestroyed()) return;
            deviceText = text;
            ceiling = CEIL_WAITING_AI;
            OrphosApp.get().repo().extractCapture(page, text, "image/png", (result, failed) -> {
                if (isDestroyed()) return;
                draft = result;
                aiReached = result != null && !failed;
                onComplete = () -> openReview(file);
                ceiling = 100;
            });
        });
    }

    /**
     * Uploaded image: the server OCRs the actual image, then the AI summarises the extracted
     * text. Nothing is saved — the review shows the image, the text exactly as OCR read it,
     * and the summary, and saves only once the user confirms.
     */
    private void startImage(String image) {
        ceiling = CEIL_WAITING_AI;
        String mime = getIntent().getStringExtra(EXTRA_IMAGE_MIME);
        OrphosApp.get().repo().extractCapture(new java.io.File(image), null, mime, (result, failed) -> {
            if (isDestroyed()) return;
            draft = result;
            aiReached = result != null && !failed;
            onComplete = () -> openImageReview(image);
            ceiling = 100;
        });
    }

    /**
     * The image review: the original image, the OCR text (editable, and also kept unedited so
     * the original extraction is saved beside any correction), and the AI's title, summary and points.
     */
    private void openImageReview(String image) {
        String type = getIntent().getStringExtra(EXTRA_IMAGE_TYPE);
        String ocr = draft != null && draft.content != null ? draft.content.trim() : "";
        Intent i = new Intent(this, CaptureSummaryActivity.class);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_FILE, image);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TYPE, type != null ? type : Capture.TYPE_IMAGE);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_MIME, getIntent().getStringExtra(EXTRA_IMAGE_MIME));
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TEXT, ocr);
        if (!ocr.isEmpty()) i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TRANSCRIPT, ocr);
        putDraftAi(i);
        boolean hasSummary = draft != null && draft.summary != null && !draft.summary.trim().isEmpty();
        if (draft == null) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_image_unavailable));
        } else if (ocr.isEmpty()) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_image_no_text));
        } else if (draft.lowConfidence) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_image_low_confidence));
        } else if (!aiReached || !hasSummary) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_ai_unavailable));
        }
        startActivity(i);
        finish();
    }

    /**
     * Typed note: the text is already there, so only the AI stage runs. The server summarises
     * the text as sent, and the review shows the original next to that summary.
     */
    private void startTyped(String typed) {
        deviceText = typed;
        ceiling = CEIL_WAITING_AI;
        OrphosApp.get().repo().extractCapture(null, typed, null, (result, failed) -> {
            if (isDestroyed()) return;
            draft = result;
            aiReached = result != null && !failed;
            onComplete = () -> openReview(null);
            ceiling = 100;
        });
    }

    /**
     * Voice note: Whisper transcribes the recording, then the server summarises the
     * transcript exactly as it was heard. Without a transcript there is nothing to review,
     * so a failure returns to the recording screen with the reason instead.
     */
    private void startVoice(String audio) {
        ceiling = CEIL_TRANSCRIBING;
        OrphosApp.get().repo().transcribeVoiceNote(new java.io.File(audio),
                com.orphos.mobile.ui.voice.VoiceDictation.dictationLanguage(), (transcript, error) -> {
            if (isDestroyed()) return;
            if (transcript == null) {
                Intent result = new Intent().putExtra(EXTRA_ERROR, getString(transcribeErrorRes(error)));
                setResult(RESULT_CANCELED, result);
                finish();
                return;
            }
            deviceText = transcript;
            ceiling = CEIL_WAITING_AI;
            OrphosApp.get().repo().extractCapture(null, transcript, null, (result, failed) -> {
                if (isDestroyed()) return;
                draft = result;
                aiReached = result != null && !failed;
                onComplete = () -> openVoiceReview(audio);
                ceiling = 100;
            });
        });
    }

    private static int transcribeErrorRes(com.orphos.mobile.data.repository.OrphosRepository.TranscribeError e) {
        if (e == null) return R.string.vn_err_unavailable;
        switch (e) {
            case NOT_ENABLED: return R.string.vn_err_not_enabled;
            case NO_SPEECH:   return R.string.vn_err_no_speech;
            case TIMEOUT:     return R.string.vn_err_timeout;
            case OFFLINE:     return R.string.vn_err_offline;
            default:          return R.string.vn_err_unavailable;
        }
    }

    /**
     * The voice review: the recording for playback, the transcript as Whisper heard it
     * (editable, and also kept unedited for the save), and the AI's title, summary and points.
     */
    private void openVoiceReview(String audio) {
        Intent i = new Intent(this, CaptureSummaryActivity.class);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_FILE, audio);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TYPE, Capture.TYPE_AUDIO);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TEXT, deviceText);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TRANSCRIPT, deviceText);
        putDraftAi(i);
        boolean hasSummary = draft != null && draft.summary != null && !draft.summary.trim().isEmpty();
        if (OrphosApp.get().config().isDemoMode()) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_voice_demo));
        } else if (!aiReached || !hasSummary) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_ai_unavailable));
        }
        // The recording screen that launched this one waits for the review's result.
        i.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        startActivity(i);
        finish();
    }

    /** {@code file} is the handwriting page, or null for a typed note (which keeps its own text). */
    private void openReview(String file) {
        Intent i = new Intent(this, CaptureSummaryActivity.class);
        boolean typed = file == null;
        if (!typed) i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_FILE, file);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TYPE, typed ? Capture.TYPE_TEXT : Capture.TYPE_HANDWRITING);
        // A typed note's original is what the user wrote, never the server's copy of it.
        String text = !typed && draft != null && draft.content != null && !draft.content.trim().isEmpty()
                ? draft.content : deviceText;
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TEXT, text);
        putDraftAi(i);
        boolean hasSummary = draft != null && draft.summary != null && !draft.summary.trim().isEmpty();
        if (text == null || text.trim().isEmpty()) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_not_read));
        } else if (!aiReached || !hasSummary) {
            i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_NOTICE, getString(R.string.review_ai_unavailable));
        }
        // The canvas that launched this screen waits for the review's result.
        i.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        startActivity(i);
        finish();
    }

    /** The AI's title, summary, key points, actions and decisions, for the review screen. */
    private void putDraftAi(Intent i) {
        if (draft == null) return;
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_TITLE, draft.title);
        i.putExtra(CaptureSummaryActivity.EXTRA_REVIEW_SUMMARY, draft.summary);
        if (draft.keyPoints != null) {
            i.putStringArrayListExtra(CaptureSummaryActivity.EXTRA_REVIEW_POINTS, new ArrayList<>(draft.keyPoints));
        }
        if (draft.actions != null) {
            i.putStringArrayListExtra(CaptureSummaryActivity.EXTRA_REVIEW_ACTIONS, new ArrayList<>(draft.actions));
        }
        if (draft.decisions != null) {
            i.putStringArrayListExtra(CaptureSummaryActivity.EXTRA_REVIEW_DECISIONS, new ArrayList<>(draft.decisions));
        }
    }

    private View buildRow(Step step, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Left column: node dot + connecting line
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.MATCH_PARENT));

        step.node = new ImageView(this);
        step.node.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        left.addView(step.node);

        step.line = new View(this);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(dp(2), 0, 1f);
        lineLp.topMargin = dp(2);
        lineLp.bottomMargin = dp(2);
        step.line.setLayoutParams(lineLp);
        step.line.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        left.addView(step.line);
        row.addView(left);

        // Middle: label + optional subtitle
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(14);
        midLp.bottomMargin = dp(18);
        mid.setLayoutParams(midLp);

        step.label = new TextView(this);
        step.label.setText(step.labelRes);
        step.label.setTextSize(15f);
        mid.addView(step.label);

        step.sub = new TextView(this);
        if (step.subRes != 0) step.sub.setText(step.subRes);
        step.sub.setTextSize(12f);
        step.sub.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        step.sub.setVisibility(View.GONE);
        mid.addView(step.sub);
        row.addView(mid);

        // Right: percentage
        step.pct = new TextView(this);
        step.pct.setTextSize(13f);
        step.pct.setTypeface(step.pct.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(step.pct);

        return row;
    }

    /** Advance toward the current stage's ceiling; finish once 100% is allowed and reached. */
    private void tick() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (progress < ceiling) {
                    progress = Math.min(ceiling, progress + 3);
                    render();
                }
                if (progress >= 100 && onComplete != null) {
                    handler.postDelayed(onComplete, 450);
                } else {
                    handler.postDelayed(this, 70);
                }
            }
        }, 250);
    }

    /** Update every row and the overall bar from the current progress. */
    private void render() {
        int green = ContextCompat.getColor(this, R.color.green);
        int blue = ContextCompat.getColor(this, R.color.primary);
        int muted = ContextCompat.getColor(this, R.color.text_muted);
        int white = ContextCompat.getColor(this, R.color.text);
        int track = ContextCompat.getColor(this, R.color.surface_3);

        int span = 100 / steps.size();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            int share = clamp((progress - i * span) * 100 / span);
            boolean done = share >= 100;
            boolean active = share > 0 && !done;

            s.node.setImageResource(done ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            s.node.setColorFilter(done ? green : active ? blue : muted, PorterDuff.Mode.SRC_IN);
            s.line.setBackgroundColor(done ? green : track);
            s.label.setTextColor(share > 0 ? white : muted);
            s.sub.setVisibility(active && s.subRes != 0 ? View.VISIBLE : View.GONE);

            s.pct.setText(share > 0 ? getString(R.string.processing_pct, share) : "—");
            s.pct.setTextColor(done ? green : active ? blue : muted);
        }

        bar.setProgressCompat(progress, true);
        barPct.setText(getString(R.string.processing_pct, progress));
    }

    private void openSummary(String id) {
        Intent i = new Intent(this, CaptureSummaryActivity.class);
        i.putExtra(CaptureSummaryActivity.EXTRA_ID, id);
        startActivity(i);
        finish();
    }

    private int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    private int dp(int v) { return Math.round(v * density); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
