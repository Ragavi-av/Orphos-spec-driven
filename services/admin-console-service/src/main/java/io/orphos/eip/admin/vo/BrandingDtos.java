package io.orphos.eip.admin.vo;

import io.orphos.eip.admin.util.Countries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Branding-system DTOs. */
public final class BrandingDtos {
    private BrandingDtos() {}

    public record ColorScheme(
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String primary,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String secondary,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String accent,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String background,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String text
    ) {}

    public record ThemeRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 20) String mode,        // "light" | "dark" | "custom"
            ColorScheme colors,
            @Size(max = 65536) String customCss          // raw CSS, will be sanitised
    ) {}

    public record ThemeView(String id, String name, String mode, ColorScheme colors,
                            String sanitizedCss, Instant updatedAt) {}

    public record BrandingProfileRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 80) String productName,
            @Size(max = 200) String tagline,
            // The workspace's primary country as ISO 3166-1 alpha-2 ("IN", "JP").
            // The ONE locale setting an administrator picks: the service resolves
            // defaultLanguage, timezone, currency and numberLocale from it (see
            // Countries) so those four can never disagree. Blank = no country,
            // which leaves the tenant on platform defaults and is where every
            // profile saved before this field existed sits.
            @Size(max = 2) @Pattern(regexp = "^$|^[A-Za-z]{2}$") String country,
            // Derived from `country` whenever one is set — a value sent here is
            // then ignored. Still accepted, and still authoritative, for a
            // country-less profile: the platform-default template carries these
            // without naming a country.
            @Size(max = 10) @Pattern(regexp = "^$|^[a-z]{2}(-[A-Za-z0-9]{2,8})?$") String defaultLanguage,
            String activeThemeId,
            String logoAssetId,
            String backgroundAssetId,
            String faviconAssetId,
            // Tenant-wide typography — deliberately part of the profile, NOT the
            // colour theme, so the font can be changed without touching or
            // re-selecting a colour scheme. Blank fontFamily / null|0 fontSize
            // fall back to the built-in defaults. fontFamily is restricted to
            // font-name characters so it drops safely into a CSS `font-family`.
            @Size(max = 200) @Pattern(regexp = "^$|^[A-Za-z0-9 ,\"'_-]+$") String fontFamily,
            Integer fontSize,
            // Tenant-wide IANA time zone (e.g. "Asia/Kolkata", "America/New_York",
            // "UTC"), derived from `country` when one is set. Blank = fall back to
            // the viewer's browser time zone. The pattern accepts the IANA
            // "Area/Location" shape plus single-word ids like "UTC"; the service
            // additionally validates it with ZoneId.of().
            @Size(max = 64) @Pattern(regexp = "^$|^[A-Za-z0-9_+-]+(?:/[A-Za-z0-9_+-]+){0,2}$") String timezone,
            // Tenant reporting currency as an ISO 4217 code (e.g. "INR", "JPY").
            // Locale metadata alongside defaultLanguage and timezone, and used the
            // same way: it is the DEFAULT unit for money whose own currency the
            // data does not state. It is NOT a conversion target — a metric
            // observation carries its own currency and is always rendered in it,
            // because converting needs an FX rate with an as-of date that the
            // platform does not hold. Blank = fall back to the platform default.
            @Size(max = 3) @Pattern(regexp = "^$|^[A-Za-z]{3}$") String currency,
            // Header status-dot thresholds by open-task count: count < green → green,
            // < blue → blue, <= amber → amber, > amber → red. null on any = fall back
            // to the system default (15 / 30 / 50). Configurable in Appearance → Branding
            // (tenant) and Appearance → Platform Default (platform).
            Integer statusGreen,
            Integer statusBlue,
            Integer statusAmber
    ) {}

    public record BrandingProfileView(
            String id, String name, String productName, String tagline,
            String country,
            String defaultLanguage,
            String activeThemeId,
            // Asset IDs are the public references the UI fetches via /api/branding/asset/{id}
            String logoAssetId, String backgroundAssetId, String faviconAssetId,
            String fontFamily, Integer fontSize,
            String timezone,
            String currency,
            // BCP-47 tag the SPA formats numbers, dates and clocks in ("en-IN",
            // "ja-JP"). Derived from the country and sent explicitly so the
            // reader's UI language cannot decide it: switching the interface to
            // Japanese must translate the prose without re-denominating a single
            // figure on the board. Blank for a country-less profile, which
            // returns the SPA to formatting in whatever language is being read.
            String numberLocale,
            Instant updatedAt,
            // Header status-dot thresholds (see BrandingProfileRequest). null = the
            // SPA falls back to the system default (15 / 30 / 50).
            Integer statusGreen,
            Integer statusBlue,
            Integer statusAmber
    ) {}

    public record AssetUploadResponse(String id, String contentType, long sizeBytes) {}

    /**
     * {@code labels} maps language code → (labelKey → override value). The SPA
     * layers these on top of its built-in i18n catalog, so any UI title/text
     * can be renamed per tenant and per language from the Branding admin tab.
     *
     * <p>{@code profile} and {@code theme} are the EFFECTIVE appearance: the
     * tenant's own overrides layered over the platform default. {@code source}
     * is {@code "platform-default"} while the tenant overrides nothing and
     * {@code "custom"} once it does; {@code overriddenFields} names what the
     * tenant has customised (everything else follows the platform default
     * live).</p>
     */
    public record ActiveBrandingView(BrandingProfileView profile, ThemeView theme,
                                     Map<String, Map<String, String>> labels,
                                     String source, List<String> overriddenFields) {}

    // ----- UI label overrides -----
    public record UiLabelEntry(
            @NotBlank @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String labelKey,
            @NotBlank @Size(max = 10) @Pattern(regexp = "^[a-z]{2}(-[A-Za-z0-9]{2,8})?$") String lang,
            // Blank value = remove the override (fall back to the built-in default).
            @Size(max = 2000) String value
    ) {}

    public record UiLabelView(String labelKey, String lang, String value, Instant updatedAt) {}

    public record UiLabelBulkRequest(@Valid List<UiLabelEntry> labels) {}

    public record UiLabelHistoryView(String labelKey, String lang, String oldValue, String newValue,
                                     String changedBy, String source, Instant changedAt) {}

    public record LabelImportReport(int upserted, int removed, int skipped, List<String> errors) {}

    public record PreviewRequest(String themeId, ColorScheme colorsOverride, String cssOverride) {}

    public record PreviewToken(String token, Instant expiresAt) {}

    public record ValidationReport(boolean valid, List<String> issues, Map<String, Object> meta) {}
}
