import axios from 'axios';
import { getKeycloak, getTenant } from './keycloak';
import { getActingTenant } from './actingTenant';

/**
 * Axios request interceptor — attaches the Keycloak Bearer token AND an
 * {@code X-Tenant-Id} header to every outgoing API request.
 *
 * <p>Why the header when the JWT already carries {@code tenant_id}: it makes
 * unauthenticated bootstrap calls (and the platform-superadmin
 * acting-on-tenant flow) work uniformly with the same downstream
 * {@code TenantResolutionFilter} code path. For authenticated user requests
 * it is redundant-but-harmless — the filter prefers the JWT claim.</p>
 *
 * <p>Token refresh: if the access token is within 30 s of expiry the
 * interceptor refreshes it before sending the request. On 401, it refreshes
 * once and retries. On refresh failure the user is bounced to login.</p>
 */
axios.interceptors.request.use(async config => {
  const keycloak = getKeycloak();
  const tenant = getTenant();

  if (keycloak?.authenticated) {
    try {
      await keycloak.updateToken(30);
    } catch {
      keycloak.login();
      return Promise.reject(new Error('Session expired — redirecting to login'));
    }
    config.headers = config.headers || {};
    config.headers['Authorization'] = `Bearer ${keycloak.token}`;
  }

  // Tenant header resolution order:
  //   1) explicit X-Tenant-Id already set on the request (caller wins)
  //   2) platform-admin "acting tenant" override from localStorage (the
  //      header bar picker — only honoured by the backend when the caller
  //      actually has ROLE_platform_admin)
  //   3) the SPA's resolved tenant (host-based or JWT-derived)
  config.headers = config.headers || {};
  if (!config.headers['X-Tenant-Id']) {
    const acting = getActingTenant();
    if (acting?.tenantId) {
      config.headers['X-Tenant-Id'] = acting.tenantId;
    } else if (tenant?.tenantId) {
      config.headers['X-Tenant-Id'] = tenant.tenantId;
    }
  }

  return config;
});

axios.interceptors.response.use(
  response => response,
  async error => {
    const originalRequest = error.config;
    const keycloak = getKeycloak();

    if (error.response?.status === 401 && !originalRequest._retried && keycloak) {
      originalRequest._retried = true;
      try {
        await keycloak.updateToken(0);   // force immediate refresh
        originalRequest.headers['Authorization'] = `Bearer ${keycloak.token}`;
        return axios(originalRequest);
      } catch {
        keycloak.login();
      }
    }
    // 403 on an admin endpoint: valid session but lacks the required role.
    // Fire a window event so App.jsx can swap the AdminPage out and surface
    // a clean "access denied" panel instead of broken empty tabs.
    if (error.response?.status === 403
        && originalRequest?.url?.includes('/api/admin/')) {
      window.dispatchEvent(new CustomEvent('orphos:admin-forbidden', {
        detail: { url: originalRequest.url },
      }));
    }
    // 503 with X-Tenant-DB-Status: unavailable — tenant's dedicated data store
    // (ArcadeDB / Fuseki) is down. Fire a global event so the SPA can render a
    // tenant-aware degraded banner instead of letting every component show a
    // generic "request failed" toast.
    if (error.response?.status === 503
        && (error.response.headers?.['x-tenant-db-status'] === 'unavailable'
            || error.response.data?.code === 'tenant_data_unavailable')) {
      window.dispatchEvent(new CustomEvent('orphos:tenant-degraded', {
        detail: {
          url: originalRequest?.url,
          store: error.response.headers?.['x-tenant-db-store'] || null,
        },
      }));
    }
    return Promise.reject(error);
  }
);

export default axios;
