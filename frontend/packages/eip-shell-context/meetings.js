// Shared helpers for the calendar views — the Upcoming Meetings tab, the
// highlight strip that links to it, the AI Summary rail, and the "next meeting"
// panel in the dashboard greeting band.
//
// These live in eip-shell-context rather than in the email-scanner remote
// because the greeting band is the SHELL's, and a host cannot import from a
// remote's source. Keeping one copy is the point: the greeting band's "in 45
// min" and the rail's countdown are the same fact, and two implementations of
// "which meeting is next" would eventually disagree on screen.

import axios from 'axios';
import { formatTime, tenantDayStart } from './datetime';

/**
 * Start/end of the tenant's current day as ISO instants.
 *
 * The window is computed client-side and passed to the API rather than letting
 * the server decide what "today" means: the server would have to assume a
 * timezone, and assuming UTC puts an early-morning IST meeting on the previous
 * day. The zone is the tenant's — the one the meeting times are displayed in —
 * so the window and the times on screen always describe the same day.
 */
export function todayWindow() {
  return { from: tenantDayStart(0).toISOString(), to: tenantDayStart(1).toISOString() };
}

/** Event start: dueDate, falling back to receivedAt. */
export function startOf(m) {
  return m?.dueDate || m?.receivedAt || null;
}

/**
 * "09:30 am" / "09:30" from an ISO instant, in the APP's language.
 *
 * `undefined` as the locale meant the BROWSER's locale, which is not the same
 * thing — an en-GB browser kept rendering "01:05 pm" down a Japanese meetings
 * rail.
 */
export function fmtTime(iso) {
  return iso ? formatTime(iso, { fallback: '' }) : '';
}

/** Today's meetings, earliest first. Throws so callers can show a load error. */
export async function fetchTodaysMeetings() {
  const r = await axios.get('/api/mail-insight/meetings', { params: todayWindow() });
  const rows = Array.isArray(r.data) ? r.data : (r.data?.result || []);
  return rows;
}

/** The next meeting that has not started yet, or null once the day is done. */
export function nextUpcoming(meetings) {
  const now = Date.now();
  return (meetings || []).find(m => {
    const t = Date.parse(startOf(m));
    return isFinite(t) && t >= now;
  }) || null;
}

/**
 * The prompt sent to the AI Copilot for a meeting.
 *
 * Deliberately asks for more than the invitation already shows — the copilot
 * answers against the tenant's own data, so it can pull in the decisions, risks
 * and figures behind the meeting rather than restating the invite text that is
 * already on screen.
 */
export function briefingPrompt(m) {
  const when = fmtTime(startOf(m));
  return `Brief me for my ${when} meeting "${m.title}"`
    + `${m.senderDisplay ? `, organised by ${m.senderDisplay}` : ''}. `
    + `${m.summary ? `The invitation says: "${m.summary}". ` : ''}`
    + 'Tell me what it is about, what has changed since we last discussed it, '
    + 'what decisions are likely to be needed from me, and any open risks or '
    + 'commitments I should walk in knowing.';
}

/**
 * Whole minutes until a meeting starts, or null if it has no parseable start
 * or is already behind us.
 *
 * The greeting band's "in 45 min" and the AI Summary rail's countdown are the
 * same fact and now come from the same function — they used to be two separate
 * inline `Math.round((Date.parse(...) - now) / 60000)` expressions that could
 * round to different minutes on either side of a tick.
 */
export function minutesUntil(meeting) {
  const t = Date.parse(startOf(meeting));
  if (!isFinite(t)) return null;
  const mins = Math.round((t - Date.now()) / 60000);
  return mins >= 0 ? mins : null;
}

/**
 * The subtitle under a meeting title: organiser and, when the invitation gave
 * one, its summary — joined with the interpunct the artboard uses.
 *
 * Returns '' rather than a placeholder when neither is present, so the caller
 * can skip the line entirely instead of rendering an empty row.
 */
export function meetingSubtitle(meeting) {
  return [meeting?.senderDisplay, meeting?.summary].filter(Boolean).join(' · ');
}
