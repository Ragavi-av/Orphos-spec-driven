import Keycloak from 'keycloak-js';
import { resolveTenant } from './tenantResolver';

/**
 * Keycloak client factory — multi-tenant aware.
 *
 * <p>The platform issues a separate Keycloak realm per tenant
 * ({@code tenant-{uuid}}). The realm to log into depends on the browser's
 * current hostname, so we cannot construct the Keycloak adapter until we've
 * resolved the tenant via {@link resolveTenant}. This module is therefore a
 * lazy factory rather than a default-exported instance.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * import { initKeycloak, getKeycloak, getTenant } from './keycloak';
 *
 * // Once, at app bootstrap (AuthProvider does this):
 * const { keycloak, tenant } = await initKeycloak();
 * await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256' });
 *
 * // Anywhere afterwards (axios interceptors, hooks):
 * getKeycloak()?.token   // current access token
 * getTenant()?.tenantId  // tenant UUID
 * }</pre>
 */

let _keycloak = null;
let _tenant = null;
let _initPromise = null;

/** Status surfaced by admin-service for a tenant that's not currently usable. */
export class TenantUnavailableError extends Error {
  constructor(tenant) {
    super(`Tenant '${tenant.domain}' is ${tenant.status?.toLowerCase() || 'unavailable'}.`);
    this.name = 'TenantUnavailableError';
    this.tenant = tenant;
  }
}

/**
 * Idempotent — repeated calls share the same in-flight promise and resolve to
 * the same {keycloak, tenant} pair. Throws if the tenant lookup fails or if
 * the resolved tenant is not ACTIVE.
 *
 * @returns {Promise<{keycloak: import('keycloak-js').default, tenant: object}>}
 */
export function initKeycloak() {
  if (_keycloak && _tenant) {
    return Promise.resolve({ keycloak: _keycloak, tenant: _tenant });
  }
  if (_initPromise) return _initPromise;

  _initPromise = (async () => {
    const tenant = await resolveTenant();
    if (tenant.status !== 'ACTIVE') {
      throw new TenantUnavailableError(tenant);
    }
    const keycloak = new Keycloak({
      url:      import.meta.env.VITE_KEYCLOAK_URL    || 'http://localhost:8443',
      realm:    tenant.keycloakRealm,
      clientId: import.meta.env.VITE_KEYCLOAK_CLIENT || 'orphos-spa',
    });
    _keycloak = keycloak;
    _tenant = tenant;
    return { keycloak, tenant };
  })();

  return _initPromise;
}

/**
 * @returns {import('keycloak-js').default | null} The active Keycloak instance,
 * or {@code null} if {@link initKeycloak} has not yet resolved. Callers that
 * run after AuthProvider has mounted (the only way to render the app tree)
 * can rely on this being non-null.
 */
export function getKeycloak() {
  return _keycloak;
}

/**
 * @returns {object | null} The resolved tenant ({tenantId, domain,
 * keycloakRealm, status}) or {@code null} if not yet initialised.
 */
export function getTenant() {
  return _tenant;
}
