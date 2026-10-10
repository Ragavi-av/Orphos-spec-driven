// The FORMAT locale — the one number/date/time formatting reads.
//
// Two separate things were being decided by one value, and telling them apart
// is the whole point of this module:
//
//   language — which WORDS the reader sees. Owned by i18next, chosen by the
//              reader from the header LanguageSwitcher, remembered per user.
//
//   format   — which NOTATION numbers, dates and clocks are written in: 万/億
//              vs Cr/L, 1.234,56 vs 1,234.56, dd/mm vs mm/dd. Owned by the
//              TENANT's primary country (Admin → Branding), the same setting
//              that owns the time zone and the reporting currency.
//
// They used to be the same value, so a reader switching the board to Japanese
// silently re-denominated every figure on it. A language switch must move the
// prose and nothing else; the notation stays where the admin put it.
//
// Module-level rather than React context, mirroring setTenantTimezone
// (ScheduleEditor) and setTenantCurrency (money): most formatting call sites
// are plain helpers with no component around them.

let _displayLocale = null;

/**
 * Publish the tenant country's format locale (a BCP-47 tag, e.g. 'en-IN').
 *
 * Called by eip-shell-context/country when the tenant profile lands. Blank or
 * non-string clears it, which returns every formatter to its UI-language
 * fallback — the behaviour for a tenant whose admin has set no country.
 */
export function setDisplayLocale(tag) {
  _displayLocale = (typeof tag === 'string' && tag.trim()) ? tag.trim() : null;
}

/** The tenant's format locale, or '' when no country is configured. */
export function displayLocale() {
  return _displayLocale || '';
}

/**
 * The locale to format in: the tenant country's, else the reader's language.
 *
 * Every formatter resolves through here so the fallback rule is stated once.
 * `fallback` is the caller's UI language — an un-configured tenant keeps the
 * historical behaviour of formatting in whatever language is being read.
 */
export function formatLocale(fallback) {
  return _displayLocale || fallback || 'en';
}
