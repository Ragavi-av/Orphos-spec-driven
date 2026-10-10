/**
 * Platform-admin tenant impersonation store.
 *
 * <p>A platform_admin's JWT carries no tenant_id claim, so by default
 * tenant-scoped admin-service endpoints can't tell which tenant they should
 * read. Setting an "acting tenant" here makes the axios interceptor attach
 * {@code X-Tenant-Id: <uuid>} on every outbound request; admin-service's
 * TenantResolutionFilter honours the header only when the caller has
 * ROLE_platform_admin.</p>
 *
 * <p>Selection persists in localStorage so the same tenant stays in scope
 * across reloads. Components subscribe to the {@code orphos:acting-tenant}
 * window event to re-render when the selection changes.</p>
 */
const STORAGE_KEY = 'orphos.acting-tenant';
const EVENT_NAME  = 'orphos:acting-tenant';

export function getActingTenant() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * @param {{tenantId: string, domain?: string, displayName?: string} | null} tenant
 *   Pass an object to set the acting tenant, or null to clear it.
 */
export function setActingTenant(tenant) {
  try {
    if (tenant && tenant.tenantId) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        tenantId:    tenant.tenantId,
        domain:      tenant.domain ?? null,
        displayName: tenant.displayName ?? null,
      }));
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  } catch { /* localStorage disabled — silently no-op */ }
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: tenant }));
}

export function clearActingTenant() {
  setActingTenant(null);
}

/**
 * Subscribe to acting-tenant changes. Returns an unsubscribe function.
 *
 * @param {(t: object | null) => void} handler
 */
export function onActingTenantChange(handler) {
  const wrap = e => handler(e.detail ?? getActingTenant());
  window.addEventListener(EVENT_NAME, wrap);
  return () => window.removeEventListener(EVENT_NAME, wrap);
}
