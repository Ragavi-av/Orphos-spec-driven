// The tenant's primary country → language, time zone, currency, number format.
//
// The board had three independent knobs for one underlying idea of "where this
// workspace operates": the default UI language (i18n), the time zone
// (setTenantTimezone, read by datetime.js and every greeting) and the reporting
// currency (setTenantCurrency, read by money.js). Nothing tied them together,
// so a Tokyo tenant could be left reading IST clocks under ¥ tiles.
//
// A country is the one choice an ADMIN actually makes, so this module owns the
// mapping and drives all four from it:
//
//   India → English · Asia/Kolkata · INR · en-IN (₹5.86 Cr, 06:00, dd/mm)
//   Japan → 日本語   · Asia/Tokyo   · JPY · ja-JP (￥5,860万, 09:30, yyyy/mm/dd)
//
// WHO SETS IT. The country is TENANT-level and admin-only (Admin → Branding →
// Primary country). It is not a per-reader preference and there is no header
// control for it. A reader's only locale choice is the LANGUAGE, from the
// header LanguageSwitcher, and that moves the prose alone: switching to
// Japanese on an India tenant translates the words and leaves the clocks on
// Asia/Kolkata and the money in ₹ crore. Changing the currency a figure is
// labelled with, or the wall clock a timestamp resolves against, would change
// what the number MEANS — that is an operating decision, not a reading
// preference.
//
// WHO WINS ON LANGUAGE. Moving the country resets every reader to the new
// country's language, including those who had picked one — the workspace has
// relocated, and leaving half the board in the old country's language is the
// state this module exists to prevent. From there each reader is free to switch
// back, and their pick survives every reload and every Branding-tab save, until
// the country moves again. So: the admin sets the floor, the reader chooses
// above it, and a move resets the floor.
//
// The country is deliberately NOT a conversion: money.js still formats each
// amount in the currency its data declares, and the country's currency is only
// the default for amounts that name none.
//
// Adding a country: give it a `language` that exists in i18n's
// SUPPORTED_LANGUAGES (otherwise the tenant default silently falls back to
// English) and mirror the entry in the backend catalog
// (admin-console-service … branding/service/Countries.java), which is what
// actually resolves the profile on save.

import { useEffect, useState } from 'react';
import i18n, { hasChosenLanguage, clearLanguageChoice } from './i18n';
import { setTenantTimezone } from './ScheduleEditor';
import { setTenantCurrency } from './money';
import { setDisplayLocale } from './locale';

/**
 * The shipped countries.
 *
 * `numberLocale` is the BCP-47 tag every figure is FORMATTED in — 'en-IN' is
 * what produces the crore/lakh grouping an Indian reader expects, and it stays
 * in force no matter which language the reader picks.
 */
export const SUPPORTED_COUNTRIES = [
  {
    code: 'IN', flag: '🇮🇳',
    label: 'India', nativeLabel: 'India',
    language: 'en', timezone: 'Asia/Kolkata', currency: 'INR', numberLocale: 'en-IN',
  },
  {
    code: 'JP', flag: '🇯🇵',
    label: 'Japan', nativeLabel: '日本',
    language: 'ja', timezone: 'Asia/Tokyo', currency: 'JPY', numberLocale: 'ja-JP',
  },
];

// A CACHE of the tenant value, not a user preference: it exists only so the
// first paint after a reload already has the right zone and currency, instead
// of showing browser-zone clocks for the second or so the branding fetch takes.
// setTenantCountry overwrites it from the server on every fetch.
const CACHE_KEY = 'orphos.country';
const COUNTRY_EVENT = 'orphos:countrychange';

export function countryByCode(code) {
  if (!code) return null;
  const wanted = String(code).trim().toUpperCase();
  return SUPPORTED_COUNTRIES.find(c => c.code === wanted) || null;
}

function readCache() {
  // localStorage throws in private-mode Safari and in some embedded webviews;
  // the board must still render, just without the pre-fetch head start.
  try { return localStorage.getItem(CACHE_KEY); } catch { return null; }
}

let _country = countryByCode(readCache());

/** The tenant's configured country, or null when the admin has set none. */
export function activeCountry() {
  return _country;
}

/** Country's zone, or '' — callers fall back to the browser zone. */
export function countryTimezone() {
  return _country?.timezone || '';
}

/** Country's currency, or '' — callers fall back to the platform default. */
export function countryCurrency() {
  return _country?.currency || '';
}

/** Country's DEFAULT language, or '' — an explicit reader choice still wins. */
export function countryLanguage() {
  return _country?.language || '';
}

/** Country's format locale, or '' — callers fall back to the UI language. */
export function countryNumberLocale() {
  return _country?.numberLocale || '';
}

// Components that read the zone, the currency or the format locale cannot see
// those change: all three live in module-level singletons (ScheduleEditor,
// money, locale) rather than in React state, precisely so non-React helpers can
// read them. A language switch re-renders every useTranslation() consumer for
// free, but a country change whose language happens to match the current one
// would otherwise move the clocks and leave the page showing the old ones.
const listeners = new Set();

export function subscribeCountry(fn) {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
}

function notify() {
  for (const fn of [...listeners]) {
    try { fn(); } catch { /* one bad subscriber must not stop the rest */ }
  }
}

/**
 * Push a country's settings into this bundle's singletons.
 *
 * The language is applied LAST: i18n.changeLanguage() synchronously re-renders
 * every useTranslation() consumer, and those renders must already see the new
 * zone, currency and format locale or the board paints one frame of translated
 * text over the previous country's clocks.
 *
 * `forceLanguage` is the difference between MOVING the workspace and merely
 * re-stating where it already is — see setTenantCountry.
 */
function applyLocally(country, { forceLanguage = false } = {}) {
  _country = country || null;
  setTenantTimezone(country?.timezone || null);
  setTenantCurrency(country?.currency || null);
  setDisplayLocale(country?.numberLocale || null);
  if (!country) { notify(); return; }

  // Moving the workspace to a new country resets everyone to that country's
  // language, including readers who had picked one: the admin has changed where
  // this workspace operates, and a board left half in the previous country's
  // language is the state this whole feature exists to prevent. Their pick is
  // cleared rather than remembered, so the header comes up on the new default
  // and they are free to switch back — until the country moves again.
  //
  // Without a move this is a plain republish (page load, tenant switch, any
  // Branding-tab save), and a reader's pick MUST survive those, or picking a
  // language would never outlast the next fetch.
  if (forceLanguage) clearLanguageChoice();
  if ((forceLanguage || !hasChosenLanguage())
      && (i18n.language || '').split('-')[0] !== country.language) {
    // Patched by the i18n cross-bundle bridge, so this also moves every
    // remote's own i18n instance.
    i18n.changeLanguage(country.language);
  }
  notify();
}

/**
 * Publish the tenant's country — the single entry point, called by
 * BrandingProvider with `profile.country` on every branding fetch.
 *
 * Broadcast over `window` because Module Federation gives the shell and every
 * remote their own copy of this module (and of ScheduleEditor/money/locale):
 * BrandingProvider only mounts in the shell, so without the broadcast the
 * widget board and co-pilot would keep browser-zone clocks and platform-default
 * currency until a hard refresh. Same problem, and the same shape of fix, as
 * the cross-bundle language bridge in ./i18n.js.
 *
 * Whether this is a MOVE (the admin changed the country) or a republish of the
 * one already in force is decided here, by comparing against the country this
 * bundle last applied — which the localStorage cache seeds at import time, so
 * the comparison survives a reload. Only a move resets readers' languages.
 *
 * @param code ISO 3166-1 alpha-2, or blank/unknown to clear the country
 * @returns the resolved country, or null
 */
export function setTenantCountry(code) {
  const country = countryByCode(code);
  const moved = (country?.code || '') !== (_country?.code || '');
  try {
    if (country) localStorage.setItem(CACHE_KEY, country.code);
    else localStorage.removeItem(CACHE_KEY);
  } catch { /* not fatal — costs only the pre-fetch head start */ }
  // Applied even when the country has not moved: BrandingProvider has just
  // republished the raw profile values over these same singletons, so an early
  // return on the identity check would leave them holding those.
  applyLocally(country, { forceLanguage: moved && !!country });
  if (moved) {
    try {
      // forceLanguage rides along so every federated bundle makes the same call
      // the shell just did — each has its own i18n instance to move.
      window.dispatchEvent(new CustomEvent(COUNTRY_EVENT, {
        detail: { code: country?.code || '', forceLanguage: !!country },
      }));
    } catch { /* no CustomEvent — this bundle still switched */ }
  }
  return country;
}

if (typeof window !== 'undefined') {
  // A change in the shell lands in every remote. Re-applying in the bundle that
  // originated it is a harmless no-op: applyLocally only assigns.
  window.addEventListener(COUNTRY_EVENT, (event) => {
    applyLocally(countryByCode(event?.detail?.code),
      { forceLanguage: !!event?.detail?.forceLanguage });
  });

  // Another tab of the same workspace. `storage` fires only in the OTHER tabs,
  // so a country change saved in one admin tab reaches a dashboard open in
  // another without either one reloading. The move is judged against THIS tab's
  // country: it carries its own i18n instance and its own reader-choice marker.
  window.addEventListener('storage', (event) => {
    if (event.key !== CACHE_KEY) return;
    const next = countryByCode(event.newValue);
    const moved = (next?.code || '') !== (_country?.code || '');
    applyLocally(next, { forceLanguage: moved && !!next });
  });

  // Apply the cached country at import time so the first paint after a reload
  // is already correct. BrandingProvider replaces it with the server's value
  // once its fetch lands; this covers the window before that.
  applyLocally(_country);
}

/**
 * React view of the tenant country: re-renders the caller whenever it moves.
 *
 * Read-only by design — there is no setter here. The country is changed in
 * Admin → Branding, saved to the tenant profile, and arrives back through
 * BrandingProvider.
 *
 * `timezone`/`currency`/`numberLocale`/`language` are '' when no country is
 * configured, so a caller can write `countryTz || ''` and keep whatever
 * fallback it had.
 */
export function useCountry() {
  const [country, setState] = useState(activeCountry);
  useEffect(() => subscribeCountry(() => setState(activeCountry())), []);
  return {
    country,
    code: country?.code || '',
    timezone: country?.timezone || '',
    currency: country?.currency || '',
    language: country?.language || '',
    numberLocale: country?.numberLocale || '',
  };
}
