// Locale-aware date/time formatting.
//
// Both halves come from the tenant's primary country (Admin → Branding): the
// TIME ZONE a timestamp resolves against, and the NOTATION it is written in
// (dd/mm vs yyyy/mm/dd, 12h vs 24h). Neither follows the reader's UI language —
// a language switch translates the prose around a timestamp and leaves the
// timestamp itself alone, because moving the wall clock changes what the value
// MEANS rather than how it reads. See eip-shell-context/locale.
//
// The UI language remains the fallback for a tenant whose admin has set no
// country: without one there is nothing better to format in, and formatting in
// whatever is being read is the historical behaviour. That fallback is what
// stopped "17/2/2026" and "Wednesday, 20 August 2026" appearing on an otherwise
// Japanese board — every call site used to either hardcode 'en-GB' or pass
// nothing at all, and passing nothing means the BROWSER's locale.
//
// Formatting reads both values off shared singletons rather than taking them as
// arguments, because most callers are plain module-level helpers with no React
// context (decision-log/api.js, MailInsights/meetings.js,
// admin/mailMonitorUtils.js). That is safe because every federated bundle's
// copy is kept in step — i18n by the cross-bundle bridge in ./i18n.js, the
// country's zone and locale by the broadcast in ./country.js.

import i18n from './i18n';
import { tenantTimezone } from './ScheduleEditor';
import { formatLocale } from './locale';
import { hour12Options } from './timeFormat';

/**
 * The locale to format in: the tenant country's tag ('ja-JP'), falling back to
 * the UI language as a bare tag ('ja-JP' → 'ja') when no country is set.
 */
export function activeLocale() {
  return formatLocale((i18n.language || 'en').split('-')[0]);
}

/**
 * Coerce whatever a service returned into a Date, or null.
 *
 * Timestamps arrive as ISO strings, epoch millis and (from ArcadeDB) occasional
 * free-form values. A caller that got an unparseable value must render its own
 * placeholder rather than "Invalid Date", so this reports the failure as null.
 */
function toDate(value) {
  if (value === null || value === undefined || value === '') return null;
  const d = value instanceof Date ? value : new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Date only, in the active language and the tenant's zone. */
export function formatDate(value, { fallback = '—', ...options } = {}) {
  const d = toDate(value);
  if (!d) return fallback;
  try {
    return d.toLocaleDateString(activeLocale(), { timeZone: tenantTimezone(), ...options });
  } catch {
    // An invalid tenant zone must not blank the cell — fall back to the
    // browser's zone rather than losing the date entirely.
    return d.toLocaleDateString(activeLocale(), options);
  }
}

/**
 * Date + time, in the workspace's zone and notation, on the reader's clock.
 *
 * The reader's 12h/24h preference sits between the defaults and the caller's
 * own options, so a call site that passes an explicit `hour12` still wins —
 * a few surfaces (log timestamps, exports) want one fixed form regardless.
 */
export function formatDateTime(value, { fallback = '—', ...options } = {}) {
  const d = toDate(value);
  if (!d) return fallback;
  const opts = { ...hour12Options(), ...options };
  try {
    return d.toLocaleString(activeLocale(), { timeZone: tenantTimezone(), ...opts });
  } catch {
    return d.toLocaleString(activeLocale(), opts);
  }
}

/** Time only, in the tenant's zone, on the reader's clock. */
export function formatTime(value, { fallback = '—', ...options } = {}) {
  const d = toDate(value);
  if (!d) return fallback;
  const opts = { hour: '2-digit', minute: '2-digit', ...hour12Options(), ...options };
  try {
    return d.toLocaleTimeString(activeLocale(), { timeZone: tenantTimezone(), ...opts });
  } catch {
    return d.toLocaleTimeString(activeLocale(), opts);
  }
}

/** Offset of `timeZone` from UTC at the instant `utcMs`, in ms (DST-aware). */
function zoneOffsetMs(timeZone, utcMs) {
  const p = {};
  for (const { type, value } of new Intl.DateTimeFormat('en-US', {
    timeZone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(utcMs))) p[type] = Number(value);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - utcMs;
}

/**
 * Midnight `offsetDays` from today IN THE TENANT'S ZONE, as a Date.
 *
 * "Today" for a calendar window, a travel countdown or a meetings rail is the
 * tenant's day, the same zone every timestamp on the board is displayed in —
 * not the browser's. Using the browser's midnight while displaying in the
 * tenant's zone put a traveller's early meetings on the wrong day, and made the
 * web disagree with the mobile app. Falls back to browser midnight if the zone
 * is not one Intl knows.
 */
export function tenantDayStart(offsetDays = 0, now = new Date()) {
  const timeZone = tenantTimezone();
  try {
    const p = {};
    for (const { type, value } of new Intl.DateTimeFormat('en-US', {
      timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(now)) p[type] = Number(value);
    const wallMidnight = Date.UTC(p.year, p.month - 1, p.day + offsetDays);
    // Two passes settle the offset across a DST change at that midnight.
    let t = wallMidnight - zoneOffsetMs(timeZone, wallMidnight);
    t = wallMidnight - zoneOffsetMs(timeZone, t);
    return new Date(t);
  } catch {
    const d = new Date(now); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + offsetDays);
    return d;
  }
}

/** A plain number with the active language's grouping ("1,234" / "1,234"). */
export function formatNumber(value, options) {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString(activeLocale(), options);
}
