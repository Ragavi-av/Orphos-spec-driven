// ── Access-control resource catalog ─────────────────────────────────────────
// The canonical, role-agnostic tree of resources whose access can be managed
// from the Access Control tab. The backend stores opaque (role, resourceKey)
// grants; this catalog says what those keys mean and how they nest
// (group → tab/section). It is DERIVED from screenRegistry.js — the single
// source of truth for every admin tab / user screen — so a screen added to the
// registry becomes grantable here automatically, with no drift from AdminPage.

import { ADMIN_GROUP_ORDER, ADMIN_SCREENS, USER_SCREENS, isGrantable } from './screenRegistry';

// Admin groups with their GRANTABLE tabs. A group whose every screen is
// platform-scoped (security-ops, platform) yields no tabs and drops out.
export const ADMIN_GROUPS = ADMIN_GROUP_ORDER
  .map(([key, label]) => ({
    key,
    label,
    tabs: ADMIN_SCREENS
      .filter(s => s.group === key && isGrantable(s))
      .map(s => ({ key: s.key, label: s.label })),
  }))
  .filter(g => g.tabs.length > 0);

// User-facing dashboard sections (plugin-driven) — grouped under one pseudo-group.
export const USER_GROUP = {
  key: 'user-dashboard', label: 'User Dashboard',
  tabs: USER_SCREENS.map(s => ({ key: s.key, label: s.label })),
};

export const ACCESS_GROUPS = [...ADMIN_GROUPS, USER_GROUP];

// Platform-scoped surface — never grantable to any other role (kept in sync with
// AccessControlService.LOCKED_RESOURCES on the backend). Derived: every
// non-grantable screen key, plus any group whose every screen is locked (so the
// whole group is deny-only by key too).
export const LOCKED_RESOURCES = (() => {
  const locked = new Set(ADMIN_SCREENS.filter(s => !isGrantable(s)).map(s => s.key));
  for (const [groupKey] of ADMIN_GROUP_ORDER) {
    const inGroup = ADMIN_SCREENS.filter(s => s.group === groupKey);
    if (inGroup.length > 0 && inGroup.every(s => !isGrantable(s))) locked.add(groupKey);
  }
  return locked;
})();

// Roles whose access is fixed (full access, not editable).
export const IMMUTABLE_ROLES = new Set(['platform_admin']);

// Self-lockout guard: the tenant 'admin' role can never lose the Access Control
// tab or its group, so admins can always get back in and fix a bad grant.
export const ADMIN_LOCKOUT_GUARD = new Set(['access-control', 'auth-authz']);

// tab key → group key, for hierarchy lookups.
export const GROUP_OF = (() => {
  const m = {};
  for (const g of ACCESS_GROUPS) for (const t of g.tabs) m[t.key] = g.key;
  return m;
})();

/**
 * Shape a flat grants array ([{role, resourceKey, effect}]) into a nested map:
 *   { [role]: { [resourceKey]: 'allow' | 'deny' } }
 */
export function toGrantMap(grants) {
  const m = {};
  for (const g of grants || []) {
    if (!g?.role || !g?.resourceKey) continue;
    (m[g.role] ||= {})[g.resourceKey] = g.effect;
  }
  return m;
}

/**
 * The explicit effect for (role, resourceKey), or undefined if not set.
 * grantMap is the nested map from {@link toGrantMap}.
 */
export function explicitEffect(grantMap, role, resourceKey) {
  return grantMap?.[role]?.[resourceKey];
}

/**
 * The DEFAULT effect for a single role + group when nothing explicit is set.
 * Encodes today's hardcoded behaviour so an empty access map reproduces it
 * exactly: full/platform admin see everything; each scoped admin sees its own
 * group; the user dashboard is visible to everyone by default; everything else
 * is denied.
 */
export function roleDefault(role, groupKey) {
  if (IMMUTABLE_ROLES.has(role) || role === 'admin') return 'allow';
  if (groupKey === 'user-dashboard') return 'allow';
  if (role === 'ingestion_admin') return groupKey === 'documents' ? 'allow' : 'deny';
  if (role === 'data_ai_admin')   return (groupKey === 'data' || groupKey === 'ai') ? 'allow' : 'deny';
  if (role === 'secops_admin')    return groupKey === 'auth-authz' ? 'allow' : 'deny';
  return 'deny';
}

/**
 * Effective access for ONE role on a resource: explicit-on-resource wins, then
 * explicit-on-group, then the role default. Immutable roles always allow;
 * locked resources allow only immutable roles. Returns 'allow' | 'deny'.
 */
export function effectiveAccess(grantMap, role, resourceKey, groupKey) {
  if (IMMUTABLE_ROLES.has(role)) return 'allow';
  if (role === 'admin' && ADMIN_LOCKOUT_GUARD.has(resourceKey)) return 'allow';
  if (LOCKED_RESOURCES.has(resourceKey) || (groupKey && LOCKED_RESOURCES.has(groupKey))) return 'deny';
  const onResource = explicitEffect(grantMap, role, resourceKey);
  if (onResource === 'allow' || onResource === 'deny') return onResource;
  const onGroup = groupKey ? explicitEffect(grantMap, role, groupKey) : undefined;
  if (onGroup === 'allow' || onGroup === 'deny') return onGroup;
  return roleDefault(role, groupKey);
}

/**
 * Is a resource visible to a user holding {@code roles} (allow-wins across the
 * held roles)? Used by the tab/section visibility filters.
 */
export function anyRoleAllows(grantMap, roles, resourceKey, groupKey) {
  return (roles || []).some(r => effectiveAccess(grantMap, r, resourceKey, groupKey) === 'allow');
}

/**
 * The EXPLICIT effect for a resource across a set of held roles (tab overrides
 * group; allow-wins across roles), ignoring role defaults. Returns
 * 'allow' | 'deny' | undefined (undefined = no explicit opinion → caller keeps
 * its base behaviour). Used for additive dashboard-section overrides so an empty
 * map leaves today's visibility untouched.
 */
export function mergeEffect(grantMap, roles, resourceKey, groupKey) {
  let sawDeny = false;
  for (const role of roles || []) {
    const e = explicitEffect(grantMap, role, resourceKey)
           ?? (groupKey ? explicitEffect(grantMap, role, groupKey) : undefined);
    if (e === 'allow') return 'allow';
    if (e === 'deny') sawDeny = true;
  }
  return sawDeny ? 'deny' : undefined;
}
