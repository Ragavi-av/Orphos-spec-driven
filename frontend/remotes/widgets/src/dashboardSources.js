import axios from 'axios';
import { PERSONA_METRIC_IDS } from 'eip-shell-context/catalog';

/**
 * The widget board's data, fetched in ONE round-trip through web-bff-service's
 * GET /api/web/dashboard instead of one request per tile.
 *
 * Tiles share sources — briefing/latest alone feeds four of them — so the board
 * works out the distinct sources its visible tiles read, asks the BFF for exactly
 * those, and each tile then reads its source off the shared result.
 *
 * Kept in this remote rather than eip-shell-context on purpose: that package is a
 * federation singleton served by the SHELL, so a helper added there does not exist
 * for this remote until the shell is rebuilt too (see ONTOLOGY_ONLY_IDS in
 * WidgetBoard for the same constraint).
 *
 * The BFF is never load-bearing. If the aggregate request fails outright (BFF down
 * or not deployed), or one source comes back UNAVAILABLE, that tile issues its
 * original direct request. A FORBIDDEN/NOT_FOUND source is NOT retried — the BFF
 * forwarded the reader's own token, so a direct call would be refused the same
 * way — and is surfaced as the matching axios-style error instead, which keeps
 * the tiles' existing "not permitted" handling working unchanged.
 */

/**
 * Widgets with NO live feed that the ontology does observe. "Projects at Risk" has no
 * project-status endpoint, so the tile used to be hardcoded to "Not connected" while the
 * tenant profile held a real RAG-bearing observation for it. Kept in this remote rather
 * than only in catalog.js for the singleton reason above: a catalog edit alone would not
 * reach this board until the shell is rebuilt too.
 */
export const ONTOLOGY_ONLY_IDS = new Set(['rag-red']);

/** Fixed sources by BFF key: the direct route, and whether it prices money. */
const FIXED = {
  briefing:  { url: '/api/briefing/latest' },
  sla:       { url: '/api/priority/sla' },
  decisions: { url: '/api/decisions' },
  alerts:    { url: '/api/alerts' },
  hrSummary: { url: '/api/zenitus/hr/summary', money: true },
  cashflow:  { url: '/api/zenitus/cashflow/summary', money: true },
};

/** Which fixed source each bespoke tile reads. */
const TILE_SOURCE = {
  'uptime': 'sla', 'sla-health': 'sla',
  'budget': 'briefing', 'incidents': 'briefing',
  'system-health': 'briefing', 'budget-variance': 'briefing',
  'pending-decisions': 'decisions',
  'alerts': 'alerts',
  'headcount': 'hrSummary', 'headcount-dept': 'hrSummary', 'payroll': 'hrSummary',
  'net-cashflow': 'cashflow', 'cash-inflow': 'cashflow', 'cash-outflow': 'cashflow',
};

const currencyConfig = displayCurrency =>
  (displayCurrency ? { params: { displayCurrency } } : undefined);

const HTTP_STATUS = { FORBIDDEN: 403, NOT_FOUND: 404 };

/** Error shaped like an axios failure, so `e.response.status` checks keep working. */
function refusal(status) {
  const e = new Error(`Request failed with status code ${status}`);
  e.response = { status, data: null };
  return e;
}

/**
 * A loader with no aggregate behind it: every read is the tile's original direct
 * request. Used when the BFF call failed, and wherever a single fresh read is wanted.
 */
export function directSources(displayCurrency) {
  return {
    get: name => axios.get(FIXED[name].url, FIXED[name].money ? currencyConfig(displayCurrency) : undefined),
    persona: id => axios.get(`/api/dashboard/persona-metrics/${id}`, currencyConfig(displayCurrency)),
    metric: id => axios.get(`/api/dashboard/metrics/${id}`),
  };
}

/** Wraps an aggregate so each read resolves from it, falling back to `direct`. */
function aggregateSources(aggregate, direct) {
  const read = async (key, fallback) => {
    const status = aggregate.sources?.[key]?.status;
    if (status === 'OK') return { data: aggregate.data?.[key] ?? null, status: 200 };
    if (HTTP_STATUS[status]) throw refusal(HTTP_STATUS[status]);
    // UNAVAILABLE, or a key the aggregate does not carry: try the service itself.
    return fallback();
  };
  return {
    get: name => read(name, () => direct.get(name)),
    persona: id => read(`persona:${id}`, () => direct.persona(id)),
    metric: id => read(`metric:${id}`, () => direct.metric(id)),
  };
}

/** The BFF query for a set of widgets: distinct fixed sources plus per-widget ids. */
export function dashboardQuery(widgets, displayCurrency) {
  const sources = new Set();
  const persona = new Set();
  const metrics = new Set();
  for (const w of widgets) {
    // Same precedence as fetchMetric: dynamic, then ontology, then bespoke.
    if (w.dynamic) metrics.add(w.id);
    else if (PERSONA_METRIC_IDS.has(w.id) || ONTOLOGY_ONLY_IDS.has(w.id)) persona.add(w.id);
    else if (TILE_SOURCE[w.id]) sources.add(TILE_SOURCE[w.id]);
  }
  const params = {};
  if (sources.size) params.sources = [...sources].join(',');
  if (persona.size) params.persona = [...persona].join(',');
  if (metrics.size) params.metrics = [...metrics].join(',');
  if (displayCurrency) params.displayCurrency = displayCurrency;
  return params;
}

/**
 * Fetches everything the given widgets read in one BFF request.
 *
 * Always resolves — to an aggregate-backed loader on success, or to a direct
 * loader when the BFF could not be used — so the board never has to branch.
 */
export async function loadDashboardSources(widgets, displayCurrency) {
  const direct = directSources(displayCurrency);
  const params = dashboardQuery(widgets, displayCurrency);
  if (Object.keys(params).every(k => k === 'displayCurrency')) return direct;
  try {
    const { data } = await axios.get('/api/web/dashboard', { params });
    // An SPA catch-all serving index.html at 200 is "BFF absent", not "every source empty".
    if (!data || typeof data.sources !== 'object') return direct;
    return aggregateSources(data, direct);
  } catch {
    return direct;
  }
}
