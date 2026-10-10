import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
// Side-effect import: registers THIS bundle's copy of the country module on the
// cross-bundle broadcast and replays the reader's stored choice into its own
// money/ScheduleEditor singletons. Federation gives the remote separate copies
// of those (the same disconnect that makes dashboardLayout a prop below), so
// without this a board switched to Japan would fall back to the INR default for
// any observation whose own currency the service left blank — ₹ figures written
// on a 万 scale, which is the exact mismatch the country pill exists to remove.
import 'eip-shell-context/country';
import WidgetBoard from './WidgetBoard';
import DashboardDefaultsTab from './admin/DashboardDefaultsTab';

/**
 * Entry point exposed as `plugin_widgets/RemoteApp`.
 *
 * Not tab-based like the other plugins (tabs_json is `[]` in
 * platform-registry-service's PluginManifest seed) — the shell renders this
 * inline above the tab row via slot="top-widgets" whenever the acting user
 * is neither Viewer nor Guest, gated by usePlugin('widgets').
 *
 * `context.setCopilotQuery` carries the "ask the copilot about this metric"
 * signal up to the ai-copilot remote's side panel — see that remote's
 * RemoteApp.jsx for the other end of this contract.
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

function RemoteAppInner({ slot, adminSection, context }) {
  if (adminSection === 'DashboardDefaultsTab') {
    return <DashboardDefaultsTab />;
  }
  if (slot === 'top-widgets') {
    // `context.dashboardLayout` is the host-connected layout (visibleIds/order/
    // save/…). This remote bundles its own copy of eip-shell-context, so its
    // own useDashboardLayout() hook is NOT wired to the shell's provider —
    // relying on it made toggle/drag no-ops. Pass the live value through.
    return <WidgetBoard onAsk={context?.setCopilotQuery} layout={context?.dashboardLayout} />;
  }
  return null;
}
