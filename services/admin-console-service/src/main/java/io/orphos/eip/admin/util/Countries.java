package io.orphos.eip.admin.util;

import io.orphos.eip.admin.service.BrandingService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The primary countries a tenant can operate in, and what each one resolves to.
 *
 * <p>A workspace's language, time zone, reporting currency and number notation
 * are four settings that have to agree — a board showing Japanese text over IST
 * clocks and rupee tiles is worse than either consistent arrangement. They used
 * to be three independent fields on the branding profile, so nothing stopped
 * them drifting apart. The country is the one choice an administrator actually
 * makes, and this catalog derives the rest from it.</p>
 *
 * <p>This is the AUTHORITY: {@code BrandingService} resolves the profile through
 * it on every save, so the persisted language/zone/currency always match the
 * stored country. The SPA carries a mirror of this table
 * ({@code eip-shell-context/country.js}) purely so the picker has labels and the
 * board can render before the profile fetch lands — add a country in both
 * places, and give it a {@code language} the SPA actually ships a catalog for,
 * or the tenant default silently falls back to English.</p>
 *
 * <p>The currency here is a DEFAULT unit for money whose source states none, not
 * a conversion target: converting needs an FX rate with an as-of date, which
 * branding does not hold.</p>
 */
public final class Countries {
    private Countries() {}

    /**
     * @param code         ISO 3166-1 alpha-2
     * @param language     BCP-47 language subtag; must exist in the SPA's SUPPORTED_LANGUAGES
     * @param timezone     IANA zone id — the wall clock every timestamp and greeting resolves against
     * @param currency     ISO 4217 reporting currency
     * @param numberLocale BCP-47 tag deciding NOTATION: crore/lakh vs 万/億, grouping, date order
     */
    public record Country(String code, String language, String timezone,
                          String currency, String numberLocale) {}

    private static final Map<String, Country> BY_CODE = new LinkedHashMap<>();

    static {
        register(new Country("IN", "en", "Asia/Kolkata", "INR", "en-IN"));
        register(new Country("JP", "ja", "Asia/Tokyo",   "JPY", "ja-JP"));
    }

    private static void register(Country c) {
        BY_CODE.put(c.code(), c);
    }

    /** The catalog, in display order. */
    public static Map<String, Country> all() {
        return Map.copyOf(BY_CODE);
    }

    /** Resolve a country code, case-insensitively. Empty for blank or unknown. */
    public static Optional<Country> byCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_CODE.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * The canonical stored form of a country code: uppercase, or "" when blank
     * or unrecognised.
     *
     * <p>An unknown code is dropped rather than rejected: it would resolve to no
     * language, zone or currency, so persisting it would leave the profile
     * claiming a country whose settings nothing could produce. "" is a real
     * state — the tenant falls back to platform defaults, which is where every
     * profile saved before the country existed already sits.</p>
     */
    public static String normalize(String code) {
        return byCode(code).map(Country::code).orElse("");
    }
}
