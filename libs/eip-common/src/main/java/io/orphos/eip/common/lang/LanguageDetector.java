package io.orphos.eip.common.lang;

/**
 * Lightweight, dependency-free language detection tuned for the bilingual
 * Japanese + English deployment.
 *
 * <p>Distinguishing Japanese from English is reliably done by Unicode script,
 * which avoids pulling a statistical detector onto every service's classpath
 * and — crucially — is far more accurate than n-gram detectors on the short
 * strings Orphos indexes (document titles, email subjects, single graph rows):
 * <ul>
 *   <li>Any Hiragana or Katakana is unique to Japanese → decisive {@code "ja"}.</li>
 *   <li>A sufficiently high ratio of CJK ideographs (kanji) is treated as
 *       Japanese as well; Chinese is out of scope for this deployment.</li>
 *   <li>Everything else falls back to {@code "en"}.</li>
 * </ul>
 *
 * <p>Returns ISO 639-1 codes ({@code "ja"} / {@code "en"}) or {@code "und"} for
 * blank input. Shared in eip-common so doc-ingest, nl-query and (later)
 * mail-insight all classify text the same way.
 */
public final class LanguageDetector {

    private LanguageDetector() {}

    // Fraction of CJK ideographs (of all letters) above which kana-less text is
    // treated as Japanese. Kana is the primary, decisive signal; this fallback
    // only fires for kana-less CJK (pure-kanji labels, or kanji content terms in
    // light English framing). Kept low enough to catch those yet high enough that
    // English prose quoting a stray ideograph (e.g. "円", ratio ~0.02) stays "en".
    private static final double KANJI_JA_THRESHOLD = 0.15;

    /** Cap the scan: a leading prefix is more than enough to decide the script. */
    private static final int MAX_SCAN_CHARS = 4000;

    /**
     * Detects the dominant language of {@code text}.
     *
     * @return {@code "ja"}, {@code "en"}, or {@code "und"} when the input is blank.
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) return "und";

        int letters = 0;
        int cjk = 0;
        int limit = Math.min(text.length(), MAX_SCAN_CHARS);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);

            // Kana (incl. halfwidth katakana) is exclusive to Japanese — decide immediately.
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                    || isHalfwidthKatakana(c)) {
                return "ja";
            }

            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                cjk++;
                letters++;
            } else if (Character.isLetter(c)) {
                letters++;
            }
        }

        if (letters > 0 && (double) cjk / letters >= KANJI_JA_THRESHOLD) {
            return "ja";
        }
        return "en";
    }

    /** Convenience predicate: true when {@link #detect(String)} resolves to Japanese. */
    public static boolean isJapanese(String text) {
        return "ja".equals(detect(text));
    }

    private static boolean isHalfwidthKatakana(char c) {
        return c >= 0xFF66 && c <= 0xFF9D;
    }
}
