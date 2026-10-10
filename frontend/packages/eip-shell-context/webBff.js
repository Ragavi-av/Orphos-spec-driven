import axios from './axiosAuth';
import { getKeycloak, getTenant } from './keycloak';
import { getActingTenant } from './actingTenant';

/**
 * Client for web-bff-service ({@code /api/web/*}), which collapses a screen's fan-out
 * into one round-trip.
 *
 * <p>Every BFF response is the same envelope: {@code data} keyed by source name,
 * {@code sources[name].status} (OK | FORBIDDEN | NOT_FOUND | UNAVAILABLE), and a
 * {@code partial} flag. {@link sourceResponse} turns one source back into what a direct
 * axios call would have produced, so callers keep their existing then/catch handling.</p>
 *
 * <p>The BFF is an optimisation, never a dependency: when it is down, not deployed, or
 * a single source comes back UNAVAILABLE, the caller's direct request runs instead. The
 * one case that does NOT fall back is a refusal (FORBIDDEN/NOT_FOUND) — the BFF forwards
 * the caller's own token, so a direct call would only be refused again.</p>
 */

/** Error shaped like an axios failure, so `e.response.status` checks keep working. */
function refusal(status, url) {
  const e = new Error(`Request failed with status code ${status}`);
  e.response = { status, data: null };
  e.config = { url };
  return e;
}

const HTTP_STATUS = { FORBIDDEN: 403, NOT_FOUND: 404 };

/**
 * One source out of an aggregate, as an axios-style `{ data }` response.
 *
 * @param {object|null} aggregate the BFF envelope, or null when the BFF call failed
 * @param {string} name   source key in the envelope
 * @param {function} direct thunk issuing the original request — the fallback
 * @param {string} [url]  the original URL, carried on a synthetic refusal for logging
 */
export async function sourceResponse(aggregate, name, direct, url) {
  const status = aggregate?.sources?.[name]?.status;
  if (status === 'OK') return { data: aggregate.data?.[name] ?? null, status: 200 };
  if (HTTP_STATUS[status]) throw refusal(HTTP_STATUS[status], url);
  return direct();
}

// ── Shell bootstrap ──────────────────────────────────────────────────────────
//
// The shell's startup reads (plugins, widget layout, role aliases, access map,
// document count) live in separate providers that mount independently. Rather
// than hoist them into one component, each provider asks for its own source and
// they share ONE /api/web/bootstrap request: the first caller fires it, the rest
// join it. Each source is handed out once — a later read of the same source is a
// deliberate refetch (an admin saved roles, a layout changed) and must go to the
// owning service for fresh data.

/** Past this age the shared response is treated as spent rather than reused. */
const BOOTSTRAP_TTL_MS = 30_000;

let bootstrap = null;   // { key, at, promise, consumed: Set }

/** Tenant the request will be scoped to — mirrors axiosAuth's header resolution. */
function tenantKey() {
  return getActingTenant()?.tenantId || getTenant()?.tenantId || '';
}

function sharedBootstrap() {
  const key = tenantKey();
  const fresh = bootstrap && bootstrap.key === key && Date.now() - bootstrap.at < BOOTSTRAP_TTL_MS;
  if (!fresh) {
    bootstrap = {
      key,
      at: Date.now(),
      consumed: new Set(),
      promise: axios.get('/api/web/bootstrap')
        // Guard the envelope: an SPA catch-all serving index.html at 200 would
        // otherwise read as "every source missing" rather than "BFF absent".
        .then(r => (r.data && typeof r.data.sources === 'object' ? r.data : null))
        .catch(() => null),
    };
  }
  return bootstrap;
}

/**
 * GET a shell-startup resource, via the shared bootstrap aggregate when possible.
 *
 * Drop-in for `axios.get(url)`: resolves to `{ data }`, rejects like axios does.
 *
 * @param {string} name source key in the bootstrap screen, e.g. 'plugins'
 * @param {string} url  the owning service's route, used for fallback and refetch
 */
export async function bootstrapGet(name, url) {
  const direct = () => axios.get(url);
  // Unauthenticated, the BFF would 401 the whole batch — skip straight to the
  // route, which knows its own anonymous behaviour.
  if (!getKeycloak()?.authenticated) return direct();

  const entry = sharedBootstrap();
  if (entry.consumed.has(name)) return direct();
  entry.consumed.add(name);
  return sourceResponse(await entry.promise, name, direct, url);
}
