import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * The two-sided locale split, tested from both sides.
 *
 * ADMIN sets the workspace's primary country, and four things follow from it —
 * the default language, the time zone, the reporting currency and the notation
 * figures are written in. USERS get one control, the language, and it must move
 * the prose and nothing else.
 *
 * Every one of those four lives somewhere different — the language in i18next,
 * the zone in ScheduleEditor's singleton, the currency in money's, the notation
 * in locale's — so only a test at this level can say they actually move
 * together under the admin and stay put under the reader. That second half is
 * the part a refactor breaks silently: re-denominating a board because someone
 * wanted to read it in Japanese is a data error dressed up as a preference.
 */

import i18n, { clearLanguageChoice, hasChosenLanguage } from 'eip-shell-context/i18n';
import { setTenantTimezone, tenantTimezone } from 'eip-shell-context/ScheduleEditor';
import { setTenantCurrency, tenantCurrency, formatMoney } from 'eip-shell-context/money';
import { setDisplayLocale, displayLocale } from 'eip-shell-context/locale';
import { formatTime } from 'eip-shell-context/datetime';
import { setTenantCountry } from 'eip-shell-context/country';
import LanguageSwitcher from './LanguageSwitcher';

// 00:30 UTC — the same instant is 06:00 in Kolkata and 09:30 in Tokyo, and it
// straddles a day boundary neither zone shares with UTC, so a formatter that
// quietly ignored the zone could not accidentally pass.
const INSTANT = '2026-08-31T00:30:00Z';

const pill = name => screen.getByRole('button', { name: new RegExp(name) });

describe('primary country (admin) vs display language (user)', () => {
  beforeEach(async () => {
    localStorage.clear();
    clearLanguageChoice();
    // Clear the country singletons too: they are module-level and survive
    // localStorage.clear(), so without this each test would inherit the last
    // one's country.
    setTenantCountry('');
    await i18n.changeLanguage('en');
    // What BrandingProvider publishes for a tenant with no country configured.
    setTenantTimezone('UTC');
    setTenantCurrency('USD');
    setDisplayLocale(null);
  });

  it('moves language, zone, currency and notation together when the admin sets Japan', async () => {
    setTenantCountry('JP');

    await waitFor(() => expect(i18n.language).toBe('ja'));
    expect(tenantTimezone()).toBe('Asia/Tokyo');
    expect(tenantCurrency()).toBe('JPY');
    expect(displayLocale()).toBe('ja-JP');
    expect(formatTime(INSTANT)).toContain('09:30');
    // Myriad scale and yen — NOT a converted amount. money.js holds no FX rates
    // and must never pretend otherwise. The yen sign is matched as a class: a ja
    // locale's narrow symbol resolves to the fullwidth ￥ (U+FFE5) on current
    // ICU, and pinning either form would make this a report on the runtime's
    // CLDR data rather than on the feature.
    expect(formatMoney(58600000)).toMatch(/^[¥￥]5,860万$/);
  });

  it('moves them back when the admin sets India', async () => {
    setTenantCountry('JP');
    await waitFor(() => expect(i18n.language).toBe('ja'));

    setTenantCountry('IN');

    await waitFor(() => expect(i18n.language).toBe('en'));
    expect(tenantTimezone()).toBe('Asia/Kolkata');
    expect(tenantCurrency()).toBe('INR');
    expect(formatTime(INSTANT)).toContain('06:00');
    expect(formatMoney(58600000)).toBe('₹5.86 Cr');
  });

  it('translates the interface WITHOUT moving the clock, currency or notation', async () => {
    setTenantCountry('IN');
    await waitFor(() => expect(i18n.language).toBe('en'));

    render(<LanguageSwitcher />);
    await userEvent.click(pill('日本語'));
    await waitFor(() => expect(i18n.language).toBe('ja'));

    // The whole point: Japanese prose over an Indian workspace's figures.
    expect(tenantTimezone()).toBe('Asia/Kolkata');
    expect(tenantCurrency()).toBe('INR');
    expect(displayLocale()).toBe('en-IN');
    expect(formatTime(INSTANT)).toContain('06:00');
    expect(formatMoney(58600000)).toBe('₹5.86 Cr');
  });

  it('keeps a reader\'s language when the branding bundle republishes the country', async () => {
    setTenantCountry('IN');
    render(<LanguageSwitcher />);
    await userEvent.click(pill('日本語'));
    await waitFor(() => expect(i18n.language).toBe('ja'));
    expect(hasChosenLanguage()).toBe(true);

    // What a branding refetch does — on tenant switch, and after every save in
    // the Branding tab. Left unguarded it would snap the reader back to English.
    setTenantCountry('IN');

    expect(i18n.language).toBe('ja');
    expect(tenantTimezone()).toBe('Asia/Kolkata');
  });

  it('overrides a reader\'s language when the admin MOVES the country', async () => {
    setTenantCountry('IN');
    render(<LanguageSwitcher />);
    await userEvent.click(pill('日本語'));
    await waitFor(() => expect(i18n.language).toBe('ja'));

    // The admin relocates the workspace. Unlike a republish of the same
    // country, this resets everyone — a board left half in the old country's
    // language is the state the country setting exists to prevent.
    setTenantCountry('JP');
    await waitFor(() => expect(i18n.language).toBe('ja'));

    // And the reset must CLEAR the pick, not merely lose to it once: otherwise
    // the reader stays flagged as decided and the next move would skip them.
    expect(hasChosenLanguage()).toBe(false);
  });

  it('lets the reader switch back after a country move, and keeps it', async () => {
    setTenantCountry('IN');
    setTenantCountry('JP');
    await waitFor(() => expect(i18n.language).toBe('ja'));

    render(<LanguageSwitcher />);
    await userEvent.click(pill('English'));
    await waitFor(() => expect(i18n.language).toBe('en'));

    // Every branding refetch republishes the same country. The reader's new
    // pick has to outlive those, or choosing a language would be pointless.
    setTenantCountry('JP');
    setTenantCountry('JP');

    expect(i18n.language).toBe('en');
    // …while the workspace's own settings stay Japanese throughout.
    expect(tenantTimezone()).toBe('Asia/Tokyo');
    expect(displayLocale()).toBe('ja-JP');
  });

  it('applies the country default language to a reader who has chosen none', async () => {
    setTenantCountry('JP');
    await waitFor(() => expect(i18n.language).toBe('ja'));
    // Applying a default must not COUNT as a choice, or the next country change
    // would find the reader "already decided" and never move them again.
    expect(hasChosenLanguage()).toBe(false);
  });

  it('marks the pressed language and offers no country control', async () => {
    render(<LanguageSwitcher />);

    expect(pill('English')).toHaveAttribute('aria-pressed', 'true');
    expect(pill('日本語')).toHaveAttribute('aria-pressed', 'false');
    // The user side has exactly one locale control. A country pill here would
    // be a reader deciding what a figure is denominated in.
    expect(screen.queryByRole('group', { name: /country/i })).toBeNull();
  });

  it('labels money in the currency the DATA declares, whatever the country', async () => {
    setTenantCountry('JP');
    await waitFor(() => expect(displayLocale()).toBe('ja-JP'));

    // The data says INR, so the figure stays in INR — and in the scale rupees
    // are written in. Crore travels with the currency, not with the reader:
    // ₹5,855万 is a hybrid neither country writes. Relabelling ₹ as ￥ without
    // an exchange rate would misstate this number by roughly 1.8x, so the
    // currency is never inferred from where the reader (or workspace) sits.
    expect(formatMoney(58600000, 'INR')).toBe('₹5.86 Cr');

    // …and an amount whose source names no currency falls back to the
    // country's. This is the ONLY thing the country's currency decides.
    expect(formatMoney(58600000, null)).toMatch(/^[¥￥]5,860万$/);
    expect(formatMoney(58600000, '')).toMatch(/^[¥￥]5,860万$/);
  });

  it('writes each currency in its own scale, on either country\'s board', async () => {
    // Both boards, both currencies: the scale tracks the money every time.
    for (const code of ['IN', 'JP']) {
      setTenantCountry(code);
      await waitFor(() => expect(displayLocale()).toBe(code === 'IN' ? 'en-IN' : 'ja-JP'));
      expect(formatMoney(380000, 'INR')).toBe('₹3.80 L');
      expect(formatMoney(58600000, 'JPY')).toMatch(/^[¥￥]5,860万$/);
    }
  });

  it('follows the country currency for unlabelled money when the country moves', async () => {
    setTenantCountry('IN');
    await waitFor(() => expect(displayLocale()).toBe('en-IN'));
    expect(formatMoney(58600000, null)).toBe('₹5.86 Cr');

    setTenantCountry('JP');
    await waitFor(() => expect(displayLocale()).toBe('ja-JP'));
    expect(formatMoney(58600000, null)).toMatch(/^[¥￥]5,860万$/);
    // The labelled figure did not move with it — same currency, same scale.
    expect(formatMoney(58600000, 'INR')).toBe('₹5.86 Cr');
  });

  it('leaves the tenant profile in charge when no country is configured', () => {
    // BrandingProvider's legacy path: the profile's own timezone/currency, for
    // tenants saved before the country existed.
    setTenantCountry('');
    setTenantTimezone('Europe/London');
    setTenantCurrency('GBP');

    expect(tenantTimezone()).toBe('Europe/London');
    expect(tenantCurrency()).toBe('GBP');
    // No country, so notation falls back to the reader's language.
    expect(displayLocale()).toBe('');
  });
});
