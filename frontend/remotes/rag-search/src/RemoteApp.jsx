import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
import SearchSettingsTab from './admin/SearchSettingsTab';

/**
 * Entry point exposed as `plugin_rag_search/RemoteApp`.
 *
 * No tabs (tabs_json is `[]` in the PluginManifest seed) — this plugin only
 * contributes an admin section today. SPARQLExplorer.jsx and NLQueryChat.jsx
 * moved into this remote alongside SearchSettingsTab (per the original
 * feature-domain grouping) but are not wired into any tab or admin section
 * in the pre-federation app either — orphaned then, orphaned now. Not wiring
 * new UI surfaces for them here that didn't exist before the split.
 */
export default function RemoteApp({ adminSection, context }) {
  // Bridge this remote's react-i18next to the shell's i18n singleton — see
  // ai-copilot/src/RemoteApp.jsx for the full rationale.
  return (
    <RemoteThemeProvider themeParams={context?.themeParams}>
      <I18nextProvider i18n={sharedI18n}>
        {adminSection === 'SearchSettingsTab' ? <SearchSettingsTab /> : null}
      </I18nextProvider>
    </RemoteThemeProvider>
  );
}
