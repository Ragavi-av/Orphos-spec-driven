// Server-backed store for AI Co-pilot session history.
//
// Sessions are persisted in ArcadeDB via nl-query-service
// (/api/nl-query/me/copilot/sessions), scoped server-side to the logged-in
// user's Keycloak sub and tenant. This replaced the old localStorage store so
// history follows the user across devices and survives browser data clears.
// All writes stay best-effort like before: callers .catch() and move on —
// a failed save must never break the chat itself.

import axios from 'axios';

const API = '/api/nl-query/me/copilot/sessions';

// Legacy localStorage key (pre server-side history) — read once by
// migrateLocalSessions() below, then removed.
const LEGACY_BASE_KEY = 'orphos.copilot.sessions';

/** Resolve a stable per-user identity from the Keycloak token (legacy key namespace). */
export function identityFrom(keycloak) {
  return keycloak?.tokenParsed?.sub
      || keycloak?.tokenParsed?.tenant_id
      || null;
}

function notifyUpdated() {
  window.dispatchEvent(new Event('orphos:sessions-updated'));
}

/** Fetch the current user's sessions, newest first. */
export async function fetchSessions() {
  const res = await axios.get(API);
  return Array.isArray(res.data?.sessions) ? res.data.sessions : [];
}

/** Create or update one session ({id, label, startedAt, endedAt, messages}). */
export async function saveSession(session) {
  await axios.put(`${API}/${encodeURIComponent(session.id)}`, {
    label:     session.label ?? null,
    startedAt: session.startedAt ?? null,
    endedAt:   session.endedAt ?? null,
    messages:  session.messages ?? [],
  });
  notifyUpdated();
}

export async function removeSession(id) {
  await axios.delete(`${API}/${encodeURIComponent(id)}`);
  notifyUpdated();
}

export async function clearSessions() {
  await axios.delete(API);
  notifyUpdated();
}

/**
 * One-time migration: push any pre-existing localStorage history to the
 * server, then drop the local copy. The key is only removed after every
 * session uploaded, so a mid-migration failure retries on next load.
 */
export async function migrateLocalSessions(identity) {
  const key = identity ? `${LEGACY_BASE_KEY}.${identity}` : LEGACY_BASE_KEY;
  let sessions;
  try {
    sessions = JSON.parse(localStorage.getItem(key) || '[]');
  } catch {
    sessions = [];
  }
  if (!Array.isArray(sessions) || sessions.length === 0) {
    try { localStorage.removeItem(key); } catch { /* ignore */ }
    return;
  }
  for (const s of sessions) {
    if (!s?.id) continue;
    await axios.put(`${API}/${encodeURIComponent(s.id)}`, {
      label:     s.label ?? null,
      startedAt: s.startedAt ?? null,
      endedAt:   s.endedAt ?? null,
      messages:  s.messages ?? [],
    });
  }
  try { localStorage.removeItem(key); } catch { /* ignore */ }
  notifyUpdated();
}
