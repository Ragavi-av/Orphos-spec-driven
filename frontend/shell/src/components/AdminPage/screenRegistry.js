// ── Screen registry ─────────────────────────────────────────────────────────
// THE single source of truth for every admin tab and user-dashboard screen in
// the app. Both the admin navigation (AdminPage `tabDefs` / `PLUGIN_ADMIN_MAP`)
// AND the Access Control provisioning catalog (accessCatalog.js `ADMIN_GROUPS` /
// `LOCKED_RESOURCES` / `USER_GROUP`) are DERIVED from this list — so adding a
// screen here makes it show up in the admin UI *and* become grantable in Access
// Control automatically. Neither can silently drift from the other again.
//
// To add a new admin tab:
//   1. Add an entry to ADMIN_SCREENS below (key, group, label; optional flags).
//   2. If its body is a federated plugin, add `plugin: ['<pluginId>', '<Section>']`.
//   3. Give it an icon in AdminPage's TAB_ICON map (keyed by the same `key`).
//   4. Render its body in AdminPage's content switch (shell-native tabs only).
// It is grantable in Access Control unless flagged platformOnly/platformAdminOnly.
//
// Keys are the opaque resourceKeys the access backend stores grants against, so
// they must stay stable once shipped.

// Admin group order + labels (drives both the AdminPage group strip and the
// Access Control grouping). Groups whose every screen is locked (security-ops,
// platform) simply produce no grantable tabs and drop out of the catalog.
export const ADMIN_GROUP_ORDER = [
  ['security-ops', 'Platform Operations'],
  ['auth-authz',   'Authentication & Authorization'],
  ['data',         'Data'],
  ['ai',           'AI'],
  ['documents',    'Document Ingestion'],
  ['comms',        'Communications'],
  ['appearance',   'Appearance'],
  ['platform',     'Platform'],
];

// Every admin screen. Flags:
//   platformOnly       — cross-tenant, platform scope only (never grantable).
//   platformAdminOnly  — only a platform admin may see/manage it (never grantable).
//   plugin: [id, Sec]  — body is a Module Federation remote's admin section.
// A screen with neither flag is a normal tenant-scoped, grantable tab.
export const ADMIN_SCREENS = [
  // ── Platform Operations (cross-tenant — platform admin only) ──
  { key: 'tenants',        group: 'security-ops', label: 'Tenants',        platformOnly: true },
  { key: 'services',       group: 'security-ops', label: 'Services',       platformOnly: true },
  { key: 'db-connections', group: 'security-ops', label: 'DB Connections', platformOnly: true },
  { key: 'bases',          group: 'security-ops', label: 'Platform Tiers', platformOnly: true },
  { key: 'logs',           group: 'security-ops', label: 'Service Logs',   platformOnly: true },
  // ── Authentication & Authorization (tenant-scoped) ──
  { key: 'auth',           group: 'auth-authz', label: 'Authentication' },
  { key: 'users',          group: 'auth-authz', label: 'User Management' },
  { key: 'access-control', group: 'auth-authz', label: 'Access Control' },
  // ── Data ──
  { key: 'onboarding',     group: 'data', label: 'Data Onboarding' },
  { key: 'data-sources',   group: 'data', label: 'Data Sources' },
  { key: 'data-mapping',   group: 'data', label: 'Data Mapping' },
  { key: 'ingestion-jobs', group: 'data', label: 'Ingestion Jobs' },
  { key: 'queries',        group: 'data', label: 'Query History', plugin: ['ai-copilot', 'QueryHistoryTab'] },
  { key: 'ontology',       group: 'data', label: 'Ontology Sources' },
  // ── AI ──
  { key: 'ai',       group: 'ai', label: 'AI Providers',      plugin: ['ai-copilot', 'AIProvidersTab'] },
  { key: 'prompts',  group: 'ai', label: 'LLM Prompts',       plugin: ['ai-copilot', 'LLMPromptsTab'] },
  { key: 'doc-ai',   group: 'ai', label: 'Document AI',       plugin: ['doc-ingest', 'DocumentAITab'] },
  { key: 'email-ai', group: 'ai', label: 'Email AI',          plugin: ['email-scanner', 'EmailAITab'] },
  { key: 'comms-ai', group: 'ai', label: 'Communications AI', plugin: ['email-scanner', 'CommsAITab'] },
  // ── Document Ingestion ──
  { key: 'doc-ingest-defaults', group: 'documents', label: 'Ingestion Defaults', plugin: ['doc-ingest', 'IngestionDefaultsTab'] },
  { key: 'doc-sources',         group: 'documents', label: 'Folder Connections', plugin: ['doc-ingest', 'SourceConnectorsTab'] },
  { key: 'doc-upload',          group: 'documents', label: 'Doc Ingestion',      plugin: ['doc-ingest', 'DocIngestSettingsTab'] },
  { key: 'doc-list',            group: 'documents', label: 'Ingested Documents', plugin: ['doc-ingest', 'DocIngestListTab'] },
  { key: 'doc-search-settings', group: 'documents', label: 'Search Settings',    plugin: ['rag-search', 'SearchSettingsTab'] },
  { key: 'doc-queries',         group: 'documents', label: 'Doc Search History', plugin: ['doc-ingest', 'DocIngestQueriesTab'] },
  // ── Communications ──
  { key: 'email',        group: 'comms', label: 'Email',                  plugin: ['email-scanner', 'EmailConfigTab'] },
  { key: 'event-alerts', group: 'comms', label: 'Event Alerts' },
  { key: 'mail-monitor', group: 'comms', label: 'Mailbox Monitor',        plugin: ['email-scanner', 'MailMonitorTab'] },
  { key: 'channels',     group: 'comms', label: 'Communication Channels', plugin: ['email-scanner', 'ChannelConnectorsTab'] },
  // ── Appearance ──
  { key: 'branding',          group: 'appearance', label: 'Branding' },
  { key: 'dashboard',         group: 'appearance', label: 'Dashboard Defaults', plugin: ['widgets', 'DashboardDefaultsTab'] },
  { key: 'platform-branding', group: 'appearance', label: 'Platform Default', platformOnly: true },
  // ── Platform (tenant-scoped, platform admin only) ──
  { key: 'plugins',         group: 'platform', label: 'Plugins',           platformAdminOnly: true },
  { key: 'agents',          group: 'platform', label: 'Agents',            platformAdminOnly: true },
  { key: 'agent-approvals', group: 'platform', label: 'Agent Approvals',   platformAdminOnly: true },
  { key: 'mobile-access',   group: 'platform', label: 'Mobile Access',     platformAdminOnly: true },
  // Voice is cross-tenant by nature — it starts and stops a shared speech model
  // and grants it to named tenants — so it is platform scope, not a tenant tab.
  { key: 'voice',           group: 'platform', label: 'Voice',             platformOnly: true, platformAdminOnly: true },
  { key: 'capabilities',    group: 'platform', label: 'Capability Catalog', platformOnly: true, platformAdminOnly: true },
];

// User-facing dashboard sections (plugin tabs + shell panels), enforced via the
// same access grants. Keys MUST match the user dashboard section keys in App.jsx
// and each plugin's PluginManifest tabs_json.
export const USER_SCREENS = [
  { key: 'tasks-for-action', label: 'Tasks & Approvals' },
  { key: 'notifications',    label: 'Notifications' },
  { key: 'doc-search',       label: 'Document Search' },
  { key: 'decision-log',     label: 'Decision Log' },
  { key: 'ai-history',       label: 'AI History' },
  { key: 'ask-orphos',       label: 'AI Copilot (panel)' },
  { key: 'widget-board',     label: 'Widget Board (panel)' },
];

/** A screen is grantable (appears in Access Control) unless it is platform-scoped. */
export function isGrantable(screen) {
  return !screen.platformOnly && !screen.platformAdminOnly;
}
