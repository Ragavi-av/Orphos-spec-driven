import { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import {
  Box, Paper, Typography, IconButton, Tooltip,
  Popover, Switch, CircularProgress, Button, ButtonBase,
} from '@mui/material';
import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import TuneIcon          from '@mui/icons-material/Tune';
import ArrowBackIcon     from '@mui/icons-material/ArrowBack';
import AutoAwesomeIcon   from '@mui/icons-material/AutoAwesome';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { CATALOG, DEFAULT_AREA, groupByArea, PERSONA_METRIC_IDS } from 'eip-shell-context/catalog';
import { formatMoney } from 'eip-shell-context/money';
import { useCountry } from 'eip-shell-context/country';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { useDashboardLayout, DASHBOARD_LAYOUT_CHANGED_EVENT } from 'eip-shell-context/DashboardLayoutProvider';
import useSectionPref from 'eip-shell-context/useSectionPref';
import SectionToggle from 'eip-shell-context/SectionToggle';
import { orphosAnim } from 'eip-shell-context/orphosTheme';
import { ONTOLOGY_ONLY_IDS, directSources, loadDashboardSources } from './dashboardSources';

// ─────────────────────────────────────────────────────────────────────────────
// Data fetchers for each widget — metric summary + detail rows
// ─────────────────────────────────────────────────────────────────────────────
const STATUS_COLOR = { CRITICAL:'#FF6B81', HIGH:'#FFB84D', MEDIUM:'#FFB84D',
                       BREACHED:'#FF6B81', DEGRADED:'#FF6B81', OPEN:'#FFB84D',
                       IN_PROGRESS:'#8DBCFF', ACTIVE:'#FF6B81',
                       APPROVED:'#3DDC97', PENDING:'#FFB84D', REJECTED:'#FF6B81',
                       MET:'#3DDC97', HEALTHY:'#3DDC97', POSITIVE:'#3DDC97', NEGATIVE:'#FF6B81' };

/**
 * A money amount awaiting formatting.
 *
 * Money is carried as {amount, currency} through fetch and state, and turned
 * into a string only at RENDER time — the same reason captions are carried as
 * catalog keys. fetchMetric runs outside React with no access to the active
 * language, and its result is cached in component state, so a string formatted
 * here would keep its crore/lakh scale after a switch to Japanese. The currency
 * comes from the observation (GovMetricObservation.currency); the locale decides
 * only the scale and grouping. See eip-shell-context/money.
 *
 * `fx` is the server's conversion provenance, present ONLY when the figure was
 * restated into the reader's currency — the reported amount, the rate, its
 * as-of date and where the rate came from. Conversion happens in
 * widgets-service, not here: picking between a period-average and a closing
 * rate needs the metric's period and its flow/balance classification, neither
 * of which survives the trip to the browser. All this end does is show the
 * result honestly, which means never rendering a converted figure without the
 * marker and the tooltip that explain it.
 */
const money = (amount, currency, fx = null) => ({ amount, currency, fx });

/**
 * Rows out of a list response, whatever shape the service chose.
 *
 * The platform is not consistent: priority-engine and mail-insight return
 * `{ result: [...] }`, while decision-log's GET /api/decisions returns a BARE
 * ARRAY. Every caller here assumed the wrapped form, so `data.result` was
 * undefined for decisions and the Pending Decisions tile reported 0 no matter
 * what was in the database — it read 0 with 217 pending rows just as happily as
 * with 2. Reading both shapes here stops that failing silently again.
 */
function rowsOf(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.result)) return data.result;
  if (Array.isArray(data?.content)) return data.content;   // Spring Page
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

/**
 * Resolve a metric's caption at render time, in the ACTIVE language.
 *
 * Three shapes are supported, in precedence order:
 *   subtitleParts — [{key, params} | {text}], joined with ' · '. Used by the
 *                   ontology branch, whose caption is several clauses long.
 *   subtitleKey   — a single catalog key + params. Used by the bespoke widgets.
 *   subtitle      — free text from the server (dynamic/onboarded widgets), which
 *                   the frontend has no key for and must pass through verbatim.
 *
 * Keeping resolution here rather than in fetchMetric is what lets a language
 * switch re-render captions off the CACHED metric, with no refetch.
 */
/** True for a deferred money amount produced by `money()`. */
const isMoneyValue = v => v !== null && typeof v === 'object' && 'amount' in v && 'currency' in v;

/** Format a value that may be a deferred money amount, in the active locale. */
function resolveValue(v, lng) {
  return isMoneyValue(v) ? formatMoney(v.amount, v.currency, lng) : v;
}

/**
 * The provenance lines for a converted figure, or null.
 *
 * Three facts, because a reader challenging a number will ask for exactly
 * these: what the source says, what rate was applied and as of when, and who
 * says so. The reported amount is formatted through the same formatMoney the
 * tile uses, so the two figures in the tooltip and on the card are written in
 * one style and can be compared at a glance.
 */
/**
 * The currency an amount is NOW in.
 *
 * The zenitus endpoints report a single top-level `currency` for the whole
 * response, but conversion is per amount — a rate can cover one figure's period
 * and not another's. So a converted amount's unit comes from its own `fx`
 * block, and only an unconverted one falls back to the response-level value.
 */
const fxCurrency = (fx, fallback) => fx?.displayCurrency || fallback;

function fxLines(t, fx, toCurrency, lng) {
  if (!fx) return null;
  return [
    t('widgets.fx.reported', 'Reported {{amount}}', {
      amount: formatMoney(fx.reportedValue, fx.reportedCurrency, lng),
    }),
    t('widgets.fx.rate', '{{rate}} {{from}}→{{to}} · {{type}} rate, {{date}}', {
      rate: fx.rate,
      from: fx.reportedCurrency,
      to: toCurrency,
      type: t(`widgets.fx.rateType.${fx.rateType}`, fx.rateType),
      date: fx.rateAsOf,
    }),
    fx.rateSource ? t('widgets.fx.source', 'Source: {{source}}', { source: fx.rateSource }) : null,
  ].filter(Boolean);
}

/** Interpolation params with any deferred money amounts formatted for `lng`. */
function resolveParams(params, lng) {
  if (!params) return params;
  let out = null;
  for (const [k, v] of Object.entries(params)) {
    if (isMoneyValue(v)) (out = out || { ...params })[k] = formatMoney(v.amount, v.currency, lng);
  }
  return out || params;
}

function resolveSubtitle(t, m, lng) {
  if (Array.isArray(m?.subtitleParts)) {
    return m.subtitleParts
      .map(p => (p.key ? t(p.key, resolveParams(p.params, lng)) : p.text))
      .filter(Boolean)
      .join(' · ');
  }
  if (m?.subtitleKey) return t(m.subtitleKey, resolveParams(m.subtitleParams, lng));
  return m?.subtitle;
}

/** True when the service refused the caller's persona (403), as opposed to failing. */
const isRefused = (e) => e?.response?.status === 403 || e?.response?.data?.permitted === false;

/**
 * @param displayCurrency ISO code the reader wants money in — their country
 *   pick, from eip-shell-context/country. Advisory: the service converts only
 *   where its rate table covers the observation's period, and otherwise returns
 *   the reported figure unchanged. A blank value asks for no conversion at all,
 *   which is what a reader who has picked no country gets.
 * @param src where the tile's source data comes from — the board's shared
 *   web-bff aggregate (see ./dashboardSources), or direct requests by default.
 */
async function fetchMetric(widget, displayCurrency, src = directSources(displayCurrency)) {
  // Dynamic (data-onboarded) widgets resolve their value from the generic
  // tenant-metrics endpoint rather than the hardcoded cases below.
  // Captions come back as catalog keys (subtitleKey/valueKey + params) and are
  // resolved with t() at render time, so they flip language without a refetch.
  // Dynamic (data-onboarded) widgets return free-text server subtitles — those
  // stay verbatim via the plain `subtitle` field.
  if (widget && widget.dynamic) {
    try {
      const { data } = await src.metric(widget.id);
      return { value: data?.value ?? '—', subtitle: data?.subtitle || '', trend: 'up', trendColor: widget.color };
    } catch {
      return { value: '—', subtitleKey: 'widgets.captions.dataUnavailable', trend: 'up' };
    }
  }
  const id = widget && widget.id ? widget.id : widget;

  // Ontology-declared widgets: one branch for all of them, so a new widget needs a
  // vocabulary entry and a catalog line but no new fetch code. The response carries
  // permitted=false when the caller's persona may not see the number — distinct from
  // "no data", and worded differently so a hidden figure never looks like an outage.
  if (PERSONA_METRIC_IDS.has(id) || ONTOLOGY_ONLY_IDS.has(id)) {
    try {
      const { data } = await src.persona(id);
      if (data && data.permitted === false) {
        return { value: '—', subtitleKey: 'widgets.captions.notPermitted', trend: 'up', trendColor: '#C3D3F2' };
      }
      // ── Rule: honest empty states. A tile with no observation says so; it never
      // renders 0. A confident ₹0 on an enterprise cash tile discredits every other
      // number on the board, which is exactly how the old Finance feed failed.
      if (!data || data.available === false || data.value === null || data.value === undefined) {
        return { value: '—', subtitleKey: 'widgets.captions.noDataPeriod', trend: 'up', empty: true };
      }
      const unit = data.unit || '';
      // CURRENCY values stay unformatted until render (see `money` above); the
      // observation's own currency travels with them, so an INR tenant and a JPY
      // tenant each render in their real unit instead of a hardcoded ₹.
      const isMoney = unit === 'CURRENCY';
      // data.currency is what the figure is NOW in — the display currency when the
      // service converted, the reported one when it could not. data.fx carries the
      // reported amount and the rate in the converted case, and is absent in the
      // other, which is exactly the signal the tile needs to decide on the marker.
      const fmt = v => isMoney ? money(v, data.currency, data.fx || null)
                     : unit === 'PERCENT' ? `${Number(v).toFixed(1)}%`
                     : String(v);
      const value = fmt(data.value);

      // ── Rule: one number, one comparison, one trend. Never a naked figure. ──
      // The comparison is attainment against the declared target; a tile with no
      // target says so rather than implying it is on plan.
      //
      // The caption is emitted as a LIST OF CATALOG KEYS, not as a finished
      // sentence. Building the English here is what left "89% of target" and
      // "of period elapsed" in English on a Japanese board: fetchMetric runs
      // outside React with no `t`, and the result is cached in state, so any
      // string baked in at this point survives a language switch untranslated.
      // The render resolves each part with t() and joins them — same contract
      // the subtitleKey widgets below already use.
      const n = Number(data.value);
      const target = (data.targetValue === null || data.targetValue === undefined)
        ? null : Number(data.targetValue);
      const parts = [];
      if (target !== null && isFinite(target) && target !== 0 && isFinite(n)) {
        parts.push({ key: 'widgets.captions.pctOfTarget',
                     params: { pct: Math.round((n / target) * 100), target: fmt(target) } });
      } else if (target !== null && isFinite(target)) {
        parts.push({ key: 'widgets.captions.targetOnly', params: { target: fmt(target) } });
      } else {
        parts.push({ key: 'widgets.captions.noTarget' });
      }
      // Period label is tenant data ("FY2027 Q2"), not UI copy — passed through
      // verbatim rather than forced through a key that could not cover every
      // fiscal calendar a tenant might declare.
      if (data.period) parts.push({ text: data.period });

      // ── Pace: progress against time elapsed in the period. 33% done at 50%
      // elapsed is behind, and must read as behind without the reader doing the
      // arithmetic. Only meaningful for PERCENT metrics that carry period bounds.
      let pace = null;
      if (unit === 'PERCENT' && data.periodStart && data.periodEnd && isFinite(n)) {
        const start = Date.parse(data.periodStart), end = Date.parse(data.periodEnd);
        if (isFinite(start) && isFinite(end) && end > start) {
          const elapsed = Math.min(100, Math.max(0,
            ((Date.now() - start) / (end - start)) * 100));
          pace = { elapsed, behind: n < elapsed - 5 };
          parts.push({ key: 'widgets.captions.periodElapsed',
                       params: { pct: Math.round(elapsed) } });
        }
      }

      // ── Rule: colour is never decorative. RAG from the source is authoritative;
      // pace can only escalate to amber, never paint something green. With neither,
      // the value stays neutral rather than borrowing the catalog's accent — that
      // accent is what rendered "378 cost centres over budget" in a calm blue.
      let statusColor = STATUS_COLOR[data.ragStatus] || null;
      if (!statusColor && pace?.behind) statusColor = '#FFB84D';

      // Raw comparison numbers travel alongside the prose caption. The board
      // sorts on `attainmentPct` and renders the compact "target X · N%" foot
      // from these, so neither job has to re-parse a translated sentence.
      const hasTarget = target !== null && isFinite(target);
      return {
        value,
        subtitleParts: parts,
        trend: pace?.behind ? 'down' : 'up',
        trendColor: statusColor,
        statusColor,
        asOf: data.period || null,
        hasTarget,
        targetIsZero: hasTarget && target === 0,
        targetLabel: hasTarget ? fmt(target) : null,
        attainmentPct: (hasTarget && target !== 0 && isFinite(n)) ? (n / target) * 100 : null,
        periodLabel: data.period || null,
      };
    } catch {
      // Distinct from "no data for this period": the request itself failed, so the
      // source is unreachable rather than merely unobserved. Worded so a reader can
      // tell an outage from an empty quarter.
      return { value: '—', subtitleKey: 'widgets.captions.notConnected', trend: 'up', empty: true };
    }
  }
  try {
    switch (id) {
      case 'uptime': {
        const { data } = await src.get('sla');
        const rows = rowsOf(data);
        const avail = rows.find(r => r.metricName === 'Availability' || r.metricName === 'Uptime');
        // No availability row → an honest empty tile, never a plausible-looking constant.
        if (!avail) return { value: '—', subtitleKey: 'widgets.captions.noDataPeriod', trend: 'up', empty: true };
        // The caption is the measured gap to this row's own target, never a fixed figure.
        const actual = Number(avail.actual), target = Number(avail.target);
        const hasTarget = avail.target != null && isFinite(target);
        const met = avail.status ? avail.status === 'MET' : (hasTarget && actual >= target);
        if (!hasTarget) {
          return { value: `${avail.actual}%`, subtitleKey: met ? 'widgets.captions.allSlasMet' : 'widgets.captions.breachedCount', subtitleParams: { n: 1 }, trend: met ? 'up' : 'down' };
        }
        const gap = actual - target;
        const pp = `${gap >= 0 ? '+' : ''}${gap.toFixed(1)}`;
        return { value: `${avail.actual}%`, subtitleKey: 'widgets.captions.vsSlaTarget', subtitleParams: { pp, target }, trend: met ? 'up' : 'down', trendColor: met ? '#3DDC97' : '#FF6B81' };
      }
      case 'budget': {
        // Spent against budget across the same cost centres the drill-down lists
        // (and the mobile Financials "capital" tile sums) — not a fixed figure.
        const { data } = await src.get('briefing');
        const rows = data?.budgetOverruns?.result || [];
        const budget = rows.reduce((s, r) => s + (Number(r.budget) || 0), 0);
        const spent = rows.reduce((s, r) => s + (Number(r.spent) || 0), 0);
        if (budget <= 0) return { value: '—', subtitleKey: 'widgets.captions.noDataPeriod', trend: 'up', empty: true };
        return { value: `${Math.round((spent / budget) * 100)}%`, subtitleKey: 'widgets.captions.spentOfBudget', subtitleParams: { n: rows.length }, trend: spent > budget ? 'down' : 'up', trendColor: spent > budget ? '#FF6B81' : '#FFB84D' };
      }
      case 'incidents': {
        const { data } = await src.get('briefing');
        const n = data?.activeIncidents?.result?.length ?? 0;
        return { value: String(n), subtitleKey: n > 0 ? 'widgets.captions.needAttention' : 'widgets.captions.noOpenIncidents', subtitleParams: { n }, trend: n > 0 ? 'down' : 'up', trendColor: n > 0 ? '#FF6B81' : '#3DDC97' };
      }
      case 'pending-decisions': {
        const { data } = await src.get('decisions');
        const pending = rowsOf(data).filter(d => d.status === 'PENDING');
        return { value: String(pending.length), subtitleKey: pending.length > 0 ? 'widgets.captions.awaitingApproval' : 'widgets.captions.allDecided', trend: pending.length > 0 ? 'down' : 'up', trendColor: pending.length > 0 ? '#FFB84D' : '#3DDC97' };
      }
      case 'alerts': {
        const { data } = await src.get('alerts');
        const rows = rowsOf(data);
        const crit = rows.filter(a => a.severity === 'CRITICAL').length;
        return { value: String(rows.length), subtitleKey: crit > 0 ? 'widgets.captions.criticalCount' : 'widgets.captions.noCriticalAlerts', subtitleParams: { n: crit }, trend: rows.length > 0 ? 'down' : 'up', trendColor: rows.length > 0 ? '#FF6B81' : '#3DDC97' };
      }
      case 'sla-health': {
        const { data } = await src.get('sla');
        const rows = rowsOf(data);
        const met = rows.filter(r => r.status === 'MET').length;
        return { value: `${met}/${rows.length}`, subtitleKey: rows.length - met > 0 ? 'widgets.captions.breachedCount' : 'widgets.captions.allSlasMet', subtitleParams: { n: rows.length - met }, trend: met === rows.length ? 'up' : 'down', trendColor: met === rows.length ? '#3DDC97' : '#FF6B81' };
      }
      case 'system-health': {
        const { data } = await src.get('briefing');
        const systems = data?.activeIncidents?.result || [];
        return { valueKey: systems.length > 0 ? 'widgets.captions.affectedCount' : 'widgets.captions.allHealthy', valueParams: { n: systems.length }, subtitleKey: 'widgets.captions.itSystemsStatus', trend: systems.length > 0 ? 'down' : 'up', trendColor: systems.length > 0 ? '#FF6B81' : '#3DDC97' };
      }
      case 'budget-variance': {
        const { data } = await src.get('briefing');
        const overruns = data?.budgetOverruns?.result || [];
        return { value: String(overruns.length), subtitleKey: overruns.length > 0 ? 'widgets.captions.costCentresOver' : 'widgets.captions.withinBudget', trend: overruns.length > 0 ? 'down' : 'up', trendColor: overruns.length > 0 ? '#FF6B81' : '#3DDC97' };
      }
      case 'headcount': {
        const { data } = await src.get('hrSummary');
        const depts = data?.byDepartment?.length ?? 0;
        // A missing figure is an empty tile, not a confident "0".
        if (data?.headcount == null) return { value: '—', subtitleKey: 'widgets.captions.noDataPeriod', trend: 'up', empty: true };
        return { value: String(data.headcount), subtitleKey: 'widgets.captions.departments', subtitleParams: { n: depts }, trend: 'up', trendColor: '#3DDC97' };
      }
      case 'headcount-dept': {
        const { data } = await src.get('hrSummary');
        const top = (data?.byDepartment || [])[0];
        return { value: String(data?.byDepartment?.length ?? 0), subtitleKey: top ? 'widgets.captions.deptLeads' : 'widgets.captions.noData', subtitleParams: top ? { label: top.label, n: top.count } : undefined, trend: 'up', trendColor: '#8DBCFF' };
      }
      case 'payroll': {
        const { data } = await src.get('hrSummary');
        // `<key>Fx` sits BESIDE the amount rather than wrapping it — see the
        // putMoney note in ZenitusMetricsController for why the shape is flat.
        return { value: money(data?.payrollTotal, fxCurrency(data?.payrollTotalFx, data?.currency), data?.payrollTotalFx), subtitleKey: 'widgets.captions.annualCtc', trend: 'up', trendColor: '#FFB84D' };
      }
      case 'net-cashflow': {
        const { data } = await src.get('cashflow');
        if (data?.ytd?.nett == null) return { value: '—', subtitleKey: 'widgets.captions.noDataPeriod', trend: 'up', empty: true };
        const nett = data.ytd.nett;
        return { value: money(nett, fxCurrency(data?.ytd?.nettFx, data?.currency), data?.ytd?.nettFx), subtitleKey: 'widgets.captions.netPosition', subtitleParams: { fy: data?.fiscalYear || 'YTD' }, trend: nett >= 0 ? 'up' : 'down', trendColor: nett >= 0 ? '#3DDC97' : '#FF6B81' };
      }
      case 'cash-inflow': {
        const { data } = await src.get('cashflow');
        return { value: money(data?.ytd?.inflow, fxCurrency(data?.ytd?.inflowFx, data?.currency), data?.ytd?.inflowFx), subtitleKey: 'widgets.captions.receipts', subtitleParams: { fy: data?.fiscalYear || 'YTD' }, trend: 'up', trendColor: '#3DDC97' };
      }
      case 'cash-outflow': {
        const { data } = await src.get('cashflow');
        return { value: money(data?.ytd?.outflow, fxCurrency(data?.ytd?.outflowFx, data?.currency), data?.ytd?.outflowFx), subtitleKey: 'widgets.captions.payments', subtitleParams: { fy: data?.fiscalYear || 'YTD' }, trend: 'down', trendColor: '#FF6B81' };
      }
      default:
        return { value: '—', subtitle: '', trend: 'up' };
    }
  } catch (e) {
    // A persona refusal (e.g. HR figures for a role without the HR data domain) is not an
    // outage; say "not available for your role" so it cannot be mistaken for one.
    if (isRefused(e)) {
      return { value: '—', subtitleKey: 'widgets.captions.notPermitted', trend: 'up', trendColor: '#C3D3F2' };
    }
    return { value: '—', subtitleKey: 'widgets.captions.dataUnavailable', trend: 'up' };
  }
}

async function fetchDetail(widget) {
  if (widget && widget.dynamic) {
    try {
      const { data } = await axios.get(`/api/dashboard/metrics/${widget.id}`);
      return (data?.rows || []).map(r => ({
        primary: String(r.label),
        secondary: `${r.value}`,
        ask: `Give me an executive view of "${widget.label}": ${r.label} = ${r.value}.`,
      }));
    } catch {
      return [];
    }
  }
  const id = widget && widget.id ? widget.id : widget;
  try {
    switch (id) {
      case 'uptime':
      case 'sla-health': {
        const { data } = await axios.get('/api/priority/sla');
        return rowsOf(data).map(r => ({
          primary: `${r.metricName} — ${r.system || 'unknown system'}`,
          secondary: `Actual: ${r.actual} ${r.unit}  ·  Target: ${r.target} ${r.unit}`,
          status: r.status,
          ask: `Give me the executive analysis for the SLA metric "${r.metricName}" on system ${r.system}. Actual: ${r.actual}${r.unit}, target: ${r.target}${r.unit}, status: ${r.status}.`,
        }));
      }
      case 'incidents':
      case 'system-health': {
        const { data } = await axios.get('/api/briefing/latest');
        return (data?.activeIncidents?.result || []).map(r => ({
          primary: r.title || r.id,
          secondary: `${r.severity || ''} · ${r.domain || ''} · ${r.affectedSystem || ''}`.replace(/^ · | · $/g, ''),
          status: r.severity,
          ask: `Provide an executive briefing on this incident: "${r.title}". Severity: ${r.severity}, status: ${r.status}, domain: ${r.domain}.`,
        }));
      }
      case 'budget':
      case 'budget-variance': {
        const { data } = await axios.get('/api/briefing/latest');
        const items = [...(data?.budgetOverruns?.result || [])];
        return items.map(r => ({
          primary: r.name || r.id,
          secondary: `Budget: ${r.budget} ${r.currency}  ·  Spent: ${r.spent} ${r.currency}  ·  Variance: ${r.variance} ${r.currency}`,
          status: (r.variance < 0 || (typeof r.variance === 'string' && r.variance.startsWith('-'))) ? 'OVER' : 'UNDER',
          ask: `Analyse the budget status for cost centre "${r.name}": budget ${r.budget} ${r.currency}, spent ${r.spent} ${r.currency}, variance ${r.variance} ${r.currency}.`,
        }));
      }
      case 'pending-decisions': {
        const { data } = await axios.get('/api/decisions');
        return rowsOf(data).filter(d => d.status === 'PENDING').map(r => ({
          primary: r.title,
          secondary: `Domain: ${r.domain || '—'}  ·  Decided by: ${r.decidedBy || 'Unassigned'}`,
          status: 'PENDING',
          ask: `Summarise this pending decision and its implications: "${r.title}". Domain: ${r.domain}. Rationale: ${r.rationale || 'not provided'}.`,
        }));
      }
      case 'alerts': {
        const { data } = await axios.get('/api/alerts');
        return rowsOf(data).map(r => ({
          primary: r.title,
          secondary: r.message || '',
          status: r.severity,
          ask: `Explain this enterprise alert and its business impact: "${r.title}". Severity: ${r.severity}, domain: ${r.domain}. Message: ${r.message}.`,
        }));
      }
      case 'rag-red':
        return [];   // no project-status source yet — see the tile above
      case 'headcount':
      case 'headcount-dept':
      case 'payroll': {
        const { data } = await axios.get('/api/zenitus/hr/summary');
        return (data?.byDepartment || []).map(d => ({
          primary: d.label,
          secondary: `${d.count} employee${d.count === 1 ? '' : 's'}`,
          status: 'ACTIVE',
          ask: `Give me an executive view of the "${d.label}" department at this organisation: it has ${d.count} employees. How does it compare to the rest of the headcount?`,
        }));
      }
      case 'net-cashflow':
      case 'cash-inflow':
      case 'cash-outflow': {
        const { data } = await axios.get('/api/zenitus/cashflow/summary');
        return (data?.months || []).map(m => ({
          primary: `${m.monthName} · ${m.fiscalYear}`,
          // Deferred like the tile captions: the labels resolve through t() and
          // the amounts through formatMoney, both at render, so this row follows
          // a language switch instead of freezing English crore/lakh into state.
          secondaryParts: [
            { key: 'widgets.detail.inflow',  params: { v: money(m.inflow,  data?.currency) } },
            { key: 'widgets.detail.outflow', params: { v: money(m.outflow, data?.currency) } },
            { key: 'widgets.detail.net',     params: { v: money(m.nett,    data?.currency) } },
          ],
          status: Number(m.nett) >= 0 ? 'POSITIVE' : 'NEGATIVE',
          ask: `Analyse the cash flow for ${m.monthName} ${m.fiscalYear}: inflow ${m.inflow}, outflow ${m.outflow}, net ${m.nett}. Is this a healthy month and what should leadership note?`,
        }));
      }
      default:
        return [];
    }
  } catch {
    return [];
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

function StatusBadge({ status }) {
  const color = STATUS_COLOR[status] || '#C3D3F2';
  return (
    <Box component="span" sx={{
      display: 'inline-block', px: 0.8, py: 0.1,
      borderRadius: 1, fontSize: 10, fontWeight: 700,
      // Alphas raised from 18/33: a 9% tint that read on white is invisible on navy.
      color, bgcolor: color + '2e', border: `1px solid ${color}6b`,
    }}>
      {status}
    </Box>
  );
}

function DetailPanel({ widget, onBack, onAsk }) {
  const { t, i18n } = useTranslation();
  const label = widget ? t(`widgets.labels.${widget.id}`, widget.label) : widget?.label;
  const [rows, setRows]     = useState([]);
  const [loading, setLoading] = useState(true);
  const [hovered, setHovered] = useState(null);

  useEffect(() => {
    setLoading(true);
    fetchDetail(widget).then(r => { setRows(r); setLoading(false); });
  }, [widget]);

  return (
    // The drill-down runs one step deeper than the tiles it opened from — the
    // design's nested fill with the stronger outline, so it reads as a layer on
    // top of the board rather than as another card beside it.
    <Paper elevation={0} sx={{
      bgcolor: '#0F2450', border: '1px solid #2E4A8C', borderRadius: '13px',
      px: '17px', py: '15px',
    }}>
      {/* Header — the widget's own name, said as a drill-down of it */}
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.75 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography fontSize={10.5} fontWeight={600}
            sx={{ color: '#7D92C4', textTransform: 'uppercase', letterSpacing: 0.9 }}>
            {t('widgets.board.detailOf', '{{label}} · detail', { label })}
          </Typography>
          <Typography fontSize={11.5} sx={{ color: '#4D6299', mt: '5px' }}>
            {t('widgets.board.askAi', 'Click to ask AI co-pilot')}
          </Typography>
        </Box>
        <Tooltip title={t('widgets.board.backToWidgets', 'Back to widgets')}>
          <IconButton size="small" onClick={onBack}
            aria-label={t('widgets.board.backToWidgets', 'Back to widgets')}
            sx={{ color: '#7D92C4', '&:hover': { color: '#FFFFFF', bgcolor: 'rgba(91, 157, 255, 0.14)' } }}>
            <ArrowBackIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {loading ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 2 }}>
          <CircularProgress size={16} /><Typography fontSize={12} color="#7D92C4">{t('widgets.board.loading', 'Loading…')}</Typography>
        </Box>
      ) : rows.length === 0 ? (
        <Typography fontSize={12} color="#7D92C4" sx={{ py: 2 }}>{t('widgets.board.noData', 'No data available.')}</Typography>
      ) : (
        <Box sx={{ mt: 1.75 }}>
          <Typography fontSize={10.5} fontWeight={600}
            sx={{ color: '#7D92C4', textTransform: 'uppercase', letterSpacing: 0.8, mb: '2px' }}>
            {t('widgets.board.breakdown', 'What it breaks down into')}
          </Typography>
          {/* A list, not a card grid: the design reads a breakdown as rows under
              one heading, which keeps every line on the same left edge however
              long its label is. */}
          {rows.map((row, i) => (
            <Box
              key={i}
              component={onAsk ? 'button' : 'div'}
              type={onAsk ? 'button' : undefined}
              onMouseEnter={() => setHovered(i)}
              onMouseLeave={() => setHovered(null)}
              onClick={() => onAsk && onAsk({ label, query: row.ask })}
              title={onAsk ? t('widgets.board.askAi', 'Click to ask AI co-pilot') : undefined}
              sx={{
                display: 'flex', alignItems: 'center', gap: 1.5, width: '100%',
                textAlign: 'left', font: 'inherit', background: 'none',
                px: 0.75, py: '10px', borderRadius: '8px',
                border: 'none', borderBottom: '1px solid #1C2F5F',
                cursor: onAsk ? 'pointer' : 'default',
                '&:hover': onAsk ? { bgcolor: 'rgba(91, 157, 255, 0.10)' } : {},
              }}
            >
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography fontSize={12.8}
                  sx={{ color: hovered === i && onAsk ? '#8DBCFF' : '#EAF0FF' }}>
                  {row.primary}
                </Typography>
                {(row.secondaryParts || row.secondary) && (
                  <Typography fontSize={11} color="#4D6299" mt="2px">
                    {resolveSubtitle(t, { subtitleParts: row.secondaryParts,
                                          subtitle: row.secondary }, i18n.language)}
                  </Typography>
                )}
              </Box>
              {row.status && <StatusBadge status={row.status} />}
              {onAsk && (
                <AutoAwesomeIcon sx={{ fontSize: 13, flexShrink: 0,
                  color: hovered === i ? '#8DBCFF' : '#4D6299', transition: 'color 0.15s' }} />
              )}
            </Box>
          ))}
        </Box>
      )}
    </Paper>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main WidgetBoard
// ─────────────────────────────────────────────────────────────────────────────
export default function WidgetBoard({ onAsk, layout }) {
  const { t, i18n } = useTranslation();
  // The workspace's reporting currency, from the tenant's primary country
  // (Admin → Branding). Empty when no country is configured, which asks the
  // service for no conversion — the board then shows every figure in the
  // currency its data declares, as it always did. Deliberately NOT tied to the
  // header language pill: a reader switching to Japanese is reading the same
  // figures, not asking for them to be re-denominated.
  const { currency: displayCurrency } = useCountry();
  // Layout (visibleIds + order) is owned by DashboardLayoutProvider — backend
  // persisted, scoped per (tenantId, userId). The provider also debounces the
  // PUT so rapid drag-reorders coalesce into a single request.
  //
  // Prefer the layout the shell passes via the `context` prop: this remote
  // bundles its own copy of eip-shell-context under Module Federation, so the
  // local useDashboardLayout() hook is NOT connected to the host's
  // <DashboardLayoutProvider> — it returns the default value whose `save` is a
  // no-op (which is why toggling/reordering did nothing). The hook stays as a
  // fallback for older shells that don't pass the layout down.
  const hookLayout = useDashboardLayout();
  const { visibleIds, order, source, save, resetToTenantDefault } = layout || hookLayout;

  // Dynamic (data-onboarded) widgets for this tenant, merged with the static
  // catalog. Empty for tenants that haven't onboarded data.
  const [dynamicWidgets, setDynamicWidgets] = useState([]);
  const loadDynamicWidgets = useCallback(() => {
    axios.get('/api/dashboard/widgets')
      .then(res => setDynamicWidgets(Array.isArray(res.data)
        ? res.data.map(w => ({ id: w.id, label: w.label,
            description: (w.type || '').toLowerCase(), color: w.color || '#8DBCFF', dynamic: true,
            // Data-onboarded widgets have no static area; bucket them together
            // and surface their onboarded type as the data source.
            area: DEFAULT_AREA, source: w.type || 'Onboarded data' }))
        : []))
      .catch(() => setDynamicWidgets([]));
  }, []);
  // Refetch on mount AND whenever an admin adds/edits/deletes a widget (the
  // manage-widgets screen fires the same layout-changed event), so the board
  // reflects widget changes without a full page reload.
  useEffect(() => {
    loadDynamicWidgets();
    window.addEventListener(DASHBOARD_LAYOUT_CHANGED_EVENT, loadDynamicWidgets);
    return () => window.removeEventListener(DASHBOARD_LAYOUT_CHANGED_EVENT, loadDynamicWidgets);
  }, [loadDynamicWidgets]);
  const effectiveCatalog = useMemo(() => {
    const ids = new Set(CATALOG.map(w => w.id));
    return [...CATALOG, ...dynamicWidgets.filter(w => !ids.has(w.id))];
  }, [dynamicWidgets]);

  // Metric values per widget
  const [metrics, setMetrics] = useState({});
  // Detail view
  const [detailId, setDetailId] = useState(null);

  // Widget picker popover
  const [pickerAnchor, setPickerAnchor] = useState(null);

  // Watchlist ordering. Fixed ON: the board's job is to surface what is off
  // plan, and a hand-arranged order buries a red tile behind three green ones.
  // The heading strip used to carry a control that flipped this to the saved
  // manual order; that strip was removed, so the sort is no longer switchable
  // from the UI and the drag grips (armed only in manual mode) stay hidden.
  // The saved manual order is untouched — restoring a toggle brings it back.
  const autoOrder = true;

  // Drag state
  const dragId    = useRef(null);
  const [dragging, setDragging] = useState(false);   // source card fades
  const [dragOver, setDragOver] = useState(null);    // target card highlights
  const [armedId,  setArmedId]  = useState(null);    // card whose grip is held

  // ── Visible widgets in current order ──────────────────────────────────────
  const visibleWidgets = order
    .filter(id => visibleIds.includes(id))
    .map(id => effectiveCatalog.find(w => w.id === id))
    .filter(Boolean);

  // ── Worst first ───────────────────────────────────────────────────────────
  // Distance from target = |attainment - 100|, so a 146% overrun (46) and a
  // 54% shortfall (46) rank equally: both are the same distance off plan, which
  // is what "worst" means here. Tiles whose target is unknown or zero cannot
  // produce a distance and sort last rather than being guessed at — sort() is
  // stable, so among themselves they keep the user's own order.
  const orderedWidgets = useMemo(() => {
    if (!autoOrder) return visibleWidgets;
    const distance = (w) => {
      const pct = metrics[w.id]?.attainmentPct;
      return (typeof pct === 'number' && isFinite(pct)) ? Math.abs(pct - 100) : -1;
    };
    return [...visibleWidgets].sort((a, b) => distance(b) - distance(a));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoOrder, metrics, visibleIds, order, dynamicWidgets]);

  // ── Fetch metrics for visible widgets ─────────────────────────────────────
  // Refetches when the reader's currency changes, because conversion happens
  // server-side: a language switch alone re-renders off the cached metric (that
  // is the whole point of deferring formatting), but a CURRENCY switch needs new
  // numbers and a new rate, which only the service can price.
  //
  // One web-bff request covers every visible tile; each tile then reads its
  // source off that shared result (or falls back to its own request when the
  // BFF could not serve it).
  useEffect(() => {
    let cancelled = false;
    loadDashboardSources(visibleWidgets, displayCurrency).then(src => {
      if (cancelled) return;
      visibleWidgets.forEach(w =>
        fetchMetric(w, displayCurrency, src).then(m => {
          if (!cancelled) setMetrics(prev => ({ ...prev, [w.id]: m }));
        }));
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleIds, order, dynamicWidgets, displayCurrency]);

  // ── Drag-and-drop handlers ─────────────────────────────────────────────────
  // Strategy: reorder immediately on dragEnter (fires once per target, reliable
  // even when child elements are under the cursor). onDragOver only needs to
  // call preventDefault() to keep the "move" cursor active.

  const handleDragStart = (e, id) => {
    dragId.current = id;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', id);   // required for Firefox
    // Tiny delay so React can render the faded state after drag ghost is captured
    setTimeout(() => setDragging(true), 0);
  };

  const handleDragEnter = (e, targetId) => {
    e.preventDefault();
    if (!dragId.current || dragId.current === targetId) return;
    setDragOver(targetId);
    // Reorder immediately — gives live preview as you drag across cards.
    // The provider's debounced save coalesces rapid reorders into one PUT.
    const next = [...order];
    const from = next.indexOf(dragId.current);
    const to   = next.indexOf(targetId);
    if (from === -1 || to === -1) return;
    next.splice(from, 1);
    next.splice(to, 0, dragId.current);
    save({ visibleIds, order: next });
  };

  const handleDragOver = (e) => {
    // Must preventDefault() on every dragover to keep drop cursor active
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
  };

  const handleDragEnd = () => {
    setDragging(false);
    setDragOver(null);
    dragId.current = null;
    setArmedId(null);   // re-arm required for the next drag
  };

  // ── Toggle widget visibility ───────────────────────────────────────────────
  const toggleWidget = (id) => {
    const next = visibleIds.includes(id)
      ? visibleIds.filter(x => x !== id)
      : [...visibleIds, id];
    // Ensure the id has a position in `order`; a freshly onboarded/admin-added
    // dynamic widget isn't in the saved order yet, and visibleWidgets is built
    // by filtering `order`, so without this it would toggle on but never render.
    const nextOrder = order.includes(id) ? order : [...order, id];
    save({ visibleIds: next, order: nextOrder });
    if (detailId === id) setDetailId(null);
  };

  // ── Render ─────────────────────────────────────────────────────────────────
  const detailWidget = detailId ? effectiveCatalog.find(w => w.id === detailId) : null;

  // The artboard's "Widgets default" control. Hides the board's BODY — grid and
  // drill-down alike — while leaving the strip, so the user can always get it
  // back. Defaulted shown; the preference persists per user.
  const widgetsPref = useSectionPref('widgets', true);

  return (
    <Box sx={{ mt: 1, mb: 0.5 }}>

      {/* ── Customise trigger — this used to live in shell/src/App.jsx's tabs
          row via a forwardRef into this component, but now that WidgetBoard
          is loaded through Module Federation (App.jsx -> PluginLoader ->
          this RemoteApp), a ref can no longer cross that boundary, so the
          board owns its own trigger button instead. ── */}
      {/* `center`, not `baseline`. This row holds two CONTROLS, not running
          text, and baseline alignment sat them 2px apart: the Manage widgets
          pill aligns on its label's baseline while SectionToggle is itself a
          flex container, so its baseline resolves to the ▲ glyph's and the
          whole button hung low. Nothing here needs a shared text baseline. */}
      <Box sx={{ display: 'flex', alignItems: 'center',
                 gap: 1.25, mb: 1.25, flexWrap: 'wrap' }}>
        {/* The heading strip's label, sort-mode switch and "as of" stamp were
            removed by request — the board now leads straight into its tiles and
            this row carries only the Manage widgets trigger, pushed right. */}
        {/* Collapsed, the strip has to say what it is hiding — otherwise the
            board looks like it failed to load rather than like it is put away. */}
        {!widgetsPref.open && (
          <Box sx={{ fontSize: 11.5, color: '#7D92C4' }}>
            {t('widgets.board.hiddenCount', '{{count}} widgets hidden',
               { count: visibleWidgets.length })}
          </Box>
        )}
        <Box sx={{ flex: 1 }} />
        <Tooltip title={t('app.widgets.customise', 'Customise widgets')}>
          <ButtonBase
            onClick={e => setPickerAnchor(e.currentTarget)}
            sx={{
              gap: 0.75, fontSize: 11.5, color: '#A8BBE6',
              bgcolor: '#0F2450', border: '1px solid #2E4A8C', borderRadius: '8px',
              px: '12px', py: '6px', flexShrink: 0, whiteSpace: 'nowrap',
              // Same floor SectionToggle uses, so the pair reads as one control
              // group rather than two buttons of slightly different heights.
              // Without it the pill's height is whatever its 14px icon and
              // 11.5px label happen to add up to, which is near 28 but not on it.
              minHeight: 28,
              '&:hover': { bgcolor: '#152652', color: '#FFFFFF' },
            }}
          >
            <TuneIcon sx={{ fontSize: 14 }} />
            {t('widgets.board.manage', 'Manage widgets')}
            {'  ·  '}
            {t('widgets.board.countOf', '{{shown}} of {{total}}',
               { shown: visibleWidgets.length, total: effectiveCatalog.length })}
          </ButtonBase>
        </Tooltip>
        <SectionToggle
          open={widgetsPref.open}
          always={widgetsPref.always}
          onToggle={widgetsPref.toggle}
          onSetAlways={widgetsPref.setAlways}
          menuTitle={t('dashboard.widgets.menuTitle', 'Widgets default')}
          words={{
            expand: t('dashboard.widgets.shown', 'shown'),
            collapse: t('dashboard.widgets.hidden', 'hidden'),
          }}
        />
      </Box>

      {/* ── Detail panel OR widget grid ── */}
      {!widgetsPref.open ? null : detailWidget ? (
        <DetailPanel
          widget={detailWidget}
          onBack={() => setDetailId(null)}
          onAsk={onAsk}
        />
      ) : (
        <Box sx={{
          display: 'grid',
          // `open` stays true for the length of the exit (useSectionPref), so
          // the grid is still mounted here and the close animation can play.
          animation: orphosAnim('grow', widgetsPref.closing),
          // auto-fit rather than a fixed column count: the design's tiles are
          // 180px-minimum cards that reflow, so a board of three and a board of
          // eight both fill the width instead of stretching three tiles across
          // it or squeezing eight.
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: '12px',
          // Cards size to their own content instead of stretching to the tallest
          // in the row. Without this, one card gaining a second line of caption
          // resized every sibling beside it.
          alignItems: 'start',
        }}>
          {orderedWidgets.map(widget => {
            const m       = metrics[widget.id] || {};
            const isOver  = dragOver === widget.id;
            const isSource = dragging && dragId.current === widget.id;
            const label   = t(`widgets.labels.${widget.id}`, widget.label);
            const value   = m.valueKey ? t(m.valueKey, m.valueParams)
                                       : resolveValue(m.value, i18n.language);
            // Present only when the service actually applied a rate. Absent for
            // every figure already in the reader's currency and for every one it
            // had no rate to price — in both of those the number IS the reported
            // number and must carry no conversion marker.
            const fx = isMoneyValue(m.value) ? m.value.fx : null;
            const fxDetail = fxLines(t, fx, isMoneyValue(m.value) ? m.value.currency : null,
                                     i18n.language);
            const subtitle = resolveSubtitle(t, m, i18n.language);
            // Compact comparison, as the watchlist shows it. Only tiles that
            // went through persona-metrics carry `hasTarget`; everything else
            // keeps its own prose caption untouched.
            // The target travels as a DEFERRED money amount ({amount, currency}),
            // for the same reason the value does, so it has to go through
            // resolveValue before interpolation — handing i18next the raw object
            // is what rendered "target [object Object]" on every money tile.
            const targetText = resolveValue(m.targetLabel, i18n.language);
            const foot = m.hasTarget === undefined ? subtitle
              : m.hasTarget
                ? (m.targetIsZero
                    // A zero target admits no percentage — 18.5L against 0 is
                    // not "infinity%", it is simply over.
                    ? t('widgets.captions.targetOver', 'target {{target}} · over', { target: targetText })
                    : t('widgets.captions.targetPct', 'target {{target}} · {{pct}}%',
                        { target: targetText, pct: Math.round(m.attainmentPct) }))
                : (m.periodLabel
                    ? t('widgets.captions.noTargetPeriod', 'no target · {{period}}', { period: m.periodLabel })
                    : t('widgets.captions.noTargetPlain', 'no target'));
            return (
              <Paper
                key={widget.id}
                elevation={0}
                // Dragging is ARMED by pressing the grip, not by the card itself.
                // With `draggable` always on, any press that moved a pixel — a
                // click on the value, a scroll gesture starting over a card —
                // began a drag: the source faded to 0.45 and every card the
                // pointer crossed lit up as a drop target. That is the jumping.
                draggable={!autoOrder && armedId === widget.id}
                onDragStart={e  => handleDragStart(e, widget.id)}
                onDragEnter={e  => handleDragEnter(e, widget.id)}
                onDragOver={handleDragOver}
                onDragEnd={handleDragEnd}
                sx={{
                  px: '13px', py: '11px',
                  border: `1px solid ${isOver ? widget.color : '#243A72'}`,
                  borderRadius: '12px', position: 'relative',
                  // Column layout so the caption can be pinned to the foot of
                  // the card: with content simply flowing from the top, a tile
                  // whose caption wrapped sat lower than its neighbours and the
                  // row lost its shared baseline.
                  display: 'flex', flexDirection: 'column',
                  // Reserve the card's full height up front. Metrics resolve one
                  // request at a time, so without a floor each arriving value —
                  // and each caption that wraps to a second line — reflowed the
                  // grid and the board appeared to jump while it loaded.
                  minHeight: 92,
                  cursor: 'default',
                  transition: 'border-color 0.1s, box-shadow 0.1s, opacity 0.1s',
                  boxShadow: isOver ? `0 0 0 3px ${widget.color}33` : 'none',
                  bgcolor: isOver ? `${widget.color}26` : '#152652',
                  opacity: isSource ? 0.45 : 1,
                }}
              >
                {/* Drag handle — the only thing that arms a drag. Hidden while
                    the board sorts itself: a grip that cannot reorder anything
                    is a broken affordance. */}
                {!autoOrder && (
                <Box
                  onMouseDown={() => setArmedId(widget.id)}
                  onMouseUp={() => setArmedId(null)}
                  title={t('widgets.board.dragHint', 'Drag to reorder')}
                  sx={{
                    // Top-RIGHT, not top-left. On the left it overlapped where
                    // the title starts, which forced a pl on the title row only
                    // — so the label sat 16px further in than the value and
                    // caption beneath it and the card had no single left edge.
                    position: 'absolute', top: 6, right: 6, color: '#7D92C4', lineHeight: 0,
                    cursor: 'grab', '&:active': { cursor: 'grabbing' },
                    '&:hover': { color: '#8DBCFF' },
                  }}
                >
                  <DragIndicatorIcon sx={{ fontSize: 16 }} />
                </Box>
                )}

                {/* Transparent overlay while ANY drag is active — prevents child
                    elements from capturing dragenter/dragover and breaking DnD */}
                {dragging && (
                  <Box sx={{
                    position: 'absolute', inset: 0, zIndex: 10,
                    borderRadius: 2, cursor: 'grabbing',
                  }} />
                )}

                {/* Title row. pr clears the drag handle in the top-right corner;
                    the left edge now matches the value and caption below. */}
                <Box sx={{ display: 'flex', alignItems: 'flex-start', mb: '5px', pr: 2.5, flexShrink: 0 }}>
                  {/* One line then ellipsis, per the design's tile: at 180px the
                      label is a caption for the figure, not a heading of its
                      own, and a second line pushed the number below its
                      neighbours. Full text on hover. */}
                  <Typography fontSize={11.5} color="#A8BBE6" fontWeight={500}
                    title={label}
                    sx={{
                      lineHeight: 1.35, width: '100%',
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                    {label}
                  </Typography>
                </Box>

                {/* Clickable metric value. When the figure was converted, the
                    same tooltip carries the provenance — a second tooltip nested
                    on the marker would need a hover target of its own at 23px,
                    and would hide the reported amount behind a smaller one. */}
                <Tooltip
                  placement="top" arrow
                  title={fxDetail ? (
                    <Box sx={{ display: 'grid', gap: '2px' }}>
                      {fxDetail.map((line, i) => (
                        <Box key={i} component='span' sx={{ fontSize: 11.5, lineHeight: 1.4 }}>
                          {line}
                        </Box>
                      ))}
                      <Box component='span' sx={{ fontSize: 11, opacity: 0.7, mt: '2px' }}>
                        {t('widgets.board.viewDetails', { label })}
                      </Box>
                    </Box>
                  ) : t('widgets.board.viewDetails', { label })}
                >
                  <Box
                    onClick={e => { e.stopPropagation(); setDetailId(widget.id); }}
                    // alignSelf keeps the hit area the width of the number. As a
                    // flex child it would otherwise stretch the full card width,
                    // so clicking empty space beside the figure would open the
                    // detail dialog.
                    sx={{ cursor: 'pointer', display: 'inline-block', alignSelf: 'flex-start' }}
                  >
                    {/* Colour carries status, never identity. `statusColor` is set
                        only from the source's RAG (or an amber escalation when the
                        metric is behind pace); with neither, the value renders
                        neutral. An empty tile is muted so it can never be mistaken
                        for a real figure. The catalog's per-widget colour is now
                        decoration only — it must not reach the number, which is how
                        "378 cost centres over budget" ended up a reassuring blue. */}
                    <Typography
                      fontSize={23} fontWeight={700} lineHeight={1.05}
                      letterSpacing='-0.6px'
                      // The "≈" is decoration a screen reader would announce as
                      // "almost equal to", which says nothing useful. Replace the
                      // whole reading with the figure followed by its provenance,
                      // so a non-sighted reader gets what the tooltip gives a
                      // sighted one instead of an unexplained approximation sign.
                      aria-label={fxDetail ? `${value ?? '—'} — ${fxDetail.join('. ')}` : undefined}
                      sx={{
                        color: m.empty ? '#7D92C4' : (m.statusColor || '#FFFFFF'),
                        whiteSpace: 'nowrap',
                        textDecoration: 'underline',
                        textDecorationColor: 'rgba(168, 187, 230, 0.35)',
                        textDecorationStyle: 'dotted',
                        textUnderlineOffset: 3,
                        '&:hover': { textDecorationColor: '#8DBCFF' },
                      }}
                    >
                      {/* "≈" marks a figure that was converted rather than
                          reported. Muted and outside the value's own colour, so
                          it reads as an annotation on the number rather than as
                          part of it — and so a reader who never hovers still
                          knows this figure is derived. */}
                      {fx && (
                        <Box component='span' aria-hidden='true'
                          sx={{ color: '#7D92C4', fontWeight: 500, mr: '2px' }}>
                          ≈
                        </Box>
                      )}
                      {value ?? '—'}
                    </Typography>
                  </Box>
                </Tooltip>

                {/* Comparison line. The arrow appears only when the source actually
                    told us a direction — previously an unknown status defaulted the
                    caption to red and any up-trend to green, so tiles asserted "off
                    plan" or "on plan" about numbers nobody had judged. */}
                {foot && (
                  // mt:auto pushes the caption to the bottom of the card, so
                  // every tile in the row shares one caption baseline whatever
                  // its value or caption length.
                  <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5, mt: 'auto', pt: '5px' }}>
                    {/* No trend arrow on a watchlist tile: the attainment
                        percentage in the foot already states the direction, and
                        the arrow duplicated it at the cost of a wider line. */}
                    {/* Clamped to two lines. Comparison captions are open-ended
                        ("92% of target ₹20.00 Cr · FY2027 Q2 · 50% of period
                        elapsed") and a third line would push the card taller than
                        its neighbours. Full text stays available on hover. */}
                    <Typography
                      fontSize={10.5} title={subtitle || foot}
                      sx={{
                        color: m.statusColor || '#7D92C4',
                        display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                      }}
                    >
                      {foot}
                    </Typography>
                  </Box>
                )}
              </Paper>
            );
          })}

          {visibleWidgets.length === 0 && (
            <Box sx={{ gridColumn: '1/-1', py: 3, textAlign: 'center' }}>
              <Typography fontSize={13} color="text.secondary">
                {t('widgets.board.empty', 'No widgets visible. Click Customise widgets to add some.')}
              </Typography>
            </Box>
          )}
        </Box>
      )}

      {/* ── Widget picker popover ── */}
      <Popover
        open={Boolean(pickerAnchor)}
        anchorEl={pickerAnchor}
        onClose={() => setPickerAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        PaperProps={{ sx: { mt: 0.5, borderRadius: 2, border: '1px solid #243A72', boxShadow: '0 8px 24px #0002', minWidth: 280 } }}
      >
        <Box sx={{ p: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}>
            <Typography fontWeight={700} fontSize={13}>{t('widgets.board.title', 'Dashboard Widgets')}</Typography>
            {source === 'user' && (
              <Button size="small" onClick={resetToTenantDefault}
                sx={{ fontSize: 10, textTransform: 'none', color: '#C3D3F2', minWidth: 0, px: 0.75 }}>
                {t('widgets.board.reset', 'Reset to tenant default')}
              </Button>
            )}
          </Box>
          <Typography fontSize={11} color="text.secondary" mb={1.5}>
            {t('widgets.board.hint', 'Toggle to show or hide. Drag widgets on the board to reorder.')}
            {source === 'tenant' && t('widgets.board.hintTenant', ' Layout currently uses your tenant’s default.')}
            {source === 'system' && t('widgets.board.hintSystem', ' No tenant default set — using the platform default.')}
          </Typography>

          {/* Grouped by business area; each widget shows its data source so
              operators can tell where the metric comes from at a glance. */}
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
            {groupByArea(effectiveCatalog).map(({ area, widgets }) => (
              <CollapsibleSection key={area} title={t(`widgets.areas.${area}`, area)}
                count={widgets.length} detailsSx={{ p: 0.5 }}>
                {widgets.map(w => (
                  <Box key={w.id} sx={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    px: 1, py: 0.5, borderRadius: 1.5, gap: 1,
                    '&:hover': { bgcolor: '#0F2450' },
                  }}>
                    <Box sx={{ minWidth: 0 }}>
                      <Typography fontSize={12} fontWeight={500}>{t(`widgets.labels.${w.id}`, w.label)}</Typography>
                      <Typography fontSize={10} color="text.secondary">{t(`widgets.desc.${w.id}`, w.description)}</Typography>
                      {w.source && (
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.25 }}>
                          <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: w.color, flexShrink: 0 }} />
                          <Typography fontSize={10} color="#7D92C4">
                            {t('widgets.board.sourceLabel', 'Source:')} {t(`widgets.sources.${w.source}`, w.source)}
                          </Typography>
                        </Box>
                      )}
                    </Box>
                    <Switch
                      size="small"
                      checked={visibleIds.includes(w.id)}
                      onChange={() => toggleWidget(w.id)}
                      sx={{
                        '& .MuiSwitch-switchBase.Mui-checked': { color: w.color },
                        '& .MuiSwitch-switchBase.Mui-checked + .MuiSwitch-track': { bgcolor: w.color },
                      }}
                    />
                  </Box>
                ))}
              </CollapsibleSection>
            ))}
          </Box>
        </Box>
      </Popover>
    </Box>
  );
}
