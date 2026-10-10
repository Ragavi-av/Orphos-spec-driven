import { Box } from '@mui/material';
import { I18nextProvider } from 'react-i18next';
import sharedI18n from 'eip-shell-context/i18n';
import { setTenantTimezone } from 'eip-shell-context/ScheduleEditor';
import RemoteThemeProvider from 'eip-shell-context/RemoteThemeProvider';
import UpcomingMeetings from './UpcomingMeetings';
import MeetingsRail from './MeetingsRail';
import TravelRail from './TravelRail';
import TasksForAction from './TasksForAction';
import NotificationsPanel from './NotificationsPanel';
import EmailConfigTab from './admin/EmailConfigTab';
import MailMonitorTab from './admin/MailMonitorTab';
import EmailAITab from './admin/EmailAITab';
import ChannelConnectorsTab from './admin/ChannelConnectorsTab';
import CommsAITab from './admin/CommsAITab';

/**
 * Entry point exposed as `plugin_email_scanner/RemoteApp`.
 *
 * Two tabs (see PluginManifest.tabs_json for "email-scanner"):
 *   - tabKey="tasks-for-action" → action items inferred from monitored mail,
 *                                 with today's calendar rail beside them
 *   - tabKey="notifications"    → alerts/warnings/information feed
 * Admin sections: EmailConfigTab, MailMonitorTab, EmailAITab,
 * ChannelConnectorsTab (communication channels), CommsAITab (channel AI prompts).
 *
 * `context.setCopilotQuery`/`context.setTrackerCount` are the same
 * shell-owned callbacks described in ai-copilot's RemoteApp.jsx — this used
 * to be plain `onAsk`/`onCount` prop-drilling from shell/src/App.jsx.
 */
export default function RemoteApp(props) {
  // The tenant's chosen time zone (Appearance → Branding) lives at module scope
  // in eip-shell-context/ScheduleEditor, set by the shell's BrandingProvider.
  // That module is NOT reliably shared across the Module Federation boundary —
  // this remote loads its own copy where setTenantTimezone() never ran, so the
  // ScheduleEditor, mail summaries and reminders here would default to the
  // browser zone. Re-publish it from the shell-provided context (same reason
  // dashboardLayout is threaded through context). Both the tab and admin-section
  // render paths pass context.tenantTimezone; '' resets to the browser fallback.
  if (props?.context && 'tenantTimezone' in props.context) {
    setTenantTimezone(props.context.tenantTimezone);
  }
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
  if (adminSection === 'EmailConfigTab') return <EmailConfigTab />;
  if (adminSection === 'MailMonitorTab') return <MailMonitorTab />;
  if (adminSection === 'EmailAITab') return <EmailAITab />;
  if (adminSection === 'ChannelConnectorsTab') return <ChannelConnectorsTab />;
  if (adminSection === 'CommsAITab') return <CommsAITab />;
  // The calendar rail as a standalone slot, for a host that wants to compose it
  // into its own layout the way the copilot side-panel is composed — addressed
  // by position rather than by a PluginManifest tab entry. Kept available now
  // that the rail's own tab (upcoming-meetings) stays withdrawn.
  if (slot === 'meetings-rail') {
    return <MeetingsRail onAsk={context?.setCopilotQuery} />;
  }

  if (tabKey === 'tasks-for-action') {
    return <TasksForActionWithMeetings context={context} />;
  }
  if (tabKey === 'notifications') {
    return (
      <NotificationsPanel
        onAsk={context?.setCopilotQuery}
        onCount={context?.setTrackerCount}
      />
    );
  }
  if (tabKey === 'upcoming-meetings') {
    return <UpcomingMeetings onAsk={context?.setCopilotQuery} />;
  }
  return null;
}

/** Rail width. Fixed rather than proportional so the calendar's times never wrap. */
const RAIL_W = 300;

/**
 * Tasks for Action, with today's calendar and the next trip to its right.
 *
 * The three belong together: what needs doing is read against what the day has
 * already booked and where the week is about to take you, and someone deciding
 * which task to pick up next needs all of it in one glance. The calendar was a
 * tab of its own, then a rail beside the AI Summary tab; with that tab
 * withdrawn, this is where it lives, and travel is the same kind of standing
 * commitment so it shares the column rather than claiming width of its own.
 *
 * The rail column splits top/bottom — meetings above, travel below — and fills
 * the task feed's full height rather than stopping short of it: a column that
 * ended halfway down left a band of empty page beside the tasks, which reads as
 * something that failed to render. On md and up the column stretches to the
 * grid row (i.e. to the task feed) and the two rails divide that height ~1.35:1,
 * meetings taking the larger share because a day holds more meetings than trips.
 *
 * `minmax(min-content, …)` on both rows, not a bare `fr`: when the task feed is
 * shorter than the rails need — a near-empty inbox — the rows fall back to their
 * own content height (each rail's MIN_BODY_H) instead of being squeezed. Each
 * rail scrolls its own body, so a busy day never pushes the other panel down.
 *
 * Below md the layout is one column and the rails go back to content height —
 * stretching there would strand the travel card at the bottom of the viewport,
 * and the rail's fixed width would squeeze the task rows down to nothing.
 */
function TasksForActionWithMeetings({ context }) {
  const onAsk = context?.setCopilotQuery;
  return (
    <Box sx={{
      display: 'grid', gap: '14px', alignItems: { xs: 'start', md: 'stretch' },
      gridTemplateColumns: { xs: '1fr', md: `1.6fr minmax(${RAIL_W}px, 1fr)` },
    }}>
      <Box sx={{ minWidth: 0 }}>
        <TasksForAction onAsk={onAsk} />
      </Box>
      <Box sx={{
        minWidth: 0, display: 'grid', gap: '14px',
        gridTemplateRows: {
          xs: 'auto auto',
          md: 'minmax(min-content, 1.35fr) minmax(min-content, 1fr)',
        },
      }}>
        <MeetingsRail onAsk={onAsk} />
        <TravelRail onAsk={onAsk} />
      </Box>
    </Box>
  );
}
