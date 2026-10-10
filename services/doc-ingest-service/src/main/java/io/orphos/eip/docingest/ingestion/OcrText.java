package io.orphos.eip.docingest.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Clean-up and a plausibility check for text OCR'd from a photo, used by the
 * capture review step ({@code /extract}).
 *
 * <p>OCR engines read everything in the frame, including the "Shot on &lt;phone&gt;"
 * watermark cameras stamp into the corner, and on handwriting they emit symbol
 * noise rather than failing. Summarising that noise makes the AI invent a note
 * that was never written, so the caller skips the summary when the text does not
 * look like language and lets the user type it instead.</p>
 */
final class OcrText {

    /** Camera watermark: "Shot on motorola edge 50", "Shot with OnePlus", "Shot by …". */
    private static final Pattern WATERMARK = Pattern.compile("(?i)^\\s*shot\\s+(on|with|by)\\b.*$");
    /** The watermark's second line: a short line carrying the capture date ("ANT STUDIOZ 28 Sept 2026, 2:02 pm"). */
    private static final Pattern DATE_STAMP = Pattern.compile(
            "(?i)\\b\\d{1,2}\\s+[a-z]{3,9}\\.?\\s+\\d{4}\\b|\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b");
    private static final int MAX_STAMP_LINE = 48;

    private static final Pattern VOWEL = Pattern.compile("(?i)[aeiouy]");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern NUMERIC = Pattern.compile("[\\d.,:;/\\-+=()]+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^\\p{Punct}+|\\p{Punct}+$");

    /** Share of tokens that must read as words for the text to count as readable. */
    static final double MIN_WORD_RATIO = 0.5;
    static final int MIN_WORDS = 2;

    private OcrText() {}

    /** Removes camera watermark lines; everything else is kept exactly as recognised. */
    static String stripWatermark(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\\R", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (WATERMARK.matcher(line).matches()) {
                // The watermark often continues on the next line with a name and the date.
                if (i + 1 < lines.length && isStampLine(lines[i + 1])) i++;
                continue;
            }
            // A stamp line can also precede the "Shot on" line, depending on reading order.
            if (isStampLine(line) && i + 1 < lines.length && WATERMARK.matcher(lines[i + 1]).matches()) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).strip();
    }

    private static boolean isStampLine(String line) {
        String t = line.strip();
        return !t.isEmpty() && t.length() <= MAX_STAMP_LINE && DATE_STAMP.matcher(t).find();
    }

    /**
     * True when the text reads as language rather than OCR noise: at least
     * {@link #MIN_WORDS} word-like tokens, making up {@link #MIN_WORD_RATIO} of the
     * non-numeric tokens. A word is 3+ letters with a vowel, or any CJK/kana run
     * (Japanese has no spaces, so one token can be a whole phrase).
     */
    static boolean looksReadable(String text) {
        if (text == null || text.isBlank()) return false;
        int words = 0;
        int counted = 0;
        for (String raw : text.strip().split("\\s+")) {
            String token = EDGE_PUNCT.matcher(raw).replaceAll("");
            if (token.isEmpty() || NUMERIC.matcher(token).matches()) continue; // numbers are neutral
            counted++;
            if (isWord(token)) words++;
        }
        return words >= MIN_WORDS && words >= MIN_WORD_RATIO * counted;
    }

    private static boolean isWord(String token) {
        if (CJK.matcher(token).find()) return true;
        return token.length() >= 3 && LETTERS.matcher(token).matches() && VOWEL.matcher(token).find();
    }
}
