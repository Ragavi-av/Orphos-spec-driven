import { createContext, useContext, useEffect, useState, useCallback } from 'react';
import axios from './axiosAuth';
import { useTenant } from './AuthProvider';
import i18n, { applyLabelOverrides, hasChosenLanguage } from './i18n';
import { setTenantTimezone } from './ScheduleEditor';
import { setTenantCurrency } from './money';
import { setDisplayLocale } from './locale';
import { setTenantCountry } from './country';

/** Fire from any component to force BrandingProvider to refetch the active bundle. */
export const BRANDING_CHANGED_EVENT = 'orphos:branding-changed';
export function notifyBrandingChanged() {
  window.dispatchEvent(new Event(BRANDING_CHANGED_EVENT));
}

/**
 * BrandingProvider — fetches the tenant's active branding bundle and exposes
 * it via the {@code useBranding()} hook. Side effects (document.title,
 * favicon swap) live here so the rest of the app stays declarative.
 *
 * <p>Sits below AuthProvider in the React tree so tenant context is already
 * resolved when this component mounts.</p>
 */

const DEFAULT_BRANDING = {
  profile: null,
  theme: null,
  labels: {},
  loading: true,
  error: null,
};

const BrandingContext = createContext(DEFAULT_BRANDING);

const DEFAULT_TITLE = 'Orphos - EIP';
const FAVICON_REL = 'icon';

/**
 * Publish the tenant's locale settings out of a branding profile.
 *
 * The primary country is the modern, single source: it resolves the time zone,
 * the reporting currency, the number/date notation and the default language
 * together, so they cannot drift apart.
 *
 * The three loose fields are the LEGACY path, kept for tenants saved before the
 * country existed (and for the platform-default template, which may carry them
 * without a country). They are read only when no country is set — a profile
 * that has one has already had them derived from it server-side, so preferring
 * the country costs nothing and removes the chance of a stale combination.
 */
function publishLocale(profile) {
  // Also clears the zone/currency/notation when the profile has no country, so
  // the legacy assignments below start from a clean slate rather than layering
  // over the previous tenant's country.
  const country = setTenantCountry(profile?.country);
  if (country) return;

  setTenantTimezone(profile?.timezone || null);
  setTenantCurrency(profile?.currency || null);
  // Without a country there is no workspace notation to impose, so formatting
  // falls back to the reader's language — see eip-shell-context/locale.
  setDisplayLocale(null);
  // The profile language is a DEFAULT: a reader who picked one from the header
  // keeps it. hasChosenLanguage(), not the mere presence of 'orphos.lang' —
  // i18next's detector caches every changeLanguage() to that key, including
  // this one, so it would report "the user chose" after the very first load.
  if (profile?.defaultLanguage && !hasChosenLanguage()) {
    i18n.changeLanguage(profile.defaultLanguage);
  }
}

export function BrandingProvider({ children }) {
  const tenant = useTenant();
  const [bundle, setBundle] = useState({ ...DEFAULT_BRANDING });

  // Stable refetch — used both by the tenant-change effect and the
  // branding-changed event listener. Returning a cancel handle lets the
  // tenant effect cancel in-flight fetches on tenant switch.
  const fetchBundle = useCallback(() => {
    let cancelled = false;
    (async () => {
      try {
        const r = await axios.get('/api/branding/public/active-bundle');
        if (cancelled) return;
        const profile = r.data?.profile || null;
        setBundle({
          profile,
          theme: r.data?.theme || null,
          labels: r.data?.labels || {},
          loading: false,
          error: null,
        });
        // Tenant UI-text overrides — layered onto the built-in i18n catalog.
        // Applying an empty map resets to defaults, so a tenant switch never
        // leaks the previous tenant's labels.
        applyLabelOverrides(r.data?.labels || {});
        // The tenant's primary country is the one setting behind the time zone,
        // the reporting currency, the number/date notation and the DEFAULT UI
        // language — see eip-shell-context/country, which publishes all four
        // and broadcasts them to every federated remote (this provider mounts
        // only in the shell). Blank means the admin has set no country.
        publishLocale(profile);
      } catch (e) {
        if (cancelled) return;
        setBundle({
          profile: null,
          theme: null,
          labels: {},
          loading: false,
          error: e?.response?.data?.message || e?.message || 'branding-fetch-failed',
        });
        applyLabelOverrides({});
        // No profile to read a country from. country.js falls the zone,
        // currency and notation back to their platform defaults; the reader's
        // own language choice, being theirs, is untouched.
        publishLocale(null);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Initial load + tenant switch.
  useEffect(() => {
    if (!tenant?.tenantId) {
      setBundle({ profile: null, theme: null, labels: {}, loading: false, error: null });
      applyLabelOverrides({});
      publishLocale(null);
      return;
    }
    return fetchBundle();
  }, [tenant?.tenantId, fetchBundle]);

  // Any component (e.g. BrandingTab after a save) can fire the event to make
  // the whole SPA re-theme without a manual page reload.
  useEffect(() => {
    const handler = () => fetchBundle();
    window.addEventListener(BRANDING_CHANGED_EVENT, handler);
    return () => window.removeEventListener(BRANDING_CHANGED_EVENT, handler);
  }, [fetchBundle]);

  // Document title — productName overrides the static title set in index.html.
  // We never blow the title away to empty; if branding has no productName,
  // the index.html default remains in place from page load.
  useEffect(() => {
    const productName = bundle.profile?.productName?.trim();
    const tagline = bundle.profile?.tagline?.trim();
    if (productName) {
      document.title = tagline ? `${productName} — ${tagline}` : productName;
    } else {
      document.title = DEFAULT_TITLE;
    }
  }, [bundle.profile?.productName, bundle.profile?.tagline]);

  // Custom CSS — sanitised server-side, injected into <head> as a managed
  // <style> tag. Removed on tenant switch / unmount so a previous tenant's
  // overrides cannot leak into the next page load.
  useEffect(() => {
    const css = bundle.theme?.sanitizedCss;
    if (!css) return;
    const style = document.createElement('style');
    style.setAttribute('data-orphos-branding', 'tenant-custom-css');
    style.textContent = css;
    document.head.appendChild(style);
    return () => { style.parentNode?.removeChild(style); };
  }, [bundle.theme?.sanitizedCss]);

  // Tenant font — apply the profile's font family + base size to the whole
  // document so EVERY surface follows: the shell, all plugin remotes, and any
  // non-MUI / raw text. App.jsx's MUI theme (buildTheme) already scales its own
  // components from the same profile values; this global <body> rule is the
  // baseline underneath it that guarantees the change reaches text MUI's theme
  // doesn't own. Removed on change / tenant switch / unmount so a previous
  // tenant's font never lingers.
  useEffect(() => {
    const family = (bundle.profile?.fontFamily || '').trim();
    const size = Number(bundle.profile?.fontSize) > 0 ? Number(bundle.profile.fontSize) : 0;
    if (!family && !size) return;              // nothing customised → keep built-in defaults
    const decls = [];
    // `!important` on <body> only wins for elements that don't declare their
    // own font — descendants with an explicit font (e.g. monospace code blocks)
    // still keep it, since inheritance never overrides a direct declaration.
    if (family) decls.push(`font-family: ${family} !important;`);
    if (size) decls.push(`font-size: ${size}px;`);
    const style = document.createElement('style');
    style.setAttribute('data-orphos-branding', 'tenant-font');
    style.textContent = `body { ${decls.join(' ')} }`;
    document.head.appendChild(style);
    return () => { style.parentNode?.removeChild(style); };
  }, [bundle.profile?.fontFamily, bundle.profile?.fontSize]);

  // Favicon swap — replace (or add) <link rel="icon"> in <head> when the
  // tenant has uploaded a favicon asset. Falls back to the static favicon
  // shipped with the SPA when faviconAssetId is empty.
  useEffect(() => {
    const faviconId = bundle.profile?.faviconAssetId;
    if (!faviconId) return;
    let link = document.querySelector(`link[rel='${FAVICON_REL}']`);
    const created = !link;
    if (!link) {
      link = document.createElement('link');
      link.rel = FAVICON_REL;
      document.head.appendChild(link);
    }
    const previousHref = link.href;
    link.href = `/api/branding/public/asset/${encodeURIComponent(faviconId)}`;
    return () => {
      if (created) {
        link.parentNode?.removeChild(link);
      } else {
        link.href = previousHref;
      }
    };
  }, [bundle.profile?.faviconAssetId]);

  return (
    <BrandingContext.Provider value={bundle}>
      {children}
    </BrandingContext.Provider>
  );
}

export function useBranding() {
  return useContext(BrandingContext);
}

/** Build a public asset URL the SPA can use as an &lt;img src&gt; / CSS url(). */
export function brandingAssetUrl(assetId) {
  if (!assetId) return null;
  return `/api/branding/public/asset/${encodeURIComponent(assetId)}`;
}
