import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
import AICopilot from './AICopilot';
import CopilotHistory from './CopilotHistory';
import AIProvidersTab from './admin/AIProvidersTab';
import LLMPromptsTab from './admin/LLMPromptsTab';
import QueryHistoryTab from './admin/QueryHistoryTab';

/**
 * Entry point exposed as `plugin_ai_copilot/RemoteApp`.
 *
 * This plugin owns two slots (see platform-registry-service's PluginManifest
 * row for "ai-copilot": hasSidePanel=true, tabs=[{key:"ai-history"}]):
 *   - slot="side-panel"  → the AI Co-pilot chat panel
 *   - tabKey="ai-history" → the query-history tab
 * plus three admin sections: AIProvidersTab, LLMPromptsTab, QueryHistoryTab.
 *
 * `context.copilotQuery`/`context.setCopilotQuery` carry the cross-remote
 * "ask the copilot this" signal that WidgetBoard (plugin_widgets) and
 * TasksForAction/NotificationsPanel (plugin_email_scanner) trigger — this
 * used to be plain prop-drilling through shell/src/App.jsx before the
 * Module Federation split; now the shell still owns the state, just passed
 * down via the `context` prop every remote already receives.
 */
export default function RemoteApp(props) {
  // i18next/react-i18next are NOT federation-shared (see shell/vite.config.js),
  // so this remote's useTranslation() would bind to its own uninitialised
  // i18next copy and render raw keys. Bridge our react-i18next context to the
  // shell's singleton instance (via the shared eip-shell-context package) —
  // translations AND tenant label overrides then flow through as one source.
  return (
    <RemoteThemeProvider themeParams={props?.context?.themeParams}>
      <I18nextProvider i18n={sharedI18n}>
        <RemoteAppInner {...props} />
      </I18nextProvider>
    </RemoteThemeProvider>
  );
}

function RemoteAppInner({ tabKey, slot, adminSection, context }) {
  if (adminSection === 'AIProvidersTab') return <AIProvidersTab />;
  if (adminSection === 'LLMPromptsTab') return <LLMPromptsTab />;
  if (adminSection === 'QueryHistoryTab') return <QueryHistoryTab />;
  if (slot === 'side-panel') {
    return <AICopilot externalQuery={context?.copilotQuery}
                      tenantTimezone={context?.tenantTimezone}
                      hideProviderRow={!!context?.modelChipInShell}
                      onModelLabel={context?.onModelLabel}
                      embedded={!!context?.dockEmbedded} />;
  }
  // The dock's history panel. Same component the ai-history TAB used to render;
  // that tab was withdrawn when history moved behind the dock's clock control,
  // so this slot is now its only route in.
  if (slot === 'copilot-history' || tabKey === 'ai-history') {
    return <CopilotHistory />;
  }
  return null;
}
