package com.orphos.mobile.ui.scribbles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules behind the Capture image screen's live guidance, kept free of Android types so
 * they can be unit tested: how the detected text area sits in the frame (AI framing), how
 * the frame is lit, and which zoom levels the camera can offer.
 */
final class CaptureGuidance {

    private CaptureGuidance() { }

    enum Framing {
        /** No text found in the frame yet. */
        SEARCHING,
        /** Text found, but it runs off an edge — part of the content is outside the frame. */
        CUT_OFF,
        /** Text found, but it fills too little of the frame to read reliably. */
        TOO_SMALL,
        GOOD
    }

    enum Lighting { UNKNOWN, GOOD, DARK, GLARE }

    /** Content closer than this share of the frame's width/height to an edge counts as cut off. */
    static final float EDGE_MARGIN = 0.02f;
    /** Content covering less than this share of the frame's area is too far away. */
    static final float MIN_AREA = 0.10f;
    /** Mean luma (0–255) below which the frame is too dark. */
    static final int DARK_MEAN = 60;
    /** Share of blown-out pixels above which a reflection is washing text out. */
    static final float GLARE_FRACTION = 0.06f;
    /** Two consecutive content boxes overlapping at least this much mean the phone is steady. */
    static final float STEADY_IOU = 0.80f;

    /**
     * @param content the union of the detected text boxes, as {left, top, right, bottom} in the
     *                frame's coordinates (it may extend past the frame), or null when none
     */
    static Framing framing(float[] content, float frameW, float frameH) {
        if (content == null || frameW <= 0 || frameH <= 0) return Framing.SEARCHING;
        float mx = frameW * EDGE_MARGIN, my = frameH * EDGE_MARGIN;
        if (content[0] < mx || content[1] < my || content[2] > frameW - mx || content[3] > frameH - my) {
            return Framing.CUT_OFF;
        }
        float area = (content[2] - content[0]) * (content[3] - content[1]);
        if (area < frameW * frameH * MIN_AREA) return Framing.TOO_SMALL;
        return Framing.GOOD;
    }

    static Lighting lighting(double meanLuma, double brightFraction) {
        if (meanLuma < DARK_MEAN) return Lighting.DARK;
        if (brightFraction > GLARE_FRACTION) return Lighting.GLARE;
        return Lighting.GOOD;
    }

    /** Intersection over union of two {left, top, right, bottom} boxes; 0 when either is null. */
    static float iou(float[] a, float[] b) {
        if (a == null || b == null) return 0f;
        float iw = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        float ih = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (iw <= 0 || ih <= 0) return 0f;
        float inter = iw * ih;
        float union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
        return union <= 0 ? 0f : inter / union;
    }

    /**
     * The zoom levels to offer, as the stock camera does: the ultra-wide minimum when the
     * device has one (below 1x), then 1x, 2x and 5x as far as the camera's range allows.
     */
    static List<Float> zoomPresets(float min, float max) {
        List<Float> presets = new ArrayList<>();
        if (min < 0.95f) presets.add((float) Math.ceil(min * 10f) / 10f);
        presets.add(1f);
        if (max >= 2f) presets.add(2f);
        if (max >= 5f) presets.add(5f);
        return presets;
    }

    /** The preset the current ratio belongs to: the highest one at or below it. */
    static int activeZoom(List<Float> presets, float ratio) {
        int active = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i) <= ratio + 0.05f) active = i;
        }
        return active;
    }

    /** "1", "2", "0.6", "1.4" — whole ratios without a decimal. */
    static String zoomLabel(float ratio) {
        if (Math.abs(ratio - Math.round(ratio)) < 0.05f) return String.valueOf(Math.round(ratio));
        return String.format(Locale.US, "%.1f", ratio);
    }
}
