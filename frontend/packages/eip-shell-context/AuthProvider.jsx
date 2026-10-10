import { createContext, useContext, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { initKeycloak, getKeycloak, getTenant, TenantUnavailableError } from './keycloak';
import { TenantNotFoundError, TenantResolutionError } from './tenantResolver';
// Imported for its side effect: these screens render BEFORE the app tree, so
// the i18next singleton must already be initialised when they call t(). No
// I18nextProvider is mounted this early, and useTranslation() falls back to the
// default instance, which is the one ./i18n initialises.
import './i18n';

/**
 * AuthProvider — multi-tenant bootstrap.
 *
 * <p>Three-step flow on mount:
 * <ol>
 *   <li>{@code resolveTenant()} via {@code Host} header → admin-service
 *       returns the tenant + realm name. Failures are shown as a friendly
 *       error screen instead of redirecting to a dead Keycloak URL.</li>
 *   <li>{@code keycloak.init(...)} with the resolved realm. Unauthenticated
 *       users get redirected to that realm's login page; the promise never
 *       resolves because the page navigates away.</li>
 *   <li>After login Keycloak redirects back, init resolves true, and we
 *       render the app tree with both {@code keycloak} and {@code tenant}
 *       in context.</li>
 * </ol>
 *
 * <p>{@code onTokenExpired} fires 30 s before each expiry — updateToken()
 * keeps the session alive silently; on failure the user is sent back to
 * login (which will hit the same realm because the tenant resolution is
 * cached in sessionStorage).</p>
 */

const AuthContext = createContext(null);

const STATE_LOADING = 'loading';
const STATE_READY   = 'ready';
const STATE_ERROR   = 'error';

export function AuthProvider({ children }) {
  const [state, setState] = useState(STATE_LOADING);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { keycloak } = await initKeycloak();
        await keycloak.init({
          onLoad:           'login-required',
          pkceMethod:       'S256',
          checkLoginIframe: false,
        });
        keycloak.onTokenExpired = () => {
          keycloak.updateToken(30).catch(() => keycloak.login());
        };
        if (!cancelled) setState(STATE_READY);
      } catch (err) {
        console.error('[AuthProvider] init failed', err);
        // Bare-root URL whose hostname isn't a registered tenant domain
        // (e.g. http://<LAN-IP>:3001/) — send the visitor to the platform
        // login rather than a dead-end error card. Only for the no-slug
        // case: a wrong slug like /Acmee/ should still show "not found".
        const atBareRoot = !(window.location.pathname || '/').split('/').filter(Boolean).length;
        if (atBareRoot && err instanceof TenantNotFoundError) {
          window.location.replace('/platform/');
          return;
        }
        if (!cancelled) {
          setError(err);
          setState(STATE_ERROR);
        }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (state === STATE_LOADING) {
    return <CenteredMessage><LoadingLabel /></CenteredMessage>;
  }

  if (state === STATE_ERROR) {
    return <ErrorPanel error={error} />;
  }

  return (
    <AuthContext.Provider value={{ keycloak: getKeycloak(), tenant: getTenant() }}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * useAuth() — returns the live Keycloak instance.
 *
 * <p>This shape is preserved from the pre-multi-tenant SPA so the four
 * existing call sites (App, useRole, HeaderBar, GuestPage) keep working.
 * Use {@link useTenant} alongside it when you need the tenant UUID / domain.</p>
 *
 * <p>Useful token claims (from Keycloak):
 *   keycloak.tokenParsed?.name               → full name
 *   keycloak.tokenParsed?.email              → email
 *   keycloak.tokenParsed?.realm_access?.roles → role array
 *   keycloak.tokenParsed?.tenant_id          → tenant UUID (mirrors useTenant().tenantId)
 * </p>
 */
export function useAuth() {
  const ctx = useContext(AuthContext);
  return ctx?.keycloak ?? null;
}

/**
 * useTenant() — returns the resolved tenant: {@code { tenantId, domain,
 * keycloakRealm, status }}. Used for branding ("Welcome to {domain}"),
 * tenant-scoped routing, and the admin UI's tenant picker.
 */
export function useTenant() {
  const ctx = useContext(AuthContext);
  return ctx?.tenant ?? null;
}

// ── Internal UI helpers ────────────────────────────────────────────────

function CenteredMessage({ children }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: '100vh', fontFamily: 'Inter, sans-serif',
      color: '#C3D3F2', fontSize: 15,
    }}>
      {children}
    </div>
  );
}

/** Split out so the bootstrap message re-renders if the language changes under it. */
function LoadingLabel() {
  const { t } = useTranslation();
  return <>{t('auth.loadingWorkspace')}</>;
}

function ErrorPanel({ error }) {
  const { t } = useTranslation();
  // Distinguish the three error classes the bootstrap can throw so users see
  // an actionable message instead of a generic "something broke" wall.
  let title;
  let detail;
  if (error instanceof TenantNotFoundError) {
    title = t('auth.notFound.title');
    detail = t('auth.notFound.detail', { domain: error.domain });
  } else if (error instanceof TenantUnavailableError) {
    title = t('auth.unavailable.title');
    detail = t('auth.unavailable.detail', {
      domain: error.tenant?.domain || t('auth.thisAddress'),
      status: (error.tenant?.status || 'unavailable').toLowerCase(),
    });
  } else if (error instanceof TenantResolutionError) {
    title = t('auth.unreachable.title');
    // Surface the underlying cause so it's obvious whether we're hitting a
    // network issue (admin-service down, Vite proxy misconfigured) versus
    // a server error (HTTP 500 / SQL bug). The friendly preamble stays the
    // same; the technical detail follows it.
    detail = t('auth.unreachable.detail') + ' '
           + (error.cause ? t('auth.diagnostic', { cause: error.cause })
                          : t('auth.retryMoment'));
  } else {
    title = t('auth.failed.title');
    detail = error?.message || t('auth.failed.detail');
  }

  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: '100vh', fontFamily: 'Inter, sans-serif',
      padding: 24, color: '#FFFFFF',
    }}>
      <div style={{
        maxWidth: 480, textAlign: 'center', padding: '32px 40px',
        border: '1px solid #243A72', borderRadius: 12,
        backgroundColor: '#152652', boxShadow: '0 8px 24px rgba(0,0,0,0.45)',
      }}>
        <div style={{ fontSize: 36, marginBottom: 8 }}>⚠️</div>
        <div style={{ fontSize: 18, fontWeight: 700, marginBottom: 8 }}>{title}</div>
        <div style={{ fontSize: 14, color: '#C3D3F2', lineHeight: 1.5 }}>{detail}</div>
        <button
          onClick={() => window.location.reload()}
          style={{
            marginTop: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600,
            border: 'none', borderRadius: 6, cursor: 'pointer',
            backgroundColor: '#2F6FF0', color: '#FFFFFF',
          }}>
          {t('auth.retry')}
        </button>
      </div>
    </div>
  );
}
