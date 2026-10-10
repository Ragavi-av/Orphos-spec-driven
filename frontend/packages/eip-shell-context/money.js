// Locale-aware money formatting.
//
// Two independent inputs decide how an amount renders, and conflating them is
// what produced "₹5.86 Cr" on a Japanese board:
//
//   currency — a property of the DATA. Which unit the number is denominated in.
//              Zenitus reports INR from Chennai and JPY from Tokyo (see the
//              tenant enterprise profiles), and GovMetricObservation carries a
//              `currency` per observation. Never inferred from the UI language:
//              relabelling an INR figure as ¥ would misstate it by ~180x.
//
//   locale   — a property of the WORKSPACE. Which grouping and magnitude scale
//              the number is written in. "Cr"/"L" (crore/lakh) is unreadable to
//              a Japanese reader, who expects 万/億; both are correct for the
//              same underlying amount.
//
// The locale comes from the tenant's primary country (Admin → Branding), NOT
// from the reader's UI language — see eip-shell-context/locale for why. A
// reader switching the header to Japanese translates the prose around the
// figure; the figure itself keeps the notation the workspace operates in.
//
// Both the currency and the locale reach this module from the country, via
// setTenantCurrency and setDisplayLocale. The currency is used only when the
// data itself does not say — it is a DEFAULT, not a conversion target.
// Converting between currencies needs an FX rate with an as-of date, which
// this module does not hold, so it never converts: it formats the amount in
// the currency the data declares.

import { formatLocale } from './locale';

// Published by BrandingProvider from the active branding profile, mirroring
// setTenantTimezone in ScheduleEditor. Module-level rather than context so
// non-React formatting helpers can read it too.
let _tenantCurrency = null;

export function setTenantCurrency(code) {
  _tenantCurrency = (typeof code === 'string' && /^[A-Za-z]{3}$/.test(code.trim()))
    ? code.trim().toUpperCase() : null;
}

/**
 * The tenant's reporting currency, or INR when unset.
 *
 * INR is the historical default: every money figure on the board was formatted
 * as INR before currency existed as a concept here, so defaulting to it keeps
 * an un-migrated tenant rendering exactly as it does today rather than silently
 * switching unit.
 */
export function tenantCurrency() {
  return _tenantCurrency || 'INR';
}

// Intl.NumberFormat construction is comparatively expensive and these run once
// per tile per render, so memoise by (locale, currency).
const _symbolCache = new Map();

function currencySymbol(locale, currency) {
  const cacheKey = locale + '|' + currency;
  if (_symbolCache.has(cacheKey)) return _symbolCache.get(cacheKey);
  let symbol = currency;
  try {
    const parts = new Intl.NumberFormat(locale, {
      style: 'currency', currency, currencyDisplay: 'narrowSymbol',
    }).formatToParts(0);
    symbol = parts.find(p => p.type === 'currency')?.value || currency;
  } catch {
    // Unknown/invalid currency code, or an engine without narrowSymbol. Falling
    // back to the raw code ("INR 5.86 Cr") is honest; guessing a symbol is not.
  }
  _symbolCache.set(cacheKey, symbol);
  return symbol;
}

/**
 * Magnitude scale for an amount, as [divisor, suffix, decimals] triples, largest
 * first. Decimals belong to the STEP, not to a global size threshold: 万 amounts
 * routinely run to four digits and read as integers (5,860万), while 億 and the
 * Cr/M steps carry two decimals (1.25億, ₹123.46 Cr).
 *
 * The scale follows the CURRENCY, not the reader. Lakh and crore are how rupee
 * amounts are written; 万 and 億 are how yen amounts are written. Those are
 * conventions of each currency's home market, and they travel with the money
 * rather than with whoever is looking at it — so a rupee figure on a Japanese
 * board is still ₹3.80 L, not ₹38万. Letting the reader's language decide
 * produced exactly that hybrid, which no one writes in either country and which
 * makes a real figure look like a formatting accident.
 *
 * The reader's locale still governs everything else about the number: the
 * grouping and decimal separators, and which of these steps a value lands on
 * once scaled. Only currencies with no scale convention of their own fall
 * through to the reader — a Japanese board showing USD uses 万, because that is
 * the reader's own arithmetic and the dollar has nothing to say about it.
 */
function scaleFor(locale, currency) {
  if (currency === 'INR') return [[1e7, ' Cr', 2], [1e5, ' L', 2]];
  if (currency === 'JPY') return [[1e8, '億', 2], [1e4, '万', 0]];
  const lang = String(locale || 'en').split('-')[0];
  if (lang === 'ja') return [[1e8, '億', 2], [1e4, '万', 0]];
  return [[1e9, 'B', 2], [1e6, 'M', 2], [1e3, 'K', 2]];
}

/**
 * Format an amount for display: compact, locale-scaled, currency-labelled.
 *
 * @param value    numeric amount, or anything non-finite for the em-dash
 * @param currency ISO code from the data; falls back to the tenant default
 * @param locale   FALLBACK format locale (callers pass i18n.language). Used
 *                 only when the tenant has no primary country configured —
 *                 the country's locale always wins, so a language switch
 *                 cannot re-denominate the board.
 */
export function formatMoney(value, currency, locale) {
  return formatMoneyIn(formatLocale(locale), value, currency);
}

/**
 * Format an amount in an EXPLICIT locale, bypassing the tenant country.
 *
 * For the one caller that is not rendering the board: the Branding tab's
 * preview of a country an admin is considering but has not saved. Everything
 * else wants {@link formatMoney}, which formats in the country the workspace
 * actually operates in.
 */
export function formatMoneyIn(locale, value, currency) {
  // Absent is not zero. The board's rule is that a tile with no observation says
  // so and never renders a confident 0 — "₹0" on a cash tile discredits every
  // other number beside it. Number(null) is 0 and finite, so null/undefined/''
  // must be rejected before the numeric check rather than by it.
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  if (!isFinite(n)) return '—';

  const code = (typeof currency === 'string' && /^[A-Za-z]{3}$/.test(currency.trim()))
    ? currency.trim().toUpperCase() : tenantCurrency();
  const lng = locale || 'en';
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  const symbol = currencySymbol(lng, code);
  // An unrecognised code falls back to the code itself ("INR"), which needs a
  // separator; a real symbol ("₹", "¥") sits flush against the digits.
  const gap = /^[A-Za-z]{3}$/.test(symbol) ? ' ' : '';

  for (const [divisor, suffix, decimals] of scaleFor(lng, code)) {
    if (abs >= divisor) {
      // Decimals are FIXED, not maximum — the board has always shown "₹18.50 L",
      // and letting that render as "₹18.5 L" would be a visible regression for
      // English readers.
      return `${sign}${symbol}${gap}${(abs / divisor).toLocaleString(lng, {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
      })}${suffix}`;
    }
  }
  // Below the smallest scale step, show the amount in full with locale grouping.
  // JPY and other zero-decimal currencies must not gain a fake ".00".
  return `${sign}${symbol}${gap}${abs.toLocaleString(lng, {
    minimumFractionDigits: 0,
    maximumFractionDigits: ZERO_DECIMAL.has(code) ? 0 : 2,
  })}`;
}

// Currencies with no minor unit — an amount of 5 is ¥5, never ¥5.00.
const ZERO_DECIMAL = new Set(['JPY', 'KRW', 'VND', 'CLP', 'ISK', 'HUF']);
