import { useState } from 'react';
import {
  Box, Typography, Menu, MenuItem, ListSubheader, Divider, IconButton,
  Popover, TextField, Button, Tooltip,
} from '@mui/material';
import MoreVertIcon     from '@mui/icons-material/MoreVert';
import AutoAwesomeIcon  from '@mui/icons-material/AutoAwesome';
import AccessTimeIcon   from '@mui/icons-material/AccessTime';
import MailOutlineIcon  from '@mui/icons-material/MailOutline';
import GroupsIcon       from '@mui/icons-material/Groups';
import TagIcon          from '@mui/icons-material/Tag';
import HubIcon          from '@mui/icons-material/Hub';
import ForumIcon        from '@mui/icons-material/Forum';
import ChatIcon         from '@mui/icons-material/Chat';
import { useTranslation } from 'react-i18next';
import { SEV_COLOR, fmtDate, reprioritize, setStatus, setReminder,
         setCriticality, nextLowerCriticality, nextHigherCriticality } from './mailApi';
import { tenantTimezone, zonedWallClockToInstant, instantToZonedWallClock }
  from 'eip-shell-context/ScheduleEditor';
import AgentActionDialog from './AgentActionDialog';
import ChannelReplyDialog from './ChannelReplyDialog';

const PRIORITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

// Communication channel / origin an item was scanned from — see
// mail-insight-service's SourceChannel. EMAIL covers every mailbox protocol
// (Microsoft Graph, IMAP for Google/Zoho/other); MS_TEAMS/SLACK are ingested by
// the communication-channel connectors; DISCORD/GOOGLE_CHAT are reserved for
// connectors declared but not yet implemented. DATA is not a channel at all —
// it marks items synthesized from the tenant's live ontology data.
const CHANNEL_META = {
  EMAIL:       { Icon: MailOutlineIcon, color: '#00B0F0', labelKey: 'mailrow.channelEmail',  label: 'From email' },
  MS_TEAMS:    { Icon: GroupsIcon,      color: '#00B0F0', labelKey: 'mailrow.channelTeams',  label: 'From Microsoft Teams' },
  SLACK:       { Icon: TagIcon,         color: '#00B050', labelKey: 'mailrow.channelSlack',  label: 'From Slack' },
  DISCORD:     { Icon: ForumIcon,       color: '#00B0F0', labelKey: 'mailrow.channelDiscord', label: 'From Discord' },
  GOOGLE_CHAT: { Icon: ChatIcon,        color: '#00B050', labelKey: 'mailrow.channelGChat',  label: 'From Google Chat' },
  DATA:        { Icon: HubIcon,         color: '#00B0F0', labelKey: 'mailrow.channelData',   label: 'From data source' },
};

// Chat/collaboration channels whose items support the communications agent
// (draft/send a reply back to the channel) — everything except EMAIL and DATA.
const COMMUNICATION_CHANNELS = ['MS_TEAMS', 'SLACK', 'DISCORD', 'GOOGLE_CHAT'];

function Badge({ label }) {
  const { t } = useTranslation();
  const c = SEV_COLOR[label?.toUpperCase()] || { bg: '#CADCFC', text: '#CADCFC', border: 'rgba(202, 220, 252, 0.18)' };
  return (
    <Box component='span' sx={{
      display: 'inline-block', px: 1, py: 0.2, borderRadius: 1,
      fontSize: 10, fontWeight: 700, letterSpacing: '0.03em',
      bgcolor: c.bg, color: c.text, border: `1px solid ${c.border}`, whiteSpace: 'nowrap',
    }}>{label ? t(`sev.${String(label).toUpperCase()}`, label) : label}</Box>
  );
}

// A single inferred item with its action menu (reprioritize / status / reminder).
// `badge` chooses which field drives the colour chip (priority for tasks,
// criticality for notifications). Mutations call back via onChanged(updatedItem).
export default function MailItemRow({ item, badgeField = 'priority', onAsk, onChanged }) {
  const { t } = useTranslation();
  const [hovered, setHovered] = useState(false);
  const [menuEl, setMenuEl] = useState(null);
  const [reminderEl, setReminderEl] = useState(null);
  const [reminderVal, setReminderVal] = useState('');
  const [busy, setBusy] = useState(false);
  const [agentAction, setAgentAction] = useState(null); // 'REPLY' | 'MEETING' | null
  const [channelReplyOpen, setChannelReplyOpen] = useState(false);

  const badge = badgeField === 'criticality' ? item.criticality : item.priority;
  const due = fmtDate(item.dueDate);
  const channel = CHANNEL_META[item.source] || CHANNEL_META.EMAIL;
  const closeMenu = () => setMenuEl(null);

  const isRealItem = !String(item.id || '').startsWith('ds-');
  // The email-task agent needs a real mail item behind the row — data-source
  // items ("ds-…") are synthesized from ontology data and have no sender.
  const agentAvailable = isRealItem && (item.source == null || item.source === 'EMAIL');
  // Channel items (Slack/Teams/…) use the communications agent instead: a single
  // "draft reply" action that posts back to the originating channel.
  const channelAgentAvailable = isRealItem && COMMUNICATION_CHANNELS.includes(item.source);
  const openAgent = (action) => { setAgentAction(action); setMenuEl(null); };
  const openChannelReply = () => { setChannelReplyOpen(true); setMenuEl(null); };

  // Open the reminder popover anchored to the (stable) kebab button and close the
  // menu first — leaving the Menu open stacks a second modal whose focus trap
  // swallows clicks on the popover's Save button.
  // Seed the picker with any existing reminder, shown as its wall clock in the
  // tenant zone so the value the user sees matches how the reminder renders
  // elsewhere (⏰ fmtDate, also tenant-zone).
  const openReminder = () => {
    setReminderVal(instantToZonedWallClock(item.reminderAt));
    setReminderEl(menuEl);
    setMenuEl(null);
  };

  const act = async (fn) => {
    setBusy(true);
    try {
      const updated = await fn();
      if (updated && onChanged) onChanged(updated);
    } finally {
      setBusy(false);
      closeMenu();
      setReminderEl(null);
    }
  };

  return (
    <Box
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onClick={() => onAsk && onAsk(item)}
      sx={{
        display: 'flex', alignItems: 'flex-start', gap: 1.5,
        px: 2, py: 1.2, bgcolor: hovered ? 'rgba(0, 176, 240, 0.14)' : 'transparent', transition: 'background 0.12s',
        cursor: onAsk ? 'pointer' : 'default',
      }}
    >
      <Box sx={{ pt: '2px', flexShrink: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.6 }}>
        <Badge label={badge} />
        <Tooltip title={t(channel.labelKey, channel.label)} placement='left' arrow>
          <channel.Icon sx={{ fontSize: 14, color: channel.color }} />
        </Tooltip>
      </Box>

      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography fontSize={12} fontWeight={600} noWrap
          sx={{ color: hovered && onAsk ? '#00B0F0' : '#FFFFFF' }}>
          {item.title || t('mailrow.noSubject', '(no subject)')}
        </Typography>
        {item.summary && (
          <Typography fontSize={11} color='text.secondary'
            sx={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
            {item.summary}
          </Typography>
        )}
        <Box sx={{ display: 'flex', gap: 1.5, mt: 0.4, flexWrap: 'wrap' }}>
          {item.senderDisplay && (
            <Typography fontSize={10} color='#9AA5C8'>{t('mailrow.from', { name: item.senderDisplay })}</Typography>
          )}
          {due && (
            <Typography fontSize={10} color='#E8A33D' sx={{ display: 'flex', alignItems: 'center', gap: 0.3 }}>
              <AccessTimeIcon sx={{ fontSize: 11 }} /> {t('mailrow.due', { date: due })}
            </Typography>
          )}
          {item.reminderAt && (
            <Typography fontSize={10} color='#00B0F0'>⏰ {fmtDate(item.reminderAt)}</Typography>
          )}
          {item.status && item.status !== 'OPEN' && (
            <Typography fontSize={10} color='#00B050'>{t(`status.${item.status}`, item.status)}</Typography>
          )}
        </Box>
      </Box>

      {onAsk && (
        <Tooltip title={t('mailrow.askAi', 'Ask AI co-pilot')}>
          <AutoAwesomeIcon sx={{
            fontSize: 14, flexShrink: 0, mt: '3px',
            color: hovered ? '#00B0F0' : '#9AA5C8', transition: 'color 0.12s',
          }} />
        </Tooltip>
      )}

      <IconButton size='small' disabled={busy}
        onClick={e => { e.stopPropagation(); setMenuEl(e.currentTarget); }} sx={{ p: 0.25, mt: '-2px' }}>
        <MoreVertIcon sx={{ fontSize: 16, color: '#9AA5C8' }} />
      </IconButton>

      {/* stopPropagation: MUI renders the menu in a portal whose clicks still
          bubble up the React tree to the row's onClick (= "ask AI"). Without this,
          choosing any menu action (Done, Reprioritize, …) would also fire an AI
          request. Same guard the reminder popover below already uses. */}
      <Menu anchorEl={menuEl} open={!!menuEl} onClose={closeMenu}
        onClick={e => e.stopPropagation()}>
        {item.kind === 'NOTIFICATION' ? [
          // Prioritize → raise the criticality one level (INFORMATION → WARNING → ALERT).
          nextHigherCriticality(item.criticality) && (
            <MenuItem dense key='prio'
              onClick={() => act(() => setCriticality(item.id, nextHigherCriticality(item.criticality)))}>
              {t('mailrow.prioritize', { level: t(`sev.${nextHigherCriticality(item.criticality)}`, nextHigherCriticality(item.criticality)) })}
            </MenuItem>
          ),
          // Deprioritize → lower the criticality one level (ALERT → WARNING → INFORMATION).
          nextLowerCriticality(item.criticality) && (
            <MenuItem dense key='deprio'
              onClick={() => act(() => setCriticality(item.id, nextLowerCriticality(item.criticality)))}>
              {t('mailrow.deprioritize', { level: t(`sev.${nextLowerCriticality(item.criticality)}`, nextLowerCriticality(item.criticality)) })}
            </MenuItem>
          ),
          <MenuItem dense key='done' onClick={() => act(() => setStatus(item.id, 'DONE'))}>{t('mailrow.markDone', 'Mark Complete / Done')}</MenuItem>,
          <MenuItem dense key='ignore' onClick={() => act(() => setStatus(item.id, 'DISMISSED'))}>{t('mailrow.ignore', 'Ignore')}</MenuItem>,
          ...(agentAvailable ? [
            <Divider key='nad' />,
            <MenuItem dense key='nreply' onClick={() => openAgent('REPLY')}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.draftReply', 'AI: Draft reply…')}
            </MenuItem>,
            <MenuItem dense key='nmeet' onClick={() => openAgent('MEETING')}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.scheduleMeeting', 'AI: Schedule meeting…')}
            </MenuItem>,
          ] : []),
          ...(channelAgentAvailable ? [
            <Divider key='ncd' />,
            <MenuItem dense key='ncreply' onClick={openChannelReply}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.draftChannelReply', 'AI: Draft channel reply…')}
            </MenuItem>,
          ] : []),
          <Divider key='nd' />,
          <MenuItem dense key='nrem' onClick={openReminder}>{t('mailrow.remindLater', 'Remind me later…')}</MenuItem>,
          item.reminderAt && (
            <MenuItem dense key='nremclr' onClick={() => act(() => setReminder(item.id, null))}>{t('mailrow.clearReminder', 'Clear reminder')}</MenuItem>
          ),
        ] : [
          <MenuItem dense key='done' onClick={() => act(() => setStatus(item.id, 'DONE'))}>{t('mailrow.markDone', 'Mark Complete / Done')}</MenuItem>,
          <MenuItem dense key='nr' onClick={() => act(() => setStatus(item.id, 'DISMISSED'))}>{t('mailrow.notRequired', 'Not Required')}</MenuItem>,
          <MenuItem dense key='wip' onClick={() => act(() => setStatus(item.id, 'IN_PROGRESS'))}>{t('mailrow.inProgress', 'Mark in progress')}</MenuItem>,
          ...(agentAvailable ? [
            <Divider key='tad' />,
            <MenuItem dense key='treply' onClick={() => openAgent('REPLY')}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.draftReply', 'AI: Draft reply…')}
            </MenuItem>,
            <MenuItem dense key='tmeet' onClick={() => openAgent('MEETING')}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.scheduleMeeting', 'AI: Schedule meeting…')}
            </MenuItem>,
          ] : []),
          ...(channelAgentAvailable ? [
            <Divider key='tcd' />,
            <MenuItem dense key='tcreply' onClick={openChannelReply}>
              <AutoAwesomeIcon sx={{ fontSize: 14, mr: 0.75, color: '#00B0F0' }} /> {t('mailrow.draftChannelReply', 'AI: Draft channel reply…')}
            </MenuItem>,
          ] : []),
          <Divider key='d1' />,
          <ListSubheader key='rp' sx={{ fontSize: 11, lineHeight: '24px', bgcolor: 'transparent' }}>{t('mailrow.reprioritize', 'Reprioritize')}</ListSubheader>,
          ...PRIORITIES.map(p => (
            <MenuItem dense key={p} selected={badge === p}
              onClick={() => act(() => reprioritize(item.id, p))}>{t(`sev.${p}`, p)}</MenuItem>
          )),
          <Divider key='d2' />,
          <MenuItem dense key='rem' onClick={openReminder}>{t('mailrow.setReminder', 'Set reminder…')}</MenuItem>,
          item.reminderAt && (
            <MenuItem dense key='remclr' onClick={() => act(() => setReminder(item.id, null))}>{t('mailrow.clearReminder', 'Clear reminder')}</MenuItem>
          ),
        ]}
      </Menu>

      <Popover open={!!reminderEl} anchorEl={reminderEl} onClose={() => setReminderEl(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}>
        {/* Stop clicks inside the popover from bubbling (via the portal) to the
            row's onAsk handler. */}
        <Box onClick={e => e.stopPropagation()}
          sx={{ p: 1.5, display: 'flex', flexDirection: 'column', gap: 1, width: 240 }}>
          <Typography fontSize={12} fontWeight={600}>{t('mailrow.remindAt', 'Remind me at')}</Typography>
          <TextField type='datetime-local' size='small' value={reminderVal}
            onChange={e => setReminderVal(e.target.value)} sx={{ '& input': { fontSize: 12 } }} />
          <Typography fontSize={10} color='#9AA5C8'>
            {t('mailrow.reminderTz', 'Time zone: {{tz}}', { tz: tenantTimezone() })}
          </Typography>
          <Button size='small' variant='contained' disabled={!reminderVal}
            onClick={() => act(() => setReminder(item.id, zonedWallClockToInstant(reminderVal)))}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('mailrow.saveReminder', 'Save reminder')}</Button>
        </Box>
      </Popover>

      {/* Email-task agent: draft with AI, review, send via the tenant's SMTP
          config. Sending marks the item IN_PROGRESS server-side, so refresh
          the row through the usual onChanged path. */}
      <AgentActionDialog
        item={item}
        action={agentAction || 'REPLY'}
        open={!!agentAction}
        onClose={() => setAgentAction(null)}
        onDone={() => onChanged && onChanged({ ...item, status: 'IN_PROGRESS' })}
      />

      {/* Communications agent: draft a reply and post it back to the channel
          (Slack/Teams/…) the item came from. */}
      <ChannelReplyDialog
        item={item}
        open={channelReplyOpen}
        onClose={() => setChannelReplyOpen(false)}
        onDone={() => onChanged && onChanged({ ...item, status: 'IN_PROGRESS' })}
      />
    </Box>
  );
}
