// Shared relevance rule for the executive feeds — Tasks for Action,
// Notifications and Decision Log.
//
// Why this exists: those three tabs each rendered every open row in the tenant
// (103 tasks, 100+ notifications, 100+ decisions). A count with no relevance
// filter is anxiety, not information — and it does not improve in production,
// where the same query returns thousands. Each feed now defaults to the rows
// that need the reader, and says explicitly how many it is hiding.
//
// IMPORTANT — what "needs me" can and cannot mean today: the task and
// notification payloads carry no assignee (the UI sees priority, criticality,
// status, source, dueDate; ownership exists only on the MONITORED MAILBOX, not
// on the individual item). So relevance is approximated by SEVERITY, not by
// assignment. Wiring a real per-item owner is a mail-insight-service change —
// until then this is deliberately an approximation, and the UI says "Needs
// attention" rather than "Assigned to me" so it never overstates what it knows.

/** Severity values treated as needing executive attention. */
export const ATTENTION_LEVELS = new Set(['CRITICAL', 'HIGH', 'URGENT', 'BLOCKER']);

/** Statuses that mean the item is finished and should never occupy the feed. */
export const CLOSED_STATUSES = new Set([
  'DONE', 'COMPLETED', 'CLOSED', 'RESOLVED', 'IGNORED', 'DISMISSED', 'ARCHIVED',
]);

/** How many rows a feed shows before collapsing behind "Show all". */
export const FEED_CAP = 10;

const up = v => (typeof v === 'string' ? v.trim().toUpperCase() : '');

/**
 * True when a row still needs someone's attention.
 *
 * @param {object} item        feed row
 * @param {string[]} [fields]  severity fields to test, most specific first
 */
export function needsAttention(item, fields = ['priority', 'criticality', 'severity']) {
  if (!item) return false;
  if (CLOSED_STATUSES.has(up(item.status))) return false;
  return fields.some(f => ATTENTION_LEVELS.has(up(item[f])));
}

/**
 * Apply the scope + cap to a feed.
 *
 * @returns {{rows: object[], shown: number, total: number, matched: number, hidden: number}}
 *   `total` is everything the API returned, `matched` what passed the scope,
 *   `rows` what to render. Callers surface `hidden` so the trim is never silent —
 *   a truncated list that does not admit it is worse than a long one.
 *   `include` marks extra rows as needing attention whatever their severity
 *   (e.g. a task awaiting the reader's approval).
 */
export function scopeFeed(items, { scope = 'attention', cap = FEED_CAP, fields, include } = {}) {
  const all = Array.isArray(items) ? items : [];
  const matched = scope === 'all' ? all
    : all.filter(i => (include && include(i)) || needsAttention(i, fields));
  const rows = cap > 0 ? matched.slice(0, cap) : matched;
  return {
    rows,
    shown: rows.length,
    matched: matched.length,
    total: all.length,
    hidden: matched.length - rows.length,
  };
}
