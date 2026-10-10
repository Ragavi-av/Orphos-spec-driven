package io.orphos.eip.docingest.ingestion.structure;

/**
 * Cheap deterministic token estimate for chunk budgeting.
 *
 * <p>CJK-aware: Japanese/Chinese text has no spaces, so the old
 * {@code max(words, chars/4)} massively under-counted it (a whole sentence
 * looked like one "word" and chars/4 assumed ~4 chars/token, which holds for
 * Latin but not CJK where ~1 char ≈ 1 token). Each CJK character is now counted
 * as ~1 token; the remaining non-CJK text keeps the Latin/Indic estimate. This
 * keeps chunks within the real token budget for bilingual JA+EN content.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int len = text.length();
        int cjk = 0;
        for (int i = 0; i < len; i++) {
            if (isCjk(text.charAt(i))) cjk++;
        }
        int words = text.trim().split("\\s+").length;
        // CJK chars ≈ 1 token each; non-CJK text estimated at ~4 chars/token.
        int byChars = (int) Math.ceil((len - cjk) / 4.0) + cjk;
        return Math.max(words, byChars);
    }

    /** True for Han ideographs, kana, and halfwidth katakana — the space-less scripts. */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || b == Character.UnicodeBlock.HIRAGANA
            || b == Character.UnicodeBlock.KATAKANA
            || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
            || (c >= 0xFF66 && c <= 0xFF9D); // halfwidth katakana
    }
}
