import { createContext, useContext, useEffect, useState, useCallback } from 'react';
import { bootstrapGet } from './webBff';
import { useTenant } from './AuthProvider';

/**
 * RolesProvider — fetches the tenant's role aliases (custom display names) once
 * per tenant and exposes them via {@code useRoleLabels()}. Aliases are the label
 * shown to users everywhere a role appears (header chip, role picker, user
 * table, access control). Mirrors BrandingProvider in shape and lifecycle.
 *
 * The alias map is `{ roleKey: displayName }` for every assignable role in the
 * tenant (built-in and custom), served by the authenticated, non-admin endpoint
 * {@code GET /api/roles/aliases} so ALL users — not just admins — see aliases.
 */

// Built-in fallback labels — used when the tenant has no alias override for a
// role and the endpoint hasn't loaded yet. Mirrors the backend DEFAULT_LABELS.
const BUILTIN_LABELS = {
  platform_admin: 'Platform Admin',
  admin: 'Admin',
  ceo: 'CEO',
  cto: 'CTO',
  cfo: 'CFO',
  executive: 'Executive',
  viewer: 'Viewer',
  guest: 'Guest',
  ingestion_admin: 'Ingestion Admin',
  data_ai_admin: 'Data & AI Admin',
  secops_admin: 'SecOps Admin',
};

/** "operations_head" → "Operations Head" — readable fallback for unknown slugs. */
function humanize(slug) {
  return String(slug || '')
    .split('_').filter(Boolean)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ') || String(slug || '');
}

/** Fire to make RolesProvider refetch (e.g. after an admin edits roles/aliases). */
export const ROLES_CHANGED_EVENT = 'orphos:roles-changed';
export function notifyRolesChanged() {
  window.dispatchEvent(new Event(ROLES_CHANGED_EVENT));
}

const DEFAULT_VALUE = { aliases: {}, loading: true };
const RolesContext = createContext(DEFAULT_VALUE);

export function RolesProvider({ children }) {
  const tenant = useTenant();
  const [aliases, setAliases] = useState({});

  const fetchAliases = useCallback(() => {
    let cancelled = false;
    (async () => {
      try {
        const r = await bootstrapGet('roleAliases', '/api/roles/aliases');
        if (cancelled) return;
        setAliases(r.data && typeof r.data === 'object' ? r.data : {});
      } catch {
        if (!cancelled) setAliases({});
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!tenant?.tenantId) { setAliases({}); return; }
    return fetchAliases();
  }, [tenant?.tenantId, fetchAliases]);

  useEffect(() => {
    const handler = () => fetchAliases();
    window.addEventListener(ROLES_CHANGED_EVENT, handler);
    return () => window.removeEventListener(ROLES_CHANGED_EVENT, handler);
  }, [fetchAliases]);

  return (
    <RolesContext.Provider value={{ aliases }}>
      {children}
    </RolesContext.Provider>
  );
}

/**
 * Returns { aliases, roleLabel, customRoleKeys }.
 *  - roleLabel(slug): tenant alias → built-in label → humanised slug.
 *  - customRoleKeys: role keys present in the tenant that aren't built-ins.
 * Safe to call without a provider (returns built-in labels only).
 */
export function useRoleLabels() {
  const { aliases } = useContext(RolesContext);
  const roleLabel = useCallback((slug) => {
    if (!slug) return '';
    return aliases?.[slug] || BUILTIN_LABELS[slug] || humanize(slug);
  }, [aliases]);
  const customRoleKeys = Object.keys(aliases || {}).filter(k => !(k in BUILTIN_LABELS));
  return { aliases: aliases || {}, roleLabel, customRoleKeys };
}

/** Non-hook accessor for the built-in fallback label of a slug. */
export function builtinRoleLabel(slug) {
  return BUILTIN_LABELS[slug] || humanize(slug);
}
