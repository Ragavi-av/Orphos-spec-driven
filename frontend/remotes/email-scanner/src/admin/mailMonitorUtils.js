// Shared helpers for the Mailbox & Calendar Monitor admin screen (MailMonitorTab,
// MailConnectionCard, MonitoredMailboxesPanel).

import { tenantTimezone } from 'eip-shell-context/ScheduleEditor';

// Provider catalogue. MS_GRAPH uses the Microsoft Graph app-only API (mail +
// calendar). GOOGLE / ZOHO / IMAP all use the generic IMAP connector (mail only,
// per-mailbox app password); the presets just seed sensible host/port defaults.
export const PROVIDERS = [
  { value: 'MS_GRAPH', label: 'Microsoft 365 (Outlook)', imap: false },
  { value: 'GOOGLE',   label: 'Google Workspace / Gmail', imap: true,  host: 'imap.gmail.com', port: 993 },
  { value: 'ZOHO',     label: 'Zoho Mail',                imap: true,  host: 'imap.zoho.com',  port: 993 },
  { value: 'IMAP',     label: 'Other (IMAP)',             imap: true,  host: '',               port: 993 },
];
export const providerDef = (v) => PROVIDERS.find(p => p.value === v) || PROVIDERS[0];

// Turn an axios error into an actionable message. A missing response usually
// means the mail-insight container is down / unreachable (nginx 502), which is
// the most common cause of "the Add button does nothing".
export function errMsg(e, fallback) {
  if (e?.response) {
    const d = e.response.data;
    const detail = (d && (d.error || d.message)) || e.response.statusText || '';
    return `${fallback} (HTTP ${e.response.status}${detail ? ': ' + detail : ''})`;
  }
  return `${fallback}: can't reach the Mail Insight service. Make sure it's running (e.g. build.bat mail) and try again.`;
}

// Map a raw Graph/scan error into an actionable hint. Returns null when the
// error isn't one of the known, fixable cases (the raw text is still shown).
export function diagnoseMailError(raw) {
  if (!raw) return null;
  const t = String(raw).toLowerCase();
  if (t.includes('invalid_client') || t.includes('aadsts7000216') || t.includes('client_secret')) {
    return 'Client secret is missing or expired. Re-enter the Azure app client secret on this connection and Save. (In dev, the secret store can lose the secret on a Docker restart.)';
  }
  if (t.includes('accessdenied') || t.includes('access is denied') || t.includes(' 403')) {
    return 'Microsoft Graph denied access. In the Azure app registration, add the APPLICATION permissions Mail.Read and Calendars.Read, then click "Grant admin consent".';
  }
  if (t.includes('invaliduser') || t.includes('invalid user') || t.includes(' 404')) {
    return 'The mailbox was not found in the directory. Make sure the monitored address is a real, licensed user (or shared mailbox) in this Azure tenant.';
  }
  if (t.includes(' 401') || t.includes('unauthorized')) {
    return 'Authentication to Microsoft Graph failed. Check the Directory (tenant) ID, Application (client) ID, and client secret.';
  }
  return null;
}

export function fmtDate(iso) {
  if (!iso) return '—';
  // Render in the tenant time zone (Appearance tab) so monitor timestamps are
  // consistent org-wide rather than each viewer's browser zone.
  try { return new Date(iso).toLocaleString(undefined, { timeZone: tenantTimezone() }); }
  catch { return iso; }
}
