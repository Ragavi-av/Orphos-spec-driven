package com.orphos.mobile.core.i18n;

import java.time.LocalDate;
import java.time.chrono.JapaneseDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Locale-aware date formatting. For Japanese, shows the Gregorian date plus the
 * Reiwa-era date (令和) using java.time's JapaneseChronology — the dual-calendar
 * convention the regional UI/UX standards call for. US/other locales use month-name form.
 */
public final class DateFormatters {

    private DateFormatters() { }

    public static String today() {
        return forDate(LocalDate.now());
    }

    public static String forDate(LocalDate date) {
        Locale l = Locale.getDefault();
        if ("ja".equals(l.getLanguage())) {
            String gregorian = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN));
            String era = JapaneseDate.from(date)
                    .format(DateTimeFormatter.ofPattern("Gy年", Locale.JAPAN)); // e.g. 令和8年
            return gregorian + "（" + era + "）";
        }
        return date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
    }
}
