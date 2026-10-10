import { useEffect, useState, useCallback } from 'react';
import {
  Box, Paper, Typography, CircularProgress, Divider, Chip, Button, Tooltip,
} from '@mui/material';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import ErrorOutlineIcon        from '@mui/icons-material/ErrorOutline';
import WarningAmberIcon        from '@mui/icons-material/WarningAmber';
import InfoOutlinedIcon        from '@mui/icons-material/InfoOutlined';
import RefreshIcon             from '@mui/icons-material/Refresh';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import MailItemRow from './MailInsights/MailItemRow';
import MyMailboxBanner from './MailInsights/MyMailboxBanner';
import { scopeFeed, FEED_CAP } from 'eip-shell-context/feedScope';

const LEVELS = [
  { key: 'ALERT',       titleKey: 'notif.alerts',      title: 'Alerts',      icon: ErrorOutlineIcon, color: '#FF6B6B', emptyKey: 'notif.noAlerts',      empty: 'No alerts.' },
  { key: 'WARNING',     titleKey: 'notif.warnings',    title: 'Warnings!',   icon: WarningAmberIcon, color: '#E8A33D', emptyKey: 'notif.noWarnings',    empty: 'No warnings.' },
  { key: 'INFORMATION', titleKey: 'notif.information', title: 'Information', icon: InfoOutlinedIcon, color: '#00B0F0', emptyKey: 'notif.nothingToNote', empty: 'Nothing to note.' },
];

function Section({ def, items, loading, onChanged, onAsk }) {
  const { t } = useTranslation();
  const Icon = def.icon;
  const title = t(def.titleKey, def.title);
  // The level IS the scope here, so only the cap applies. Information in
  // particular ran to three figures and buried the Alerts section below it.
  const [expanded, setExpanded] = useState(false);
  const feed = scopeFeed(items, { scope: 'all', cap: expanded ? 0 : FEED_CAP });
  return (
    <Paper elevation={0} sx={{ border: '1px solid rgba(202, 220, 252, 0.18)', overflow: 'hidden' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.2, bgcolor: '#1E2761',
                 borderBottom: items.length ? '1px solid rgba(202, 220, 252, 0.18)' : 'none' }}>
        <Icon sx={{ fontSize: 16, color: def.color }} />
        <Typography fontSize={13} fontWeight={700} color='#FFFFFF'>{title}</Typography>
        <Box sx={{ ml: 0.5, px: 0.9, py: 0.1, borderRadius: '99px',
                   bgcolor: items.length ? def.color + '2e' : 'rgba(202, 220, 252, 0.18)',
                   border: `1px solid ${items.length ? def.color + '44' : '#9AA5C8'}` }}>
          <Typography fontSize={10} fontWeight={700} sx={{ color: items.length ? def.color : '#9AA5C8' }}>
            {loading ? '…' : items.length}
          </Typography>
        </Box>
      </Box>
      {loading ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.5 }}>
          <CircularProgress size={14} /><Typography fontSize={11} color='text.secondary'>{t('notif.loading', 'Loading…')}</Typography>
        </Box>
      ) : items.length === 0 ? (
        <Box sx={{ px: 2, py: 1.5 }}><Typography fontSize={11} color='text.secondary'>{t(def.emptyKey, def.empty)}</Typography></Box>
      ) : (
        <>
          {feed.rows.map((item, i) => (
            <Box key={item.id}>
              {i > 0 && <Divider />}
              <MailItemRow item={item} badgeField='criticality' onChanged={onChanged}
                onAsk={onAsk ? (it) => onAsk({
                  label: title,
                  query: `Explain this ${def.key.toLowerCase()} notification and its business impact: "${it.title}". ${it.summary || ''}`,
                }) : null} />
            </Box>
          ))}
          {feed.hidden > 0 && (
            <>
              <Divider />
              <Box sx={{ px: 2, py: 1, display: 'flex', alignItems: 'center', gap: 1 }}>
                <Typography fontSize={11} color='#9AA5C8'>
                  {t('notif.showingOf', 'Showing {{shown}} of {{total}}', {
                    shown: feed.shown, total: feed.total })}
                </Typography>
                <Button size='small' onClick={() => setExpanded(true)}
                  sx={{ fontSize: 11, textTransform: 'none', py: 0, minWidth: 0 }}>
                  {t('notif.showAll', 'Show all')}
                </Button>
              </Box>
            </>
          )}
        </>
      )}
    </Paper>
  );
}

// "Notifications" — items inferred from mail/calendar, grouped by criticality
// (Alert / Warning! / Information). Reports the alert+warning count up via
// onCount so the header bell + tab badge stay in sync.
export default function NotificationsPanel({ onAsk, onCount }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [items, setItems]     = useState([]);
  const [error, setError]     = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await axios.get('/api/mail-insight/notifications');
      setItems(r.data.result || []);
    } catch (e) {
      setError(e?.response?.data?.error || 'notif.loadError');
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const byLevel = (lvl) => items.filter(i => (i.criticality || '').toUpperCase() === lvl);

  // Surface alert+warning count to the parent (bell/tab badge).
  useEffect(() => {
    if (!onCount) return;
    const urgent = items.filter(i => ['ALERT', 'WARNING'].includes((i.criticality || '').toUpperCase()));
    onCount(urgent.length);
  }, [items, onCount]);

  // Re-fetch after any action so completed/ignored items drop out and
  // deprioritized ones move to the right section (email + data-source alike).
  const onChanged = () => load();

  return (
    <Box>
      <MyMailboxBanner onChanged={load} />

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2, flexWrap: 'wrap' }}>
        <NotificationsActiveIcon sx={{ fontSize: 18, color: '#CADCFC' }} />
        <Typography fontSize={13} fontWeight={700} color='#FFFFFF'>{t('notif.title', 'Notifications')}</Typography>
        <Chip size='small' label={loading ? '…' : t('notif.active', { n: items.length })}
          sx={{ fontSize: 10, fontWeight: 700,
                bgcolor: items.length ? 'rgba(255, 0, 0, 0.14)' : '#1A2247',
                color: items.length ? '#FF6B6B' : '#9AA5C8' }} />
        <Box sx={{ flex: 1 }} />
        <Tooltip title={t('tasks.refresh', 'Refresh')}>
          <Button size='small' onClick={load} sx={{ minWidth: 0, px: 1 }}>
            <RefreshIcon sx={{ fontSize: 16 }} />
          </Button>
        </Tooltip>
      </Box>

      {error && <Typography fontSize={12} color='#FF6B6B' sx={{ mb: 1 }}>
        {error === 'notif.loadError' ? t('notif.loadError', 'Could not load notifications.') : error}
      </Typography>}

      <Box sx={{ display: 'grid', gridTemplateColumns: '1fr', gap: 2 }}>
        {LEVELS.map(def => (
          <Section key={def.key} def={def} items={byLevel(def.key)} loading={loading}
            onChanged={onChanged} onAsk={onAsk} />
        ))}
      </Box>
    </Box>
  );
}
