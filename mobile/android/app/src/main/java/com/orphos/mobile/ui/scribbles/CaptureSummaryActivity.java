package com.orphos.mobile.ui.scribbles;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture Summary — AI summary + key points, with Save &amp; Continue and Share.
 *
 * <p>Two modes on the one screen:</p>
 * <ul>
 *   <li><b>View</b> ({@link #EXTRA_ID}): a saved capture in full — the handwriting as
 *       captured, its text, summary and key points.</li>
 *   <li><b>Review</b> ({@link #EXTRA_REVIEW_TYPE}): a handwriting draft (its page in
 *       {@link #EXTRA_REVIEW_FILE}), a voice note (its recording in {@link #EXTRA_REVIEW_FILE},
 *       played back here) or a typed note (no file) straight from processing. The text, summary and key points are editable, the user names the
 *       note, and Save persists it (BFF + doc-ingest, so Web Document Search finds it).
 *       Returns RESULT_OK once saved.</li>
 * </ul>
 */
public class CaptureSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "capture_id";

    public static final String EXTRA_REVIEW_FILE = "review_file";
    public static final String EXTRA_REVIEW_TYPE = "review_type";
    public static final String EXTRA_REVIEW_TEXT = "review_text";
    public static final String EXTRA_REVIEW_TITLE = "review_title";
    public static final String EXTRA_REVIEW_SUMMARY = "review_summary";
    public static final String EXTRA_REVIEW_POINTS = "review_points";
    /** The AI's actions and pending decisions; saved as found (absent = never classified). */
    public static final String EXTRA_REVIEW_ACTIONS = "review_actions";
    public static final String EXTRA_REVIEW_DECISIONS = "review_decisions";
    public static final String EXTRA_REVIEW_NOTICE = "review_notice";
    /** Voice note: the transcript exactly as Whisper returned it, saved beside the edited text. */
    public static final String EXTRA_REVIEW_TRANSCRIPT = "review_transcript";
    /** Uploaded image: the original's mime type, so it is stored exactly as picked or photographed. */
    public static final String EXTRA_REVIEW_MIME = "review_mime";

    private static final int MAX_NAME_CHARS = 80;
    /** Longest side an image is decoded to for display; the stored original keeps its full size. */
    private static final int MAX_DISPLAY_PX = 2048;

    private Capture capture;
    private String reviewFile; // the handwriting page or voice recording; null for a typed note
    private AudioPlayback audio;
    private java.io.File downloadedAudio; // a saved note's recording, fetched for playback
    private boolean reviewing;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_capture_summary);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);

        findViewById(R.id.summary_back).setOnClickListener(v -> finish());
        findViewById(R.id.summary_share).setOnClickListener(v -> share());

        reviewFile = getIntent().getStringExtra(EXTRA_REVIEW_FILE);
        reviewing = getIntent().hasExtra(EXTRA_REVIEW_TYPE);
        if (reviewing) {
            bindReview();
        } else {
            bindSaved(getIntent().getStringExtra(EXTRA_ID));
        }
    }

    // --- View mode ---

    private void bindSaved(String id) {
        TextView headerTitle = findViewById(R.id.summary_header_title);
        OrphosApp.get().repo().captureById(id, (c, fromMock) -> {
            if (isDestroyed()) return;
            capture = c;
            if (c == null) {
                // Deleted, or never saved: say so rather than show an empty or stand-in note.
                // A Toast, not the Snackbar helper: it must outlive this finishing activity.
                android.widget.Toast.makeText(this, fromMock && !OrphosApp.get().config().isDemoMode()
                        ? R.string.data_unavailable : R.string.captures_empty,
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (c.title != null) headerTitle.setText(c.title);
            if (!TextUtils.isEmpty(c.content)) {
                findViewById(R.id.summary_content_section).setVisibility(View.VISIBLE);
                if (isTyped(c.type)) {
                    ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_typed_heading);
                } else if (isAudio(c.type)) {
                    ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_transcript_heading);
                } else if (isImage(c.type)) {
                    ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_image_heading);
                }
                ((TextView) findViewById(R.id.summary_content)).setText(c.content);
            }
            ((TextView) findViewById(R.id.summary_text)).setText(c.summary != null ? c.summary : "");
            showPoints(c.keyPoints);
            if (isAudio(c.type)) {
                bindSavedAudio(c);
                return;
            }
            OrphosApp.get().repo().captureImage(c, (bmp, failed) -> {
                if (!isDestroyed()) showImage(bmp);
            });
        });
        findViewById(R.id.summary_save).setOnClickListener(v -> finish());
    }

    private void showPoints(List<String> keyPoints) {
        LinearLayout points = findViewById(R.id.summary_points);
        points.removeAllViews();
        if (keyPoints == null) return;
        float d = getResources().getDisplayMetrics().density;
        for (String k : keyPoints) {
            TextView t = new TextView(this);
            t.setText("•  " + k);
            t.setTextColor(getColor(R.color.text_secondary));
            t.setTextSize(14f);
            t.setLineSpacing(0f, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * d);
            t.setLayoutParams(lp);
            points.addView(t);
        }
    }

    private void showImage(Bitmap bmp) {
        if (bmp == null) return;
        ((ImageView) findViewById(R.id.summary_image)).setImageBitmap(bmp);
        findViewById(R.id.summary_image_card).setVisibility(View.VISIBLE);
    }

    /** A saved voice note: when it was recorded, and its recording fetched for playback. */
    private void bindSavedAudio(Capture c) {
        TextView meta = findViewById(R.id.summary_meta);
        meta.setText(getString(R.string.capture_meta_voice, nz(c.createdAt)));
        meta.setVisibility(View.VISIBLE);
        showAudioSection();
        if (!c.hasFile) { audioUnavailable(); return; }
        OrphosApp.get().repo().captureFile(c, getCacheDir(), (file, failed) -> {
            boolean fetched = file != null && !file.getAbsolutePath().equals(c.localFilePath);
            if (isDestroyed()) {
                //noinspection ResultOfMethodCallIgnored
                if (fetched) file.delete();
                return;
            }
            if (fetched) downloadedAudio = file;
            audio.load(file, this::audioUnavailable);
        });
    }

    /** Shows the recording card; its controls enable once the audio is prepared. */
    private void showAudioSection() {
        findViewById(R.id.summary_audio_section).setVisibility(View.VISIBLE);
        audio = new AudioPlayback(findViewById(R.id.summary_audio_play), findViewById(R.id.summary_audio_play_icon),
                findViewById(R.id.summary_audio_seek), findViewById(R.id.summary_audio_time));
    }

    private void audioUnavailable() {
        if (!isDestroyed()) ((TextView) findViewById(R.id.summary_audio_time)).setText(R.string.review_recording_unavailable);
    }

    // --- Review mode ---

    private void bindReview() {
        Intent in = getIntent();
        ((TextView) findViewById(R.id.summary_header_title)).setText(R.string.review_title);

        String notice = in.getStringExtra(EXTRA_REVIEW_NOTICE);
        if (!TextUtils.isEmpty(notice)) {
            TextView n = findViewById(R.id.summary_notice);
            n.setText(notice);
            n.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.summary_name_section).setVisibility(View.VISIBLE);
        EditText name = findViewById(R.id.summary_file_name);
        String title = in.getStringExtra(EXTRA_REVIEW_TITLE);
        String text = in.getStringExtra(EXTRA_REVIEW_TEXT);
        name.setText(!TextUtils.isEmpty(title) ? title : nameFrom(text));

        findViewById(R.id.summary_content_section).setVisibility(View.VISIBLE);
        findViewById(R.id.summary_content).setVisibility(View.GONE);
        EditText content = findViewById(R.id.summary_content_edit);
        content.setVisibility(View.VISIBLE);
        String type = in.getStringExtra(EXTRA_REVIEW_TYPE);
        if (isTyped(type)) {
            ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_typed_heading);
            content.setHint(R.string.review_typed_hint);
        } else if (isAudio(type)) {
            ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_transcript_heading);
            content.setHint(R.string.review_transcript_hint);
        } else if (isImage(type)) {
            ((TextView) findViewById(R.id.summary_content_heading)).setText(R.string.review_image_heading);
            content.setHint(R.string.review_image_hint);
        }
        content.setText(text != null ? text : "");

        findViewById(R.id.summary_text).setVisibility(View.GONE);
        EditText summary = findViewById(R.id.summary_text_edit);
        summary.setVisibility(View.VISIBLE);
        summary.setText(nz(in.getStringExtra(EXTRA_REVIEW_SUMMARY)));

        findViewById(R.id.summary_points).setVisibility(View.GONE);
        findViewById(R.id.summary_points_edit_card).setVisibility(View.VISIBLE);
        ArrayList<String> points = in.getStringArrayListExtra(EXTRA_REVIEW_POINTS);
        ((EditText) findViewById(R.id.summary_points_edit))
                .setText(points != null ? TextUtils.join("\n", points) : "");

        if (reviewFile != null && isAudio(type)) {
            showAudioSection();
            audio.load(new java.io.File(reviewFile), this::audioUnavailable);
        } else if (reviewFile != null) {
            showImage(com.orphos.mobile.core.capture.CaptureFiles.decodeForDisplay(reviewFile, MAX_DISPLAY_PX));
        }

        MaterialButton save = findViewById(R.id.summary_save);
        save.setText(R.string.capture_save);
        save.setOnClickListener(v -> saveReviewed());
    }

    private void saveReviewed() {
        if (saving) return;
        String name = text(R.id.summary_file_name);
        String content = text(R.id.summary_content_edit);
        String type = getIntent().getStringExtra(EXTRA_REVIEW_TYPE);
        if (name.isEmpty()) { toast(getString(R.string.review_file_name_required)); return; }
        // An image with no readable text is still worth keeping: the original itself is the note.
        if (content.isEmpty() && !(isImage(type) && reviewFile != null)) {
            toast(getString(R.string.review_text_required));
            return;
        }

        List<String> points = new ArrayList<>();
        for (String line : text(R.id.summary_points_edit).split("\n")) {
            String p = line.replaceFirst("^[•\\-*\\s]+", "").trim();
            if (!p.isEmpty()) points.add(p);
        }

        MaterialButton save = findViewById(R.id.summary_save);
        saving = true;
        save.setEnabled(false);
        save.setText(R.string.review_saving);
        String reviewMime = getIntent().getStringExtra(EXTRA_REVIEW_MIME);
        String mime = reviewFile == null ? null
                : !TextUtils.isEmpty(reviewMime) ? reviewMime
                : isAudio(type) ? "audio/mp4" : "image/png";
        // A typed note has no file: it's saved as text (mime type and channel follow its type).
        // A voice note keeps the transcript as heard, and an image the text as OCR read it,
        // next to the text as the user corrected it.
        OrphosApp.get().repo().saveReviewedCapture(reviewFile != null ? new java.io.File(reviewFile) : null, name,
                type != null ? type : Capture.TYPE_HANDWRITING, mime,
                content, getIntent().getStringExtra(EXTRA_REVIEW_TRANSCRIPT),
                text(R.id.summary_text_edit), points,
                getIntent().getStringArrayListExtra(EXTRA_REVIEW_ACTIONS),
                getIntent().getStringArrayListExtra(EXTRA_REVIEW_DECISIONS), (c, failed) -> {
                    if (isDestroyed()) return;
                    if (c == null) {
                        saving = false;
                        save.setEnabled(true);
                        save.setText(R.string.capture_save);
                        toast(getString(R.string.capture_not_saved));
                        return;
                    }
                    // A saved capture with failed=true is a demo save: on the device only, no web search.
                    int message = failed ? R.string.review_saved_local
                            : c.ingested ? R.string.review_saved : R.string.review_saved_not_indexed;
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
    }

    /** A default file name from the note's first line, when the AI suggested none. */
    private static String nameFrom(String text) {
        if (text == null) return "";
        String first = text.trim().split("\n", 2)[0].trim();
        return first.length() > MAX_NAME_CHARS ? first.substring(0, MAX_NAME_CHARS).trim() : first;
    }

    private String text(int id) {
        return ((EditText) findViewById(id)).getText().toString().trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean isTyped(String type) { return Capture.TYPE_TEXT.equalsIgnoreCase(type); }

    private static boolean isImage(String type) {
        return Capture.TYPE_IMAGE.equalsIgnoreCase(type) || Capture.TYPE_PHOTO.equalsIgnoreCase(type);
    }

    private static boolean isAudio(String type) {
        return Capture.TYPE_AUDIO.equalsIgnoreCase(type) || Capture.TYPE_VOICE.equalsIgnoreCase(type);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (audio != null) audio.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audio != null) audio.release();
        // Only the copy fetched for playback: a recording under review belongs to the recording screen.
        if (downloadedAudio != null) {
            //noinspection ResultOfMethodCallIgnored
            downloadedAudio.delete();
        }
    }

    private void toast(String msg) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show();
    }

    private void share() {
        String subject, body;
        if (reviewing) {
            subject = text(R.id.summary_file_name);
            String summary = text(R.id.summary_text_edit);
            body = summary.isEmpty() ? text(R.id.summary_content_edit) : summary;
        } else {
            if (capture == null) return;
            subject = capture.title;
            body = !TextUtils.isEmpty(capture.summary) ? capture.summary : nz(capture.content);
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(send, getString(R.string.capture_share)));
    }
}
