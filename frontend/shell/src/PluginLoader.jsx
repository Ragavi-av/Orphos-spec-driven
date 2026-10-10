import React, { Suspense, lazy, useMemo } from 'react';
import { Box, CircularProgress, Typography } from '@mui/material';

/**
 * Static map from pluginId → lazy-loaded RemoteApp factory.
 *
 * The import() strings MUST be static literals so Vite/Rollup can resolve
 * the Module Federation remote at build time. The lazy() wrapper defers
 * the actual network fetch until the component is first rendered.
 *
 * If a plugin container is down the Suspense boundary catches the rejection
 * and shows PluginErrorFallback instead of crashing the whole shell.
 */
const REMOTE_MAP = {
  'ai-copilot':    lazy(() => import('plugin_ai_copilot/RemoteApp')),
  'email-scanner': lazy(() => import('plugin_email_scanner/RemoteApp')),
  'doc-ingest':    lazy(() => import('plugin_doc_ingest/RemoteApp')),
  'rag-search':    lazy(() => import('plugin_rag_search/RemoteApp')),
  'decision-log':  lazy(() => import('plugin_decision_log/RemoteApp')),
  'widgets':       lazy(() => import('plugin_widgets/RemoteApp')),
};

function PluginLoadingFallback() {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 4, px: 2, color: '#7D92C4' }}>
      <CircularProgress size={16} />
      <Typography variant="body2">Loading plugin…</Typography>
    </Box>
  );
}

function PluginErrorFallback({ pluginId }) {
  return (
    <Box sx={{ py: 4, px: 2, color: '#FF6B81', fontSize: 13 }}>
      Plugin <strong>{pluginId}</strong> failed to load. The service may be unavailable.
    </Box>
  );
}

class PluginErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <PluginErrorFallback pluginId={this.props.pluginId} />;
    return this.props.children;
  }
}

/**
 * Renders the RemoteApp exposed by the given plugin, passing a `context` prop
 * that gives the plugin everything it needs from the shell (auth, tenant, callbacks).
 *
 * @param {string}  pluginId     - e.g. "ai-copilot"
 * @param {string}  [tabKey]     - which tab slot this render is filling
 * @param {string}  [slot]       - "side-panel" for the fixed right panel, "top-widgets" for the widgets plugin
 * @param {string}  [adminSection] - admin-tab section id (e.g. "AIProvidersTab"), for AdminPage.jsx
 * @param {object}  context      - shell-provided context (axiosAuth, keycloak, cross-remote callbacks, etc.)
 */
export default function PluginLoader({ pluginId, tabKey, slot, adminSection, context }) {
  const RemoteApp = REMOTE_MAP[pluginId];

  if (!RemoteApp) {
    return <PluginErrorFallback pluginId={pluginId} />;
  }

  return (
    <PluginErrorBoundary pluginId={pluginId}>
      <Suspense fallback={<PluginLoadingFallback />}>
        <RemoteApp tabKey={tabKey} slot={slot} adminSection={adminSection} context={context} />
      </Suspense>
    </PluginErrorBoundary>
  );
}
