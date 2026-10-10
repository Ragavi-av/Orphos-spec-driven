package com.orphos.mobile.core.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Recognition reads one line at a time, so a paragraph is only recognised correctly if
 * its strokes are grouped into the lines the user actually wrote.
 */
public class HandwritingRecognizerTest {

    /** A stroke spanning [top, bottom] vertically at x. */
    private static float[] stroke(float x, float top, float bottom) {
        return new float[] { x, top, 0f, x + 5f, bottom, 10f };
    }

    @Test
    public void strokesBelowEveryLineStartANewLine() {
        float[] a = stroke(10, 100, 140), b = stroke(40, 100, 145), c = stroke(10, 200, 240);
        List<List<float[]>> lines = HandwritingRecognizer.groupIntoLines(Arrays.asList(a, b, c));

        assertEquals(2, lines.size());
        assertEquals(Arrays.asList(a, b), lines.get(0));
        assertEquals(Arrays.asList(c), lines.get(1));
    }

    @Test
    public void aDotAddedLaterJoinsTheLineItBelongsTo() {
        float[] word1 = stroke(10, 100, 150), word2 = stroke(10, 200, 250);
        float[] dot = stroke(30, 118, 122); // the i on line one, dotted after line two was written
        List<List<float[]>> lines = HandwritingRecognizer.groupIntoLines(Arrays.asList(word1, word2, dot));

        assertEquals(2, lines.size());
        assertSame(dot, lines.get(0).get(1));
    }

    @Test
    public void aStrokeInTheGapBetweenLinesGoesToTheNearestOne() {
        float[] line1 = stroke(10, 100, 150), line2 = stroke(10, 300, 350);
        float[] comma = stroke(60, 160, 164); // just under line one
        List<List<float[]>> lines = HandwritingRecognizer.groupIntoLines(Arrays.asList(line1, line2, comma));

        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).size());
    }

    @Test
    public void degenerateStrokesAreIgnored() {
        List<List<float[]>> lines = HandwritingRecognizer.groupIntoLines(Arrays.asList(new float[0], new float[] { 1f }));
        assertEquals(0, lines.size());
    }
}
