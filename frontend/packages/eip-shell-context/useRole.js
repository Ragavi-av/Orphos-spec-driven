import { useEffect, useState } from 'react';
import { useAuth } from './AuthProvider';
import { getActiveRole, onActiveRoleChange } from './activeRole';
import { useRoleLabels } from './RolesProvider';

/**
 * Returns the user's Orphos role derived from the Keycloak JWT.
 *
 * Role hierarchy (highest → lowest):
 *   admin > ceo > cto > cfo > executive > viewer > guest
 *
 * Roles arrive in realm_access.roles — populated by:
 *   - Azure Entra ID app role assignments  → oidc-role-idp-mapper (syncMode FORCE)
 *   - Keycloak default role                → guest (assigned to every new user)
 *
 * When a user holds multiple dashboard-affecting roles, the active role
 * (persisted via {@code activeRole}) wins; if none is selected, the FIRST
 * role in the JWT's realm_access.roles array is used as the default. The
 * derived booleans (isAdmin/isCeo/...) reflect the ACTIVE role so the
 * dashboard, tabs, and admin-page access all switch together when the user
 * picks a different role.
 *
 * {@code isPlatformAdmin} is independent of the picker — it's a cross-tenant
 * meta-role read straight from the JWT and never overridden.
 */

// Dashboard-affecting roles, in the canonical priority order used when no
// explicit selection has been made beyond "first assigned".
const DASHBOARD_ROLES = ['admin', 'ceo', 'cto', 'cfo', 'executive', 'viewer'];

export function useRole() {
  const keycloak = useAuth();

  const roles = keycloak?.tokenParsed?.realm_access?.roles ?? [];

  // Tenant role aliases + the set of custom (admin-created) role keys. Safe when
  // no RolesProvider is mounted (returns built-in labels, no custom keys).
  const { roleLabel, customRoleKeys } = useRoleLabels();

  // The selectable roles the user actually holds, preserved in JWT order so
  // "first assigned" remains the default. Includes the built-in dashboard roles
  // AND any custom roles the tenant admin defined, so a user can act as one.
  const availableRoles = roles.filter(r =>
    DASHBOARD_ROLES.includes(r) || customRoleKeys.includes(r));

  // Active role state — subscribed to the activeRole store so a change in
  // any component re-renders every useRole consumer.
  const [activeSlug, setActiveSlug] = useState(() => getActiveRole());
  useEffect(() => onActiveRoleChange(setActiveSlug), []);

  // Resolve the slug we should treat as "current":
  //   1. Stored selection, if the user still holds it
  //   2. First assigned dashboard role
  //   3. Fallback to whichever non-dashboard role they have (guest, etc.)
  // Default selection when the user hasn't explicitly picked yet: prefer 'admin'
  // when held — admins land in the admin experience first — otherwise fall back
  // to the first assigned dashboard role (JWT order). An explicit stored
  // selection always wins, so switching to a user role via the picker persists.
  const defaultSlug = availableRoles.includes('admin') ? 'admin' : (availableRoles[0] ?? null);
  const effectiveSlug =
    (activeSlug && availableRoles.includes(activeSlug)) ? activeSlug
    : defaultSlug;

  // platform_admin is issued by the SEPARATE "platform" realm and lets the
  // holder cross-tenant — manage every tenant's lifecycle, list all tenants,
  // hard-delete tenants. Strictly stronger than the per-tenant ROLE_admin.
  // Always read from the JWT — the role picker can't toggle it.
  const isPlatformAdmin = roles.includes('platform_admin');

  // Scoped admin capability: manage document-ingestion defaults + search
  // settings (and the Document Ingestion admin tabs), but nothing else in the
  // Admin section. Read straight from the JWT and implied by full admin /
  // platform admin — independent of the role picker, like isPlatformAdmin.
  const isIngestionAdmin = roles.includes('ingestion_admin')
    || roles.includes('admin') || isPlatformAdmin;

  // Scoped admin capability: manage the Data & AI group only (AI Providers,
  // LLM Prompts, Query History, Ontology Sources) — nothing else in the Admin
  // section. Read straight from the JWT and implied by full admin / platform
  // admin — independent of the role picker, like isIngestionAdmin.
  const isDataAiAdmin = roles.includes('data_ai_admin')
    || roles.includes('admin') || isPlatformAdmin;

  // Scoped admin capability: manage the Authentication & Authorization group
  // only (SSO Configuration, User Management, Authentication) — nothing else in
  // the Admin section. Read straight from the JWT and implied by full admin /
  // platform admin — independent of the role picker, like isIngestionAdmin.
  const isSecopsAdmin = roles.includes('secops_admin')
    || roles.includes('admin') || isPlatformAdmin;

  // Booleans reflect the ACTIVE role so dashboard rendering and admin-page
  // access change together when the user switches roles.
  const isAdmin     = effectiveSlug === 'admin' || isPlatformAdmin;
  const isCeo       = effectiveSlug === 'ceo';
  const isCto       = effectiveSlug === 'cto';
  const isCfo       = effectiveSlug === 'cfo';
  const isExecutive = effectiveSlug === 'executive' || isCfo || isCto || isCeo || isAdmin;
  const isViewer    = effectiveSlug === 'viewer'    || isExecutive;
  const isGuest     = !effectiveSlug && roles.includes('guest');

  // CANONICAL label — kept stable (English built-in names) because it is used
  // for gating comparisons (e.g. `role === 'Viewer'`) across the app. Do NOT
  // alias this; use `roleDisplay` for anything shown to the user.
  const role = isPlatformAdmin       ? 'Platform Admin'
             : effectiveSlug === 'admin'     ? 'Admin'
             : effectiveSlug === 'ceo'       ? 'CEO'
             : effectiveSlug === 'cto'       ? 'CTO'
             : effectiveSlug === 'cfo'       ? 'CFO'
             : effectiveSlug === 'executive' ? 'Executive'
             : effectiveSlug === 'viewer'    ? 'Viewer'
             : 'Guest';

  // DISPLAY label — routes through the tenant alias map (RolesProvider), so a
  // renamed role (ceo→"President") or a custom role shows its alias wherever a
  // role is shown to the user. Falls back to the built-in label.
  const roleDisplay = isPlatformAdmin ? roleLabel('platform_admin')
             : roleLabel(effectiveSlug || 'guest');

  const hasRole = (required) => roles.includes(required);

  return {
    role, roleDisplay, roleLabel, roles,
    availableRoles, activeRole: effectiveSlug,
    isPlatformAdmin, isIngestionAdmin, isDataAiAdmin, isSecopsAdmin,
    isAdmin, isCeo, isCto, isCfo, isExecutive, isViewer, isGuest,
    hasRole,
  };
}
