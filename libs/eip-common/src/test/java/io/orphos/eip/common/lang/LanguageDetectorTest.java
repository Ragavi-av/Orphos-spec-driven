package io.orphos.eip.common.lang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    @Test
    void blankInputIsUndetermined() {
        assertThat(LanguageDetector.detect(null)).isEqualTo("und");
        assertThat(LanguageDetector.detect("")).isEqualTo("und");
        assertThat(LanguageDetector.detect("   ")).isEqualTo("und");
    }

    @Test
    void plainEnglishIsEn() {
        assertThat(LanguageDetector.detect("The quarterly budget review is overdue.")).isEqualTo("en");
        assertThat(LanguageDetector.detect("Incident P1: database outage affecting checkout")).isEqualTo("en");
    }

    @Test
    void hiraganaOrKatakanaIsJa() {
        assertThat(LanguageDetector.detect("ありがとうございます")).isEqualTo("ja");          // hiragana
        assertThat(LanguageDetector.detect("コンピューター")).isEqualTo("ja");                  // katakana
        assertThat(LanguageDetector.detect("ｺﾝﾋﾟｭｰﾀ")).isEqualTo("ja");                        // halfwidth katakana
    }

    @Test
    void kanjiHeavyTextIsJa() {
        assertThat(LanguageDetector.detect("予算承認の確認が必要です")).isEqualTo("ja");
    }

    @Test
    void mixedJapaneseAndLatinIsJaWhenKanaPresent() {
        // Bilingual subject lines: a single kana run is decisive for Japanese.
        assertThat(LanguageDetector.detect("AWS インシデント対応のレビュー")).isEqualTo("ja");
        assertThat(LanguageDetector.detect("Re: 見積書 (please review)")).isEqualTo("ja");
    }

    @Test
    void mostlyLatinWithStrayKanjiStaysEn() {
        // A lone ideograph below the threshold should not flip a clearly English string.
        String mostlyEnglish = "Please review the attached report on system 円 conversion rates today.";
        assertThat(LanguageDetector.detect(mostlyEnglish)).isEqualTo("en");
    }
}
