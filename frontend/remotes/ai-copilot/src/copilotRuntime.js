// Live AI Co-pilot conversation, held OUTSIDE React.
//
// Why a module singleton rather than component state:
//
// The co-pilot panel is mounted by the shell's dock (shell/src/App.jsx →
// CopilotDock/DockPanel). That dock UNMOUNTS the panel whenever the user moves
// away from it — flipping the dock to Documents or History, collapsing the dock
// to its rail, or opening the Admin section, which replaces the whole dashboard
// tree. While `messages`, the session id and the in-flight flag lived in
// AICopilot's own useState, every one of those moves threw the conversation
// away: the axios POST to /api/nl-query/ask was never actually cancelled, but
// its resolution had nowhere to land, and coming back re-mounted an empty panel.
// A slow model answer therefore looked like a query that had "stopped", and the
// user had to ask again.
//
// Keeping the conversation here instead means the request outlives the panel:
// the POST resolves into this store whether or not anything is rendering it, and
// a re-mounted panel just re-subscribes and shows either the still-spinning
// message or the finished answer. Module state lives as long as the loaded
// remote, i.e. for the life of the page — a genuine browser reload still drops
// an in-flight query, which would need a server-side job to survive.
//
// Consumed by AICopilot.jsx via useSyncExternalStore.

import axios from 'axios';
import { saveSession } from './sessionStore';

export function genId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function newSessionState() {
  return {
    sessionId: genId(),
    startedAt: new Date().toISOString(),
    messages:  [],
    // The half-typed question. Parked here too, so switching away mid-sentence
    // does not lose it either.
    draft:     '',
  };
}

let state = newSessionState();

const listeners = new Set();

/** useSyncExternalStore subscribe. */
export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** useSyncExternalStore getSnapshot — identity is stable until state changes. */
export function getSnapshot() {
  return state;
}

/** True while any message in the current session is still awaiting an answer. */
export function isBusy() {
  return state.messages.some(m => m.loading);
}

/**
 * Best-effort history write. Only completed messages are stored, so a session
 * whose only message is still in flight writes nothing — same rule the old
 * in-component effect used.
 */
function persist(snapshot, endedAt = null) {
  const completed = snapshot.messages.filter(m => !m.loading);
  if (completed.length === 0) return;
  saveSession({
    id:        snapshot.sessionId,
    startedAt: snapshot.startedAt,
    endedAt,
    label:     completed[0]?.question?.slice(0, 70) || null,
    messages:  completed,
  }).catch(() => { /* best-effort — a failed save must not break the chat */ });
}

/**
 * Commit a new state object and notify subscribers.
 *
 * History is only written when the message list actually changed: `draft`
 * updates fire on every keystroke and must not each cost a PUT.
 */
function commit(next) {
  const messagesChanged = next.messages !== state.messages;
  state = next;
  for (const listener of Array.from(listeners)) listener();
  if (messagesChanged) persist(state);
}

/** Store the in-progress input text. */
export function setDraft(draft) {
  if (draft === state.draft) return;
  commit({ ...state, draft });
}

/** Append an already-resolved message (e.g. the "provider needs a key" notice). */
export function appendMessage(message) {
  commit({ ...state, messages: [...state.messages, message] });
}

/**
 * Close the current session and open an empty one.
 *
 * Any request still in flight keeps running to completion, but its answer is
 * dropped by `resolve` below — the user has explicitly moved on, and dropping a
 * stale answer into a fresh session would be worse than losing it.
 */
export function startNewSession() {
  persist(state, new Date().toISOString());
  commit(newSessionState());
}

/** Land a finished (or failed) answer on its message, if it is still relevant. */
function resolve(sessionId, msgId, patch) {
  if (state.sessionId !== sessionId) return;                  // session replaced
  if (!state.messages.some(m => m.id === msgId)) return;      // message removed
  commit({
    ...state,
    messages: state.messages.map(m => (m.id === msgId ? { ...m, ...patch } : m)),
  });
}

function errorText(err) {
  const status = err.response?.status;
  const data   = err.response?.data;
  // The server's own reason, whatever shape it arrives in.
  const server = data?.error || data?.detail || data?.message
              || (typeof data === 'string' && data.trim() ? data.trim() : null);

  // No HTTP response at all → a genuine network/DNS/CORS failure, i.e. the
  // request never reached the service. This is the ONLY case that means
  // "containers may be down".
  if (!err.response) {
    return `Can't reach the NL query service (${err.code || 'no response'}). `
         + 'Check it is running:  docker ps | grep eip-nl-query';
  }

  // Every other case DID get a response — show the status and the server's real
  // reason so the actual fix is visible instead of a generic catch-all.
  if (status === 401) {
    return server
      ? `Not authorized (401): ${server}`
      : 'Not authorized (401) — your session may have expired, or the NL query service does not '
      + 'trust your login (internal service token / tenant issuers not configured on the server). '
      + 'Sign out and back in; if it persists, check eip-nl-query logs for "tenant-issuer poll".';
  }
  if (status === 403) {
    return server ? `Access denied (403): ${server}`
                  : 'Access denied (403) — your role is not permitted for this action.';
  }
  if (status === 404) {
    return server ? `Not found (404): ${server}`
                  : 'Endpoint not found (404) — the NL query service may still be starting up. Try again in ~30s.';
  }
  if (status >= 500) {
    return server ? `NL query service error (${status}): ${server}`
                  : `The NL query service returned ${status}. Check the selected AI provider and eip-nl-query logs.`;
  }
  // Any other 4xx (e.g. 400 "unknown provider") — surface the server's own reason.
  return server ? `NL query failed (${status}): ${server}`
                : `NL query failed (${status}).`;
}

/**
 * Send a question and record the answer when it arrives.
 *
 * @param {string}   query               the question; blank input is ignored
 * @param {string}   providerId          provider to answer with
 * @param {string}   [providerLabel]     display name for the message footer
 * @param {string}   [model]             explicit model override, external providers only
 * @param {string}   [sourceLabel]       "Context: …" badge for cross-remote asks
 * @param {string[]} [fallbackFollowups] suggestion chips when the answer carries none
 * @returns {string|null} the message id, or null if the query was empty
 */
export function ask({ query, providerId, providerLabel = null, model = null,
                      sourceLabel = null, fallbackFollowups = [] }) {
  const question = (query || '').trim();
  if (!question) return null;

  const msgId     = genId();
  const sessionId = state.sessionId;

  const payload = { question, provider: providerId };
  if (model) payload.model = model;

  commit({
    ...state,
    draft: '',
    messages: [...state.messages, {
      id: msgId, question, answer: null, followups: [], trust: null,
      provider: providerLabel || providerId,
      timestamp: new Date().toISOString(), loading: true, source: sourceLabel,
    }],
  });

  axios.post('/api/nl-query/ask', payload)
    .then(res => resolve(sessionId, msgId, {
      answer:    res.data?.answer || JSON.stringify(res.data),
      followups: Array.isArray(res.data?.followups) && res.data.followups.length > 0
                 ? res.data.followups : fallbackFollowups,
      // Prefer the family the service derived from the model it actually called
      // over `provider`, which is the routing key and mis-names the family for
      // the historical ids ('gemma' answers with gemini-*).
      provider:  res.data?.modelFamily || providerLabel || res.data?.provider || providerId,
      trust:     res.data?.trust || null,
      loading:   false,
    }))
    .catch(err => resolve(sessionId, msgId, {
      answer: errorText(err), followups: fallbackFollowups, loading: false,
    }));

  return msgId;
}

// Cross-remote "ask the co-pilot this" requests (WidgetBoard, TasksForAction,
// NotificationsPanel, the AI Summary tab…) arrive as a `copilotQuery` object on
// the shell context. Which ones have been handled is tracked HERE, by object
// identity, rather than by a mount-order ref inside the panel: the panel is
// unmounted for much of the time, and a ref reset on every re-mount both dropped
// requests raised while it was away and risked re-asking on return.
let lastExternalQuery = null;

/** True the first time a given `copilotQuery` object is seen. */
export function claimExternalQuery(externalQuery) {
  if (!externalQuery || externalQuery === lastExternalQuery) return false;
  lastExternalQuery = externalQuery;
  return true;
}
