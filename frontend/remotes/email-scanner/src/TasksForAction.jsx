import { useEffect, useState, useCallback } from 'react';
import {
  Box, Paper, Typography, CircularProgress, Divider, ToggleButtonGroup,
  ToggleButton, Chip, Button, Tooltip,
} from '@mui/material';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import RefreshIcon          from '@mui/icons-material/Refresh';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import MailItemRow from './MailInsights/MailItemRow';
import MyMailboxBanner from './MailInsights/MyMailboxBanner';
import ApprovalActions from './ApprovalActions';
import { scopeFeed, FEED_CAP } from 'eip-shell-context/feedScope';

// "Tasks & Approvals" — action items inferred from mail/calendar, sortable by
// priority, criticality, or due date, with per-item reprioritize / done /
// reminder actions. Data comes from mail-insight-service — the same list the
// mobile app's Tasks screen reads. Approvals come from mobile-bff's approval
// store, joined by task id (approval.sourceRef === task.id), so a task needing
// approval here is the one the mobile Approvals screen shows.
export default function TasksForAction({ onAsk }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [items, setItems]     = useState([]);
  const [approvalByTask, setApprovalByTask] = useState({});
  const [sort, setSort]       = useState('priority');
  const [error, setError]     = useState(null);
  const [failed, setFailed]   = useState(false);
  // The approval overlay failed while tasks loaded: say so, or a task that needs a
  // decision looks like one that does not (the mobile app would still show it).
  const [approvalsFailed, setApprovalsFailed] = useState(false);
  // Default to the rows that need attention rather than every open task in the
  // tenant. 'all' is one click away and still capped.
  const [scope, setScope]     = useState('attention');
  const [expanded, setExpanded] = useState(false);

  const load = useCallback(async (sortKey) => {
    setLoading(true);
    setError(null);
    setFailed(false);
    setApprovalsFailed(false);
    // Approvals are an overlay: if the BFF is down the task list still renders,
    // without approve/reject controls and with a notice that they are missing.
    const approvalsReq = axios.get('/api/mobile/approvals').catch(() => null);
    try {
      const r = await axios.get('/api/mail-insight/tasks', { params: { sort: sortKey } });
      setItems(r.data.result || []);
      const a = await approvalsReq;
      setApprovalsFailed(a === null);
      const byTask = {};
      for (const ap of (Array.isArray(a?.data) ? a.data : [])) {
        if (ap.sourceRef && ap.status === 'PENDING') byTask[ap.sourceRef] = ap;
      }
      setApprovalByTask(byTask);
    } catch (e) {
      // null → render the localized default message at display time.
      setError(e?.response?.data?.error || null);
      setItems([]);
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(sort); }, [sort, load]);

  // Re-fetch after any action so completed/ignored items drop out and
  // reprioritized ones re-sort (works uniformly for email + data-source rows).
  const onChanged = () => load(sort);

  // Scope + cap. `feed.total` keeps the tenant-wide figure available so the UI
  // can say what it is hiding instead of silently truncating.
  // A task awaiting the reader's approval always needs attention, whatever its priority.
  const feed = scopeFeed(items, {
    scope, cap: expanded ? 0 : FEED_CAP, include: it => Boolean(approvalByTask[it.id]),
  });

  return (
    <Box>
      <MyMailboxBanner onChanged={() => load(sort)} />

      {/* Header strip */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2, flexWrap: 'wrap' }}>
        <PlaylistAddCheckIcon sx={{ fontSize: 18, color: '#CADCFC' }} />
        <Typography fontSize={13} fontWeight={700} color='#FFFFFF'>{t('tasks.title', 'Tasks & Approvals')}</Typography>
        {/* Count reflects what is in scope, not the tenant-wide total — the old
            chip said "103 open" for every open task in the organisation. */}
        <Chip size='small' label={loading ? '…' : t('tasks.open', { n: feed.matched })}
          sx={{ fontSize: 10, fontWeight: 700,
                bgcolor: feed.matched ? 'rgba(255, 0, 0, 0.14)' : '#1A2247',
                color: feed.matched ? '#FF6B6B' : '#9AA5C8' }} />

        <ToggleButtonGroup size='small' exclusive value={scope}
          onChange={(_, v) => { if (v) { setScope(v); setExpanded(false); } }}>
          <ToggleButton value='attention' sx={{ fontSize: 10, textTransform: 'none', px: 1.2 }}>
            {t('tasks.scope.attention', 'Needs attention')}
          </ToggleButton>
          <ToggleButton value='all' sx={{ fontSize: 10, textTransform: 'none', px: 1.2 }}>
            {t('tasks.scope.all', 'All')}
          </ToggleButton>
        </ToggleButtonGroup>

        <Box sx={{ flex: 1 }} />

        <Typography fontSize={11} color='#9AA5C8'>{t('tasks.sortBy', 'Sort by')}</Typography>
        <ToggleButtonGroup size='small' exclusive value={sort}
          onChange={(_, v) => v && setSort(v)}>
          <ToggleButton value='priority' sx={{ fontSize: 10, textTransform: 'none', px: 1.2 }}>{t('tasks.priority', 'Priority')}</ToggleButton>
          <ToggleButton value='dueDate'   sx={{ fontSize: 10, textTransform: 'none', px: 1.2 }}>{t('tasks.dueDate', 'Due date')}</ToggleButton>
        </ToggleButtonGroup>
        <Tooltip title={t('tasks.refresh', 'Refresh')}>
          <Button size='small' onClick={() => load(sort)} sx={{ minWidth: 0, px: 1 }}>
            <RefreshIcon sx={{ fontSize: 16 }} />
          </Button>
        </Tooltip>
      </Box>

      {!loading && !failed && approvalsFailed && (
        <Typography fontSize={11} color='#FFB84D' sx={{ mb: 1 }}>
          {t('tasks.approvalsUnavailable', 'Approvals could not be loaded — tasks awaiting your decision are not marked. Refresh to retry.')}
        </Typography>
      )}
      <Paper elevation={0} sx={{ border: '1px solid rgba(202, 220, 252, 0.18)', overflow: 'hidden' }}>
        {loading ? (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 2 }}>
            <CircularProgress size={14} />
            <Typography fontSize={11} color='text.secondary'>{t('tasks.loading', 'Loading tasks…')}</Typography>
          </Box>
        ) : failed ? (
          <Box sx={{ px: 2, py: 2 }}>
            <Typography fontSize={12} color='#FF6B6B'>{error || t('tasks.loadError', 'Could not load tasks.')}</Typography>
          </Box>
        ) : feed.matched === 0 ? (
          <Box sx={{ px: 2, py: 3, textAlign: 'center' }}>
            <Typography fontSize={12} color='text.secondary'>
              {items.length > 0
                ? t('tasks.noneNeedAttention',
                    'Nothing needs your attention. {{n}} lower-priority items are open — switch to All to see them.',
                    { n: items.length })
                : t('tasks.empty', 'No action items yet. Configure a mailbox under Administration → Mailbox Monitor and run a scan.')}
            </Typography>
          </Box>
        ) : (
          <>
            {feed.rows.map((item, i) => (
              <Box key={item.id}>
                {i > 0 && <Divider />}
                <MailItemRow item={item} badgeField='priority'
                  onChanged={onChanged}
                  onAsk={onAsk ? (it) => onAsk({
                    label: t('tasks.title', 'Tasks & Approvals'),
                    query: `Summarise this action item and recommend next steps: "${it.title}". `
                         + `Priority ${it.priority}. ${it.summary || ''}`,
                  }) : null} />
                {approvalByTask[item.id] && (
                  <ApprovalActions approval={approvalByTask[item.id]} onDecided={onChanged} />
                )}
              </Box>
            ))}

            {/* Never truncate silently — state the count that is not shown. */}
            {feed.hidden > 0 && (
              <>
                <Divider />
                <Box sx={{ px: 2, py: 1.25, display: 'flex', alignItems: 'center', gap: 1 }}>
                  <Typography fontSize={11} color='#9AA5C8'>
                    {t('tasks.showingOf', 'Showing {{shown}} of {{matched}}', {
                      shown: feed.shown, matched: feed.matched })}
                    {scope === 'attention' && feed.total > feed.matched
                      && ` · ${t('tasks.lowerPriority', '{{n}} lower-priority open', {
                        n: feed.total - feed.matched })}`}
                  </Typography>
                  <Button size='small' onClick={() => setExpanded(true)}
                    sx={{ fontSize: 11, textTransform: 'none', py: 0, minWidth: 0 }}>
                    {t('tasks.showAll', 'Show all')}
                  </Button>
                </Box>
              </>
            )}
          </>
        )}
      </Paper>
    </Box>
  );
}
