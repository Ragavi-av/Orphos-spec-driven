import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
import DocSearch from './DocSearch';
import SearchHistory from './SearchHistory';
import DocIngestListTab from './admin/DocIngestListTab';
import DocIngestQueriesTab from './admin/DocIngestQueriesTab';
import DocIngestSettingsTab from './admin/DocIngestSettingsTab';
import IngestionDefaultsTab from './admin/IngestionDefaultsTab';
import DocumentAITab from './admin/DocumentAITab';
import SourceConnectorsTab from './admin/SourceConnectorsTab';

/**
 * Entry point exposed as `plugin_doc_ingest/RemoteApp`.
 *
 * One tab (tabKey="doc-search") plus five admin sections — see
 * platform-registry-service's PluginManifest seed for "doc-ingest".
 */
export default function RemoteApp(props) {
  // Bridge this remote's react-i18next to the shell's i18n singleton — see
  // ai-copilot/src/RemoteApp.jsx for the full rationale.
  return (
    <RemoteThemeProvider themeParams={props?.context?.themeParams}>
      <I18nextProvider i18n={sharedI18n}>
        <RemoteAppInner {...props} />
      </I18nextProvider>
    </RemoteThemeProvider>
  );
}

function RemoteAppInner({ tabKey, slot, adminSection, context }) {
  // Slots the AI Co-pilot dock fills. Document search stopped being a top-level
  // tab when it moved behind the dock's toggle; the component is unchanged, it
  // just renders in a third of the width now (see DocSearch's `compact`).
  if (slot === 'dock-search') {
    return <DocSearch compact initialQuery={context?.docSearchQuery || ''} />;
  }
  if (slot === 'search-history') {
    return <SearchHistory onPick={context?.onReplayDocSearch} />;
  }
  if (adminSection === 'DocIngestListTab') return <DocIngestListTab />;
  if (adminSection === 'DocIngestQueriesTab') return <DocIngestQueriesTab />;
  if (adminSection === 'DocIngestSettingsTab') return <DocIngestSettingsTab />;
  if (adminSection === 'IngestionDefaultsTab') return <IngestionDefaultsTab />;
  if (adminSection === 'DocumentAITab') return <DocumentAITab />;
  if (adminSection === 'SourceConnectorsTab') return <SourceConnectorsTab />;
  if (tabKey === 'doc-search') return <DocSearch />;
  return null;
}
