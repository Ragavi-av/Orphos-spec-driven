package com.orphos.mobile.ui.scribbles;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class CaptureGuidanceTest {

    private static final float W = 1000, H = 1500;

    @Test
    public void noTextIsSearching() {
        assertEquals(CaptureGuidance.Framing.SEARCHING, CaptureGuidance.framing(null, W, H));
    }

    @Test
    public void textRunningOffAnEdgeIsCutOff() {
        float[] box = { -20, 300, 800, 900 };
        assertEquals(CaptureGuidance.Framing.CUT_OFF, CaptureGuidance.framing(box, W, H));
    }

    @Test
    public void smallTextFarAwayIsTooSmall() {
        float[] box = { 400, 600, 600, 800 }; // 200x200 of 1000x1500 ≈ 2.7%
        assertEquals(CaptureGuidance.Framing.TOO_SMALL, CaptureGuidance.framing(box, W, H));
    }

    @Test
    public void wellPlacedTextIsGood() {
        float[] box = { 100, 200, 900, 1200 };
        assertEquals(CaptureGuidance.Framing.GOOD, CaptureGuidance.framing(box, W, H));
    }

    @Test
    public void lightingClassification() {
        assertEquals(CaptureGuidance.Lighting.DARK, CaptureGuidance.lighting(40, 0));
        assertEquals(CaptureGuidance.Lighting.GLARE, CaptureGuidance.lighting(180, 0.2));
        assertEquals(CaptureGuidance.Lighting.GOOD, CaptureGuidance.lighting(150, 0.01));
    }

    @Test
    public void steadyContentOverlapsHeavily() {
        float[] a = { 100, 100, 900, 900 };
        float[] b = { 110, 105, 905, 910 };
        float[] far = { 950, 950, 990, 990 };
        assertEquals(true, CaptureGuidance.iou(a, b) >= CaptureGuidance.STEADY_IOU);
        assertEquals(0f, CaptureGuidance.iou(a, far), 0f);
        assertEquals(0f, CaptureGuidance.iou(a, null), 0f);
    }

    @Test
    public void zoomPresetsFollowTheCameraRange() {
        assertEquals(Arrays.asList(0.5f, 1f, 2f, 5f), CaptureGuidance.zoomPresets(0.5f, 10f));
        assertEquals(Arrays.asList(0.6f, 1f, 2f), CaptureGuidance.zoomPresets(0.55f, 4f));
        assertEquals(Arrays.asList(1f), CaptureGuidance.zoomPresets(1f, 1f));
        assertEquals(Arrays.asList(1f, 2f), CaptureGuidance.zoomPresets(1f, 3f));
    }

    @Test
    public void activeZoomAndLabels() {
        java.util.List<Float> p = Arrays.asList(0.5f, 1f, 2f);
        assertEquals(0, CaptureGuidance.activeZoom(p, 0.7f));
        assertEquals(1, CaptureGuidance.activeZoom(p, 1.4f));
        assertEquals(2, CaptureGuidance.activeZoom(p, 3f));
        assertEquals("1", CaptureGuidance.zoomLabel(1.02f));
        assertEquals("0.6", CaptureGuidance.zoomLabel(0.6f));
        assertEquals("1.4", CaptureGuidance.zoomLabel(1.4f));
    }
}
