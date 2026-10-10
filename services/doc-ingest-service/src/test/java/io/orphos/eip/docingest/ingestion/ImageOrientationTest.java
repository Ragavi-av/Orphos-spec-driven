package io.orphos.eip.docingest.ingestion;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each EXIF orientation must land the image's top-left pixel where a viewer shows
 * it, or OCR still reads the page sideways or mirrored.
 */
class ImageOrientationTest {

    private static final int MARK = 0xFF0000;
    private static final int W = 4;
    private static final int H = 2;

    /** A W×H image, black except its stored top-left pixel. */
    private static BufferedImage marked() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, MARK);
        return img;
    }

    private static void assertMarkAt(int orientation, int x, int y, int w, int h) {
        BufferedImage out = ImageOrientation.transform(marked(), orientation);
        assertEquals(w, out.getWidth(), "width for orientation " + orientation);
        assertEquals(h, out.getHeight(), "height for orientation " + orientation);
        assertEquals(MARK, out.getRGB(x, y) & 0xFFFFFF, "mark position for orientation " + orientation);
    }

    @Test
    void mirrorsAndRotatesWithoutSwappingSides() {
        assertMarkAt(2, W - 1, 0, W, H);
        assertMarkAt(3, W - 1, H - 1, W, H);
        assertMarkAt(4, 0, H - 1, W, H);
    }

    @Test
    void quarterTurnsSwapWidthAndHeight() {
        assertMarkAt(5, 0, 0, H, W);          // transpose
        assertMarkAt(6, H - 1, 0, H, W);      // phone held upright: rotate 90° clockwise
        assertMarkAt(7, H - 1, W - 1, H, W);  // transverse
        assertMarkAt(8, 0, W - 1, H, W);      // rotate 270° clockwise
    }
}
