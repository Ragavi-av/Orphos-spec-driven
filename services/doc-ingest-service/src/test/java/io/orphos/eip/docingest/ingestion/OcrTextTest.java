package io.orphos.eip.docingest.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture review summarises only what this lets through, so OCR noise must be
 * rejected (or the AI invents a note) while real notes, numbers and Japanese pass.
 */
class OcrTextTest {

    @Test
    void stripsTwoLineCameraWatermark() {
        String ocr = "Testing Record this\nShot on motorola edge 50\nANT STUDIOZ 28 Sept 2026, 3:21 pm";
        assertEquals("Testing Record this", OcrText.stripWatermark(ocr));
    }

    @Test
    void stripsWatermarkWhenStampLineComesFirst() {
        String ocr = "Agenda\n28 Sept 2026, 3:21 pm\nShot on motorola edge 50";
        assertEquals("Agenda", OcrText.stripWatermark(ocr));
    }

    @Test
    void keepsDatesThatAreNotPartOfAWatermark() {
        String ocr = "Board review 28 Sept 2026\nBring the budget";
        assertEquals(ocr, OcrText.stripWatermark(ocr));
    }

    @Test
    void rejectsSymbolNoiseFromHandwriting() {
        String noise = "é A. ou Sas. {i p= edb sa: lane Seer A. , i. plush ok = els => nh addij | "
                + "2 5 ae Sh ls a a o tr | CYAN '- 1) {\\ CG n 2) [Aw 1 = RAR.";
        assertFalse(OcrText.looksReadable(noise));
        assertFalse(OcrText.looksReadable("la)\\ P \\) = ) t h | U : | e S iS }"));
        assertFalse(OcrText.looksReadable(""));
    }

    @Test
    void acceptsRealNotes() {
        assertTrue(OcrText.looksReadable("Meeting with the client tomorrow at 10 AM"));
        assertTrue(OcrText.looksReadable("Selection sort: 13 46 24 52 20 9 select the minimum and swap"));
        assertTrue(OcrText.looksReadable("明日の会議 10時 資料を準備"));
    }
}
