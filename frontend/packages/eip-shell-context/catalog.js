// Shared widget catalog — single source of truth for both the runtime
// WidgetBoard and the admin "Dashboard Defaults" tab. Keep ids in sync with
// the server-side catalog in DashboardLayoutService.CATALOG; the backend
// rejects ids it does not recognise.
//
// `area`   — business area the widget belongs to; drives the grouped picker.
// `source` — the system/feed the metric is read from (shown under each widget).
// Both are display-only metadata: the backend and saved layouts only use `id`.

export const CATALOG = [
  { id: 'uptime',            label: 'System Uptime',      description: 'Overall system availability vs SLA target',     color: '#2F6FF0', area: 'IT Operations',        source: 'SLA Monitoring' },
  { id: 'budget',            label: 'IT Budget Consumed', description: 'Budget consumed vs plan this period',           color: '#FFB84D', area: 'Finance',              source: 'Budget Plan' },
  { id: 'incidents',         label: 'Active Incidents',   description: 'Open and in-progress incidents',                color: '#FF6B81', area: 'IT Operations',        source: 'Operations Briefing' },
  { id: 'rag-red',           label: 'Projects at Risk',   description: 'Projects flagged red in RAG status',            color: '#FF6B81', area: 'Projects & Governance', source: 'Project RAG' },
  { id: 'pending-decisions', label: 'Pending Decisions',  description: 'Decisions awaiting executive approval',         color: '#FFB84D', area: 'Projects & Governance', source: 'Decision Log' },
  { id: 'alerts',            label: 'Active Alerts',      description: 'Critical and high-severity platform alerts',    color: '#FF6B81', area: 'IT Operations',        source: 'Platform Alerts' },
  { id: 'sla-health',        label: 'SLA Health',         description: 'SLA compliance ratio across all systems',       color: '#3DDC97', area: 'IT Operations',        source: 'SLA Monitoring' },
  { id: 'system-health',     label: 'IT System Health',   description: 'Degraded or critical IT systems count',         color: '#8DBCFF', area: 'IT Operations',        source: 'Operations Briefing' },
  { id: 'budget-variance',   label: 'Budget Variance',    description: 'Cost centres running over or under budget',     color: '#2F6FF0', area: 'Finance',              source: 'Operations Briefing' },

  // ── HR + Finance widgets (e.g. Zenitus tenant). Backed by /api/zenitus/* ──
  { id: 'headcount',         label: 'Total Headcount',    description: 'Active employees across the organisation',      color: '#2F6FF0', area: 'People',  source: 'HR System' },
  { id: 'headcount-dept',    label: 'Headcount by Dept',  description: 'Employee distribution across departments',      color: '#8DBCFF', area: 'People',  source: 'HR System' },
  { id: 'payroll',           label: 'Annual Payroll',     description: 'Total annual CTC across all staff',             color: '#FFB84D', area: 'Finance', source: 'HR System' },
  { id: 'net-cashflow',      label: 'Net Cash Flow',      description: 'Net cash position for the current fiscal year', color: '#3DDC97', area: 'Finance', source: 'Finance System' },
  { id: 'cash-inflow',       label: 'Cash Inflow',        description: 'Total receipts for the current fiscal year',    color: '#2F6FF0', area: 'Finance', source: 'Finance System' },
  { id: 'cash-outflow',      label: 'Cash Outflow',       description: 'Total payments for the current fiscal year',    color: '#FF6B81', area: 'Finance', source: 'Finance System' },

  // ── Ontology-declared widgets ──
  // Values come from GovMetricObservation via /api/dashboard/persona-metrics/{id}
  // (see PERSONA_METRIC_IDS in WidgetBoard.jsx) instead of a bespoke feed per
  // widget, which is what allowed these to be added without new backend endpoints.
  // They exist to close the domain gaps that made the board IT-weighted: until now
  // nothing rendered Commercial, Contracts, Risk, Talent or Objective progress, so
  // even a CEO holding every data domain saw mostly IT tiles.
  { id: 'pipeline-value',     label: 'Pipeline Value',     description: 'Weighted open pipeline for the current quarter',        color: '#2F6FF0', area: 'Commercial',            source: 'Enterprise Ontology' },
  { id: 'revenue-arr',        label: 'Recurring Revenue',  description: 'Contracted ARR across active clients',                  color: '#3DDC97', area: 'Commercial',            source: 'Enterprise Ontology' },
  { id: 'risk-exposure',      label: 'Risk Exposure',      description: 'Total residual financial exposure on the risk register', color: '#FF6B81', area: 'Risk & Compliance',     source: 'Enterprise Ontology' },
  { id: 'attrition',          label: 'Attrition Rate',     description: 'Rolling attrition rate across the organisation',        color: '#FFB84D', area: 'People',                source: 'Enterprise Ontology' },
  { id: 'objective-progress', label: 'Objective Progress', description: 'Key results at or ahead of target',                     color: '#8DBCFF', area: 'Projects & Governance', source: 'Enterprise Ontology' },

];

/**
 * Widgets whose value is served by the ontology metric endpoint. Kept as a set
 * rather than a per-widget case so adding a widget stays a vocabulary change plus a
 * catalog line — no new fetch code.
 *
 * <p>Every id below has a {@code gov:Metric → RENDERED_BY → widgetKey} binding in
 * the tenant profile, so each one resolves a value WITH its unit, target, period and
 * RAG status. The finance and people tiles were previously served by bespoke
 * "Finance System" / "HR System" feeds that returned nothing — which is why Net Cash
 * Flow rendered a confident ₹0 and Annual Payroll rendered blank, while the same
 * figures sat in the ontology as CURRENCY observations with targets. Routing them
 * here fixes both without a new endpoint.</p>
 */
export const PERSONA_METRIC_IDS = new Set([
  // Commercial
  'pipeline-value', 'revenue-arr',
  // Finance — these are PERIODIC figures (a quarter's cash position, the annual
  // payroll run). The ontology is the right source: it carries the unit, the target
  // and the RAG that the bespoke feeds never supplied.
  'net-cashflow', 'cash-inflow', 'cash-outflow', 'payroll', 'budget-variance',
  // Risk, governance and talent — also periodic, and target-bearing.
  'risk-exposure', 'objective-progress', 'attrition',
  // No project-status feed exists; the ontology's RAG observation is the only source.
  'rag-red',
]);

// Deliberately NOT routed to the ontology: uptime, sla-health, incidents, alerts,
// system-health, pending-decisions, headcount, headcount-dept, budget.
// Each has a working live feed, and the ontology only holds a quarterly observation
// of it. "Decisions waiting on me" and "active incidents" have to be current — a
// figure from the start of the quarter would be worse than the number they show
// today, even though it would gain a unit and a target by moving.

// Platform default board — the LAST resort only.
//
// Do not put a role's board here. Layout resolution in widgets-service is four
// tiers: per-user override → tenant default → PERSONA → this. The persona tier
// (DashboardLayoutService.personaDefault) seeds a board from what the caller's
// persona `WATCHES_METRIC` in the tenant's own vocabulary, so CEO/CFO/CTO/COO/CHRO
// each already get a different board without any per-role list in the frontend.
//
// This list is reached only by personas that watch NOTHING — guest, viewer, admin
// — so it is deliberately neutral operational health, not any executive's board.
// It was briefly a CEO-shaped list, which is how a CFO ended up looking at a CEO
// dashboard: the CEO board arrived here as the system default, and the moment that
// user touched the widget picker it was persisted as their personal layout, which
// outranks the persona tier permanently.
export const DEFAULT_VISIBLE = ['uptime', 'incidents', 'sla-health', 'system-health'];

export const ALL_IDS = CATALOG.map(w => w.id);

// Fallback bucket for widgets without an explicit area (e.g. dynamic,
// data-onboarded widgets merged in at runtime).
export const DEFAULT_AREA = 'Onboarded Data';

// Stable display order for area section headers in the grouped picker. Any area
// not listed here (or a widget with no area) sorts to the end under DEFAULT_AREA.
export const AREA_ORDER = [
  'IT Operations',
  'Finance',
  'Commercial',
  'People',
  'Projects & Governance',
  'Risk & Compliance',
  DEFAULT_AREA,
];

/**
 * Group a widget list into ordered { area, widgets } sections, following
 * AREA_ORDER for the section order and preserving each widget's incoming order
 * within its section. Used by the widget picker and the admin defaults tab.
 */
export function groupByArea(widgets) {
  const byArea = new Map();
  for (const w of (widgets || [])) {
    const area = w.area || DEFAULT_AREA;
    if (!byArea.has(area)) byArea.set(area, []);
    byArea.get(area).push(w);
  }
  const rank = (a) => {
    const i = AREA_ORDER.indexOf(a);
    return i === -1 ? AREA_ORDER.length : i;
  };
  return [...byArea.entries()]
    .sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
    .map(([area, ws]) => ({ area, widgets: ws }));
}
