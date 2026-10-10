/**
 * Active-role store for users with multiple roles.
 *
 * <p>A user's JWT can carry several dashboard-affecting roles (e.g. an
 * executive who is also an admin). The auto-derived "highest" role is a
 * reasonable default, but the user should be able to explicitly act as
 * any role they hold — the dashboard, tab visibility, and copilot
 * surface area all key off this selection.</p>
 *
 * <p>The selected role slug (e.g. {@code "ceo"}, {@code "admin"})
 * persists in localStorage so the choice survives reloads.
 * Components subscribe to {@code orphos:active-role} window events to
 * re-render when the selection changes — same pattern as
 * {@code actingTenant}.</p>
 */
const STORAGE_KEY = 'orphos.active-role';
const EVENT_NAME  = 'orphos:active-role';

export function getActiveRole() {
  try {
    return localStorage.getItem(STORAGE_KEY) || null;
  } catch {
    return null;
  }
}

/**
 * @param {string | null} slug A role slug like "admin", "ceo", or null to clear.
 */
export function setActiveRole(slug) {
  try {
    if (slug) {
      localStorage.setItem(STORAGE_KEY, slug);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  } catch { /* localStorage disabled — silently no-op */ }
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: slug }));
}

export function clearActiveRole() {
  setActiveRole(null);
}

/**
 * Subscribe to active-role changes. Returns an unsubscribe function.
 *
 * @param {(slug: string | null) => void} handler
 */
export function onActiveRoleChange(handler) {
  const wrap = e => handler(e.detail ?? getActiveRole());
  window.addEventListener(EVENT_NAME, wrap);
  return () => window.removeEventListener(EVENT_NAME, wrap);
}
