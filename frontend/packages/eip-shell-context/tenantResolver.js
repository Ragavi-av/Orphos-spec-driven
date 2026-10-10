/**
 * Resolves the current tenant from the browser URL.
 *
 * <p>The SPA is served at {@code http://<host>:3001/<tenant display name>/} —
 * the first path segment is the tenant's display name (URL-encoded). This
 * resolver reads that segment, looks the tenant up via
 * {@code GET /api/public/tenants/by-slug/{displayName}}, and returns the
 * {tenantId, domain, keycloakRealm, status} tuple the rest of the SPA needs
 * BEFORE the Keycloak adapter initialises.</p>
 *
 * <p>Falls back to the legacy hostname-based lookup when no path segment is
 * present — this keeps single-tenant deployments and the demo tenant (served
 * from the bare {@code http://localhost:3001/} root during dev iteration)
 * working without forcing a URL migration first.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Check sessionStorage for a cached resolution (TTL 5 min, keyed on
 *       the resolution key — slug or hostname).</li>
 *   <li>Otherwise GET {@code /api/public/tenants/by-slug/{slug}} (slug path),
 *       or {@code /api/public/tenants/by-domain/{hostname}} (legacy path).</li>
 *   <li>404 → throw {@link TenantNotFoundError}.</li>
 *   <li>5xx / network → throw {@link TenantResolutionError}.</li>
 * </ol>
 *
 * <p>Uses {@code fetch} rather than axios because the axios instance has
 * interceptors that depend on Keycloak being initialised — which it isn't
 * yet.</p>
 */

const CACHE_KEY = 'orphos.tenant.cached';
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Reserved first-path-segment that logs in against the cross-tenant
 * {@code platform} realm instead of a per-tenant realm.
 *
 * <p>A platform_admin signs in at {@code http://<host>:3001/platform/}. There
 * is deliberately NO tenant record for the platform realm — a platform_admin's
 * JWT carries no {@code tenant_id} and operates across all tenants via the
 * header-bar acting-tenant picker. So this slug short-circuits the by-slug
 * lookup with a synthetic ACTIVE {@link TenantInfo} whose {@code tenantId} is
 * null and whose realm is {@code platform}.</p>
 *
 * <p>Because the segment is reserved, a tenant whose displayName is literally
 * "platform" is unreachable via the path-slug URL — an acceptable trade-off for
 * a single reserved word. Match is case-insensitive.</p>
 */
export const PLATFORM_SLUG  = 'platform';
export const PLATFORM_REALM = 'platform';

function platformTenant(slug) {
  return {
    tenantId:      null,            // cross-tenant; no single tenant_id
    domain:        'platform',
    keycloakRealm: PLATFORM_REALM,
    status:        'ACTIVE',
    slug,
    isPlatform:    true,
  };
}

export class TenantNotFoundError extends Error {
  constructor(domain) {
    super(`No tenant is registered for '${domain}'.`);
    this.name = 'TenantNotFoundError';
    this.domain = domain;
  }
}

export class TenantResolutionError extends Error {
  constructor(domain, cause) {
    super(`Could not reach the tenant service for '${domain}': ${cause}`);
    this.name = 'TenantResolutionError';
    this.domain = domain;
    this.cause = cause;
  }
}

/**
 * Extracts the first non-empty path segment, URL-decoded.
 * Returns null if the URL is at the root (no segment).
 */
function readTenantSlug() {
  const path = window.location.pathname || '/';
  // Skip the leading slash, take the first segment, ignore trailing slashes.
  const first = path.split('/').filter(Boolean)[0];
  if (!first) return null;
  try {
    return decodeURIComponent(first);
  } catch {
    // Malformed escape sequences — fall back to the raw segment so a tenant
    // whose displayName happens to contain a stray % isn't unreachable.
    return first;
  }
}

/**
 * @typedef {Object} TenantInfo
 * @property {string} tenantId       UUID
 * @property {string} domain         The host this tenant answers for
 * @property {string} keycloakRealm  Realm to log into
 * @property {string} status         ACTIVE | SUSPENDED | DELETED
 * @property {string} [slug]         Display-name slug from the URL, when used
 */

/**
 * Resolves the tenant from the current URL.
 *
 * @returns {Promise<TenantInfo>}
 * @throws {TenantNotFoundError} when no tenant matches the slug/hostname
 * @throws {TenantResolutionError} on transient backend failure
 */
export async function resolveTenant() {
  const slug = readTenantSlug();
  const hostname = window.location.hostname || 'localhost';

  // Reserved 'platform' slug → cross-tenant platform realm, no backend lookup.
  if (slug && slug.toLowerCase() === PLATFORM_SLUG) {
    return platformTenant(slug);
  }

  // Resolution key: slug wins (path-based URL), else hostname (legacy fallback).
  // Caching on this key means switching between /acme/ and /globex/ does NOT
  // serve stale data — different keys, different cache entries.
  const resolutionKey = slug ? `slug:${slug}` : `host:${hostname}`;

  // 1. Cache hit?
  const cached = readCache(resolutionKey);
  if (cached) return cached;

  // 2. Live lookup
  const base = (import.meta.env.VITE_ADMIN_API_BASE || '').replace(/\/$/, '');
  const url = slug
    ? `${base}/api/public/tenants/by-slug/${encodeURIComponent(slug)}`
    : `${base}/api/public/tenants/by-domain/${encodeURIComponent(hostname)}`;

  const subject = slug || hostname;

  // Log the URL up front so console traces line up with the result/error
  // regardless of which branch below throws.
  // eslint-disable-next-line no-console
  console.info('[tenantResolver] GET', url);

  let response;
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (e) {
    // eslint-disable-next-line no-console
    console.error('[tenantResolver] network error', e);
    throw new TenantResolutionError(subject, `network: ${e.message} (url=${url})`);
  }

  if (response.status === 404) {
    throw new TenantNotFoundError(subject);
  }
  if (!response.ok) {
    // Read the body so the user sees the actual server error, not just a code.
    let body = '';
    try { body = (await response.text()).slice(0, 240); } catch { /* ignore */ }
    // eslint-disable-next-line no-console
    console.error('[tenantResolver] HTTP', response.status, url, body);
    throw new TenantResolutionError(subject,
      `HTTP ${response.status} from ${url}${body ? ' — ' + body : ''}`);
  }

  let payload;
  try {
    payload = await response.json();
  } catch (e) {
    throw new TenantResolutionError(subject, `Invalid JSON from ${url}: ${e.message}`);
  }

  // admin-service returns { id, domain, keycloakRealm, status } — normalise to
  // TenantInfo's tenantId field for stable client-side naming.
  const tenant = {
    tenantId:      payload.id,
    domain:        payload.domain,
    keycloakRealm: payload.keycloakRealm,
    status:        payload.status,
    slug:          slug || null,
  };
  writeCache(resolutionKey, tenant);
  return tenant;
}

/** Clear the cached resolution — invoked on logout or explicit refresh. */
export function clearTenantCache() {
  try { sessionStorage.removeItem(CACHE_KEY); } catch { /* ignore */ }
}

/**
 * Builds the post-logout redirect URL that keeps the user on the same tenant.
 * Falls back to the origin root when no tenant slug is in the URL.
 *
 * <p>Without this, logout would always bounce to {@code http://host:3001/},
 * which resolves to the demo tenant via hostname-fallback — surprising for a
 * user who signed in via {@code http://host:3001/Acme/}.</p>
 */
export function tenantRedirectUri() {
  const origin = window.location.origin;
  const first = (window.location.pathname || '/').split('/').filter(Boolean)[0];
  return first ? `${origin}/${first}/` : origin;
}

// ── Cache helpers ──────────────────────────────────────────────────────
function readCache(key) {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const entry = JSON.parse(raw);
    if (entry.key !== key) return null;
    if (Date.now() - entry.at > CACHE_TTL_MS) return null;
    return entry.tenant;
  } catch {
    return null;
  }
}

function writeCache(key, tenant) {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify({
      key, tenant, at: Date.now(),
    }));
  } catch {
    // sessionStorage may be unavailable in privacy modes — fall back to
    // re-resolving on every page load. Not fatal.
  }
}
