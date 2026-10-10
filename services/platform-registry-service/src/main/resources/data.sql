-- Seed the five well-known plugins so the registry works before any plugin
-- container has started. Services upsert their own row on startup via /register,
-- which overwrites these rows with live version + remoteEntryPath from the env.

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'ai-copilot',
    'AI Co-pilot',
    '1.0.0',
    'plugin_ai_copilot',
    '/remotes/ai-copilot/assets/remoteEntry.js',
    '[{"key":"ai-history","labelKey":"nav.aiHistory","roles":["user","admin"]}]',
    '["LLMPromptsTab","AIProvidersTab","QueryHistoryTab"]',
    TRUE,
    FALSE,
    CURRENT_TIMESTAMP
);

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'email-scanner',
    'Email Scanner',
    '1.0.0',
    'plugin_email_scanner',
    '/remotes/email-scanner/assets/remoteEntry.js',
    '[{"key":"tasks-for-action","labelKey":"nav.tasks","roles":["user","admin"]},{"key":"notifications","labelKey":"nav.notifications","roles":["user","admin","Viewer"]},{"key":"upcoming-meetings","labelKey":"nav.meetings","roles":["user","admin"]}]',
    '["EmailConfigTab","MailMonitorTab","EmailAITab","ChannelConnectorsTab","CommsAITab"]',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP
);

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'doc-ingest',
    'Document Ingest',
    '1.0.0',
    'plugin_doc_ingest',
    '/remotes/doc-ingest/assets/remoteEntry.js',
    '[{"key":"doc-search","labelKey":"nav.docSearch","roles":["user","admin","Viewer"]}]',
    '["DocIngestListTab","DocIngestQueriesTab","DocIngestSettingsTab","IngestionDefaultsTab","DocumentAITab"]',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP
);

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'rag-search',
    'RAG Search & Widgets',
    '1.0.0',
    'plugin_rag_search',
    '/remotes/rag-search/assets/remoteEntry.js',
    '[]',
    '["SearchSettingsTab"]',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP
);

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'widgets',
    'Widgets',
    '1.0.0',
    'plugin_widgets',
    '/remotes/widgets/assets/remoteEntry.js',
    '[]',
    '["DashboardDefaultsTab"]',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP
);

-- Not tab-based like the other plugins — WidgetBoard renders inline above the
-- tab row whenever a tenant user is neither Viewer nor Guest. The shell
-- checks usePlugin('widgets') for enablement and passes slot="top-widgets"
-- (a plain informational string; only "side-panel" carries special layout
-- meaning today) to PluginLoader instead of iterating it via usePluginTabs().

MERGE INTO plugin_manifest (
    plugin_id, display_name, version, remote_scope, remote_entry_path,
    tabs_json, admin_sections_json, has_side_panel, built_in, registered_at
) KEY (plugin_id) VALUES (
    'decision-log',
    'Decision Log',
    '1.0.0',
    'plugin_decision_log',
    '/remotes/decision-log/assets/remoteEntry.js',
    '[{"key":"decision-log","labelKey":"nav.decisionLog","roles":["user","admin"]}]',
    '[]',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP
);
