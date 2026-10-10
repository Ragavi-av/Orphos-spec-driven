package com.orphos.mobile.core.capture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device handwriting recognition (ML Kit Digital Ink) for Scribbles. Works on the
 * canvas strokes rather than pixels, which is what makes cursive and quick handwriting
 * readable; the ink itself never leaves the phone.
 *
 * <p>The recogniser reads one line of writing at a time, so strokes are first grouped
 * into lines, then each line is recognised with the previous line as context. The
 * result is {@code null} whenever recognition is unavailable — model not downloadable
 * (offline first run), unsupported language, timeout — and callers then fall back to
 * server-side OCR of the image.</p>
 */
public final class HandwritingRecognizer {

    public interface Callback { void onResult(@Nullable String text); }

    private static final String TAG = "HandwritingRecognizer";
    /** First use downloads the language model (~20 MB); give up rather than hang. */
    private static final long TIMEOUT_MS = 25_000;
    private static final int PRE_CONTEXT_CHARS = 20;

    private HandwritingRecognizer() { }

    /**
     * Recognises {@code strokes} ({x, y, t, …} per stroke, as DrawingView.inkStrokes()
     * returns them) written on a {@code width}×{@code height} canvas. Calls back once, on
     * the main thread.
     */
    public static void recognize(List<float[]> strokes, float width, float height, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean done = new AtomicBoolean(false);
        Callback once = text -> { if (done.compareAndSet(false, true)) main.post(() -> cb.onResult(text)); };
        main.postDelayed(() -> once.onResult(null), TIMEOUT_MS);

        List<List<float[]>> lines = groupIntoLines(strokes);
        if (lines.isEmpty()) { once.onResult(null); return; }

        DigitalInkRecognitionModel model = modelForDevice();
        if (model == null) { once.onResult(null); return; }

        RemoteModelManager models = RemoteModelManager.getInstance();
        models.isModelDownloaded(model)
                .continueWithTask(t -> Boolean.TRUE.equals(t.getResult())
                        ? com.google.android.gms.tasks.Tasks.forResult((Void) null)
                        : models.download(model, new DownloadConditions.Builder().build()))
                .addOnSuccessListener(v -> {
                    DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(
                            DigitalInkRecognizerOptions.builder(model).build());
                    recognizeLines(recognizer, lines, 0, new StringBuilder(), width, height, text -> {
                        recognizer.close();
                        once.onResult(text);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Handwriting model unavailable: " + e.getMessage());
                    once.onResult(null);
                });
    }

    /** Recognise line {@code i}, then the next with this one as pre-context. */
    private static void recognizeLines(DigitalInkRecognizer recognizer, List<List<float[]>> lines, int i,
                                       StringBuilder out, float width, float height, Callback cb) {
        if (i >= lines.size()) {
            String text = out.toString().trim();
            cb.onResult(text.isEmpty() ? null : text);
            return;
        }
        String soFar = out.toString();
        String pre = soFar.length() > PRE_CONTEXT_CHARS ? soFar.substring(soFar.length() - PRE_CONTEXT_CHARS) : soFar;
        RecognitionContext ctx = RecognitionContext.builder()
                .setPreContext(pre.replace('\n', ' '))
                .setWritingArea(new WritingArea(width, height))
                .build();
        recognizer.recognize(toInk(lines.get(i)), ctx)
                .addOnSuccessListener(result -> {
                    if (!result.getCandidates().isEmpty()) {
                        String line = result.getCandidates().get(0).getText().trim();
                        if (!line.isEmpty()) {
                            if (out.length() > 0) out.append('\n');
                            out.append(line);
                        }
                    }
                    recognizeLines(recognizer, lines, i + 1, out, width, height, cb);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Line " + i + " not recognised: " + e.getMessage());
                    recognizeLines(recognizer, lines, i + 1, out, width, height, cb);
                });
    }

    /** The model for the device language, falling back to US English when unsupported. */
    @Nullable
    private static DigitalInkRecognitionModel modelForDevice() {
        Locale locale = Locale.getDefault();
        String[] tags = { locale.toLanguageTag(), locale.getLanguage(), "en-US" };
        for (String tag : tags) {
            try {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
                if (id != null) return DigitalInkRecognitionModel.builder(id).build();
            } catch (MlKitException e) {
                Log.d(TAG, "No handwriting model for " + tag);
            }
        }
        return null;
    }

    private static Ink toInk(List<float[]> strokes) {
        Ink.Builder ink = Ink.builder();
        for (float[] s : strokes) {
            Ink.Stroke.Builder stroke = Ink.Stroke.builder();
            for (int i = 0; i + 2 < s.length; i += 3) {
                stroke.addPoint(Ink.Point.create(s[i], s[i + 1], (long) s[i + 2]));
            }
            ink.addStroke(stroke.build());
        }
        return ink.build();
    }

    /**
     * Groups strokes (in the order written) into lines of writing. A stroke joins the line
     * whose vertical band holds its centre — so dotting an i or crossing a t after the
     * word still lands on that word's line — and starts a new line when its centre falls
     * below every line so far.
     */
    static List<List<float[]>> groupIntoLines(List<float[]> strokes) {
        List<List<float[]>> lines = new ArrayList<>();
        List<float[]> bands = new ArrayList<>(); // {top, bottom} per line
        for (float[] s : strokes) {
            if (s.length < 3) continue;
            float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
            for (int i = 1; i < s.length; i += 3) { top = Math.min(top, s[i]); bottom = Math.max(bottom, s[i]); }
            float centre = (top + bottom) / 2f;

            int target = -1;
            for (int l = bands.size() - 1; l >= 0; l--) {
                if (centre >= bands.get(l)[0] && centre <= bands.get(l)[1]) { target = l; break; }
            }
            if (target < 0 && !bands.isEmpty()) {
                float lowest = -Float.MAX_VALUE;
                for (float[] b : bands) lowest = Math.max(lowest, b[1]);
                if (centre <= lowest) target = nearest(bands, centre);
            }
            if (target < 0) {
                lines.add(new ArrayList<>());
                bands.add(new float[] { top, bottom });
                target = lines.size() - 1;
            }
            lines.get(target).add(s);
            float[] b = bands.get(target);
            b[0] = Math.min(b[0], top);
            b[1] = Math.max(b[1], bottom);
        }
        return lines;
    }

    private static int nearest(List<float[]> bands, float y) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int l = 0; l < bands.size(); l++) {
            float[] b = bands.get(l);
            float d = y < b[0] ? b[0] - y : y > b[1] ? y - b[1] : 0;
            if (d < bestDist) { bestDist = d; best = l; }
        }
        return best;
    }
}
