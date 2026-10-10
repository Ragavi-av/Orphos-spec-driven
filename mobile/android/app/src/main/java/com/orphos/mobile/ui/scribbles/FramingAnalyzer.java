package com.orphos.mobile.ui.scribbles;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.mlkit.vision.MlKitAnalyzer;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.Executor;

/**
 * Live analysis for AI framing and Auto Detect. Each preview frame is measured for lighting
 * (from its luma plane) and run through on-device text detection; the listener gets the area
 * the text covers, already mapped into the PreviewView's coordinates, plus the lighting.
 * Frames are analysed on the device only and never stored.
 */
final class FramingAnalyzer implements ImageAnalysis.Analyzer {

    interface Listener {
        /** On the main thread. {@code content} is in PreviewView coordinates, null when no text is seen. */
        void onFrame(@Nullable Rect content, CaptureGuidance.Lighting lighting);
    }

    /** Luma is sampled on a grid this many pixels apart: plenty for exposure, cheap per frame. */
    private static final int SAMPLE_STEP = 8;
    private static final int BLOWN_OUT = 250;

    private final TextRecognizer recognizer;
    private final MlKitAnalyzer detector;
    /** Written on the analysis thread before the frame goes to detection, read with its result. */
    private volatile CaptureGuidance.Lighting lighting = CaptureGuidance.Lighting.UNKNOWN;

    FramingAnalyzer(Executor mainExecutor, Listener listener) {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        detector = new MlKitAnalyzer(Collections.singletonList(recognizer),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, mainExecutor,
                result -> listener.onFrame(contentBounds(result.getValue(recognizer)), lighting));
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        lighting = measureLighting(image);
        detector.analyze(image); // closes the image once detection finishes
    }

    @Nullable
    @Override
    public Size getDefaultTargetResolution() { return detector.getDefaultTargetResolution(); }

    @Override
    public int getTargetCoordinateSystem() { return detector.getTargetCoordinateSystem(); }

    @Override
    public void updateTransform(@Nullable Matrix matrix) { detector.updateTransform(matrix); }

    void close() { recognizer.close(); }

    /** The union of every text block with real content; stray single glyphs are ignored. */
    @Nullable
    private static Rect contentBounds(@Nullable Text text) {
        if (text == null) return null;
        Rect union = null;
        for (Text.TextBlock block : text.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null || block.getText().trim().length() < 2) continue;
            if (union == null) union = new Rect(box); else union.union(box);
        }
        return union;
    }

    private static CaptureGuidance.Lighting measureLighting(ImageProxy image) {
        ImageProxy.PlaneProxy luma = image.getPlanes()[0];
        ByteBuffer buf = luma.getBuffer();
        int rowStride = luma.getRowStride(), pixelStride = luma.getPixelStride();
        Rect crop = image.getCropRect();
        long sum = 0;
        int samples = 0, bright = 0;
        for (int y = crop.top; y < crop.bottom; y += SAMPLE_STEP) {
            for (int x = crop.left; x < crop.right; x += SAMPLE_STEP) {
                int index = y * rowStride + x * pixelStride;
                if (index >= buf.limit()) continue;
                int v = buf.get(index) & 0xFF;
                sum += v;
                samples++;
                if (v >= BLOWN_OUT) bright++;
            }
        }
        if (samples == 0) return CaptureGuidance.Lighting.UNKNOWN;
        return CaptureGuidance.lighting((double) sum / samples, (double) bright / samples);
    }
}
