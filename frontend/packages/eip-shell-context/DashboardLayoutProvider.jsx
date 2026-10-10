import { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import axios from './axiosAuth';
import { bootstrapGet } from './webBff';
import { useTenant } from './AuthProvider';
import { useToast } from './ToastProvider';
import { CATALOG, DEFAULT_VISIBLE, ALL_IDS } from './catalog';

/**
 * Fire from any component to force the provider to refetch the layout
 * (e.g. after an admin saves a new tenant default).
 */
export const DASHBOARD_LAYOUT_CHANGED_EVENT = 'orphos:dashboard-layout-changed';
export function notifyDashboardLayoutChanged() {
  window.dispatchEvent(new Event(DASHBOARD_LAYOUT_CHANGED_EVENT));
}

// Legacy localStorage keys — read once on first load to migrate any
// pre-existing customisation into the backend, then cleared.
const LEGACY_LS_VISIBLE = 'eip_widget_visible';
const LEGACY_LS_ORDER   = 'eip_widget_order';

const DEFAULT_STATE = {
  visibleIds: DEFAULT_VISIBLE,
  order: ALL_IDS,
  source: 'system',  // 'user' | 'tenant' | 'system'
  loading: true,
  error: null,
};

const DashboardLayoutContext = createContext({
  ...DEFAULT_STATE,
  save: () => {},
  resetToTenantDefault: () => {},
});

const SAVE_DEBOUNCE_MS = 350;

function sanitise(visibleIds, order) {
  // Dedupe only — do NOT filter against the static ALL_IDS here, or dynamic
  // (data-onboarded) widget ids would be stripped before the PUT. The server
  // validates ids against the static catalog ∪ the tenant's dynamic widgets.
  const dedup = (arr) => Array.from(new Set((arr || []).filter(Boolean)));
  const ord = dedup(order);
  // Backfill the built-in catalog ids so every static widget has a position.
  ALL_IDS.forEach(id => { if (!ord.includes(id)) ord.push(id); });
  return { visibleIds: dedup(visibleIds), order: ord };
}

/**
 * DashboardLayoutProvider — fetches the per-user dashboard layout on tenant
 * change and exposes it via the {@code useDashboardLayout()} hook.
 *
 * <p>Sits below AuthProvider in the React tree so tenant context is already
 * resolved when this mounts. Mirrors BrandingProvider in shape.</p>
 */
export function DashboardLayoutProvider({ children }) {
  const tenant = useTenant();
  const toast = useToast();
  const [state, setState] = useState(() => ({ ...DEFAULT_STATE }));
  const saveTimer = useRef(null);
  const pendingPayload = useRef(null);
  const migrationDoneFor = useRef(new Set());  // tenantIds already considered for migration

  const fetchLayout = useCallback(() => {
    let cancelled = false;
    (async () => {
      try {
        const r = await bootstrapGet('dashboardLayout', '/api/dashboard/layout/me');
        if (cancelled) return;
        // Guard: if the proxy isn't wired and the SPA's index.html catch-all
        // intercepts the call, axios resolves with HTML at 200. Treat any
        // response missing the expected fields as a fault and keep the
        // initial DEFAULT_VISIBLE state instead of falling to an empty board.
        const d = r.data;
        if (!d || typeof d !== 'object' || !Array.isArray(d.visibleIds)) {
          // eslint-disable-next-line no-console
          console.error('[dashboard-layout] /me returned unexpected payload — is /api/dashboard/ proxied to admin-service?', d);
          setState(s => ({ ...s, loading: false, error: 'dashboard-layout-bad-response' }));
          return;
        }
        // `source` is 'user' | 'tenant' | 'persona' | 'system'. The server already
        // picked the right tier — including the persona board derived from the
        // tenant's own vocabulary — so this must not second-guess it.
        const { visibleIds, order } = sanitise(d.visibleIds, d.order);
        setState({
          visibleIds,
          order,
          source: d.source || 'system',
          loading: false,
          error: null,
        });

        // One-shot migration: if the backend has no user record and no tenant
        // default (source === 'system') AND the legacy localStorage keys
        // exist, push them up once then clear. Tracked per-tenant so we don't
        // re-migrate when the user switches tenants.
        const tenantKey = tenant?.tenantId || 'unknown';
        if (r.data?.source === 'system' && !migrationDoneFor.current.has(tenantKey)) {
          migrationDoneFor.current.add(tenantKey);
          const legacyVisible = readJson(LEGACY_LS_VISIBLE);
          const legacyOrder = readJson(LEGACY_LS_ORDER);
          if (legacyVisible && legacyVisible.length > 0) {
            const migrated = sanitise(legacyVisible, legacyOrder || ALL_IDS);
            try {
              const put = await axios.put('/api/dashboard/layout/me', migrated);
              const m = sanitise(put.data?.visibleIds, put.data?.order);
              setState({
                visibleIds: m.visibleIds,
                order: m.order,
                source: put.data?.source || 'user',
                loading: false,
                error: null,
              });
              localStorage.removeItem(LEGACY_LS_VISIBLE);
              localStorage.removeItem(LEGACY_LS_ORDER);
            } catch {
              // Leave legacy keys alone if migration fails — we'll retry on next mount.
            }
          }
        }
      } catch (e) {
        if (cancelled) return;
        // Surface backend failures in the console so silent 500s don't look
        // like the UI just "didn't save" — a real complaint we've already
        // chased once around the `order` reserved-word bug.
        // eslint-disable-next-line no-console
        console.error('[dashboard-layout] fetch failed', e?.response?.status, e?.response?.data || e?.message);
        setState(s => ({
          ...s,
          loading: false,
          error: e?.response?.data?.message || e?.message || 'dashboard-layout-fetch-failed',
        }));
      }
    })();
    return () => { cancelled = true; };
  }, [tenant?.tenantId]);

  // Initial load + tenant switch.
  useEffect(() => {
    if (!tenant?.tenantId) {
      setState({ ...DEFAULT_STATE, loading: false });
      return;
    }
    setState(s => ({ ...s, loading: true }));
    return fetchLayout();
  }, [tenant?.tenantId, fetchLayout]);

  // Cross-component refetch (e.g. admin saved a new tenant default).
  useEffect(() => {
    const handler = () => fetchLayout();
    window.addEventListener(DASHBOARD_LAYOUT_CHANGED_EVENT, handler);
    return () => window.removeEventListener(DASHBOARD_LAYOUT_CHANGED_EVENT, handler);
  }, [fetchLayout]);

  // Cancel any pending save on unmount / tenant switch so we don't write
  // tenant A's layout into tenant B's account during a fast switch.
  useEffect(() => {
    return () => {
      if (saveTimer.current) {
        clearTimeout(saveTimer.current);
        saveTimer.current = null;
      }
      pendingPayload.current = null;
    };
  }, [tenant?.tenantId]);

  // Optimistic save — update state immediately so drag/toggle stays snappy,
  // then debounce the PUT so rapid reorders coalesce into a single request.
  const save = useCallback(({ visibleIds, order }) => {
    const s = sanitise(visibleIds, order);
    setState(prev => ({ ...prev, visibleIds: s.visibleIds, order: s.order, source: 'user' }));
    pendingPayload.current = s;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(async () => {
      const payload = pendingPayload.current;
      pendingPayload.current = null;
      saveTimer.current = null;
      if (!payload) return;
      try {
        const r = await axios.put('/api/dashboard/layout/me', payload);
        const d = r.data;
        if (!d || typeof d !== 'object' || !Array.isArray(d.visibleIds)) {
          // eslint-disable-next-line no-console
          console.error('[dashboard-layout] PUT /me returned unexpected payload — is /api/dashboard/ proxied?', d);
          setState(prev => ({ ...prev, error: 'dashboard-layout-bad-response' }));
          return;
        }
        const m = sanitise(d.visibleIds, d.order);
        setState(prev => ({
          ...prev,
          visibleIds: m.visibleIds,
          order: m.order,
          source: d.source || 'user',
          error: null,
        }));
        // No toast here: this is a debounced auto-save that fires on every
        // widget drag/toggle/reorder — a confirmation on each would spam the
        // user. Deliberate actions (reset to default) toast below.
      } catch (e) {
        // eslint-disable-next-line no-console
        console.error('[dashboard-layout] save failed', e?.response?.status, e?.response?.data || e?.message);
        setState(prev => ({
          ...prev,
          error: e?.response?.data?.message || e?.message || 'dashboard-layout-save-failed',
        }));
      }
    }, SAVE_DEBOUNCE_MS);
  }, []);

  const resetToTenantDefault = useCallback(async () => {
    try {
      const r = await axios.delete('/api/dashboard/layout/me');
      const m = sanitise(r.data?.visibleIds, r.data?.order);
      setState({
        visibleIds: m.visibleIds,
        order: m.order,
        source: r.data?.source || 'tenant',
        loading: false,
        error: null,
      });
      toast.success('Dashboard layout reset to default.');
    } catch (e) {
      setState(prev => ({
        ...prev,
        error: e?.response?.data?.message || e?.message || 'dashboard-layout-reset-failed',
      }));
    }
  }, [toast]);

  const value = {
    visibleIds: state.visibleIds,
    order: state.order,
    source: state.source,
    loading: state.loading,
    error: state.error,
    save,
    resetToTenantDefault,
  };

  return (
    <DashboardLayoutContext.Provider value={value}>
      {children}
    </DashboardLayoutContext.Provider>
  );
}

export function useDashboardLayout() {
  return useContext(DashboardLayoutContext);
}

function readJson(key) {
  try {
    const v = localStorage.getItem(key);
    if (!v) return null;
    const parsed = JSON.parse(v);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

// Re-export the catalog so consumers can import everything from one module.
export { CATALOG, DEFAULT_VISIBLE, ALL_IDS };
