import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
// Side-effect import: puts the reader's country choice into THIS bundle's zone
// singleton, and keeps it there on a later switch. api.js formats every decision
// date through eip-shell-context/datetime, which reads that singleton — and
// unlike email-scanner this remote is handed no tenantTimezone to republish, so
// its dates would otherwise stay on the viewer's browser zone.
import 'eip-shell-context/country';
import DecisionLog from './DecisionLog';

/**
 * Entry point exposed as `plugin_decision_log/RemoteApp` — the contract
 * shell/src/PluginLoader.jsx already renders every plugin against:
 * `<RemoteApp tabKey={tabKey} slot={slot} context={context} />`.
 *
 * DecisionLog itself doesn't need shell context yet (it calls the public
 * `/api/decisions` endpoint directly via plain axios, proxied by nginx) —
 * `context` is accepted and threaded through so future changes here don't
 * need to touch the federation boundary again.
 */
export default function RemoteApp({ tabKey, slot, context }) {
  // Bridge this remote's react-i18next to the shell's i18n singleton — see
  // ai-copilot/src/RemoteApp.jsx for the full rationale.
  return (
    <RemoteThemeProvider themeParams={context?.themeParams}>
      <I18nextProvider i18n={sharedI18n}>
        <DecisionLog tabKey={tabKey} slot={slot} context={context} />
      </I18nextProvider>
    </RemoteThemeProvider>
  );
}
