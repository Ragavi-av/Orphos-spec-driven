import axios from 'axios';
import i18n from 'eip-shell-context/i18n';
import { tenantTimezone } from 'eip-shell-context/ScheduleEditor';

// Thin wrappers over the mail-insight item-action endpoints. Each returns the
// updated item so callers can patch local state without a full refetch.

export async function reprioritize(id, priority) {
  const r = await axios.patch(`/api/mail-insight/items/${id}/priority`, { priority });
  return r.data;
}

export async function setStatus(id, status) {
  const r = await axios.patch(`/api/mail-insight/items/${id}/status`, { status });
  return r.data;
}

export async function setReminder(id, reminderAtIso) {
  const r = await axios.patch(`/api/mail-insight/items/${id}/reminder`, { reminderAt: reminderAtIso });
  return r.data;
}

// Deprioritize a notification by lowering its criticality (ALERT → WARNING → INFORMATION).
export async function setCriticality(id, criticality) {
  const r = await axios.patch(`/api/mail-insight/items/${id}/criticality`, { criticality });
  return r.data;
}

// Next-lower criticality level, or null if already at the bottom.
export function nextLowerCriticality(level) {
  const order = ['ALERT', 'WARNING', 'INFORMATION'];
  const i = order.indexOf((level || '').toUpperCase());
  return i >= 0 && i < order.length - 1 ? order[i + 1] : null;
}

// Next-higher criticality level, or null if already at the top.
export function nextHigherCriticality(level) {
  const order = ['ALERT', 'WARNING', 'INFORMATION'];
  const i = order.indexOf((level || '').toUpperCase());
  return i > 0 ? order[i - 1] : null;
}

// ── Email-task agent (AI draft reply / schedule meeting) ────────────────────

// Ask the agent to draft a reply or meeting for an item. Returns
// { action, subject, bodyText, start, end, location, note }.
export async function agentDraft(id, action, instruction) {
  const r = await axios.post(`/api/mail-insight/items/${id}/agent/draft`, { action, instruction });
  return r.data;
}

// Send the (user-reviewed) draft through the tenant's SMTP configuration.
// payload: { action, to: [], cc: [], subject, bodyText, start?, end?, location? }
export async function agentSend(id, payload) {
  const r = await axios.post(`/api/mail-insight/items/${id}/agent/send`, payload);
  return r.data;
}

// ── Communication-channel agent (AI draft/send a reply back to Slack/Teams/…) ─

// Draft a reply for a channel item. Returns { itemId, action, channel, bodyText }.
export async function commsAgentDraft(id, instruction) {
  const r = await axios.post(`/api/mail-insight/comms/items/${id}/agent/draft`, { action: 'REPLY', instruction });
  return r.data;
}

// Post the (user-reviewed) reply back to the channel the item came from.
export async function commsAgentSend(id, bodyText) {
  const r = await axios.post(`/api/mail-insight/comms/items/${id}/agent/send`, { action: 'REPLY', bodyText });
  return r.data;
}

// Shared severity → colour map for priority/criticality badges.
export const SEV_COLOR = {
  CRITICAL: { bg: '#fee2e2', text: '#dc2626', border: '#fca5a5' },
  HIGH:     { bg: '#fef3c7', text: '#d97706', border: '#fcd34d' },
  MEDIUM:   { bg: '#fff7ed', text: '#ea580c', border: '#fdba74' },
  LOW:      { bg: '#f0fdf4', text: '#16a34a', border: '#86efac' },
  ALERT:       { bg: '#fee2e2', text: '#dc2626', border: '#fca5a5' },
  WARNING:     { bg: '#fef3c7', text: '#d97706', border: '#fcd34d' },
  INFORMATION: { bg: '#eff6ff', text: '#2563eb', border: '#93c5fd' },
};

export function fmtDate(iso) {
  if (!iso) return null;
  // Format in the active UI language (shared i18n singleton), not the browser
  // locale, so dates match the rest of the screen when the user switches; and
  // in the tenant's time zone (Appearance tab) so times are consistent for the
  // whole org rather than each viewer's browser zone.
  try {
    return new Date(iso).toLocaleString(i18n.language || undefined,
      { dateStyle: 'medium', timeStyle: 'short', timeZone: tenantTimezone() });
  } catch { return iso; }
}
