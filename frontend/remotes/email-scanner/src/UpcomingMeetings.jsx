import { useEffect, useState, useCallback } from 'react';
import {
  Box, Paper, Typography, CircularProgress, Divider, Chip, Button, Tooltip,
} from '@mui/material';
import EventNoteIcon    from '@mui/icons-material/EventNote';
import RefreshIcon      from '@mui/icons-material/Refresh';
import AutoAwesomeIcon  from '@mui/icons-material/AutoAwesome';
import PersonOutlineIcon from '@mui/icons-material/PersonOutline';
import { useTranslation } from 'react-i18next';
import MyMailboxBanner from './MailInsights/MyMailboxBanner';
import { fetchTodaysMeetings, startOf, fmtTime, briefingPrompt } from 'eip-shell-context/meetings';

// "Upcoming Meetings" — today's calendar from the monitored mailbox, with an
// AI briefing action per meeting. Sits alongside Tasks for Action and
// Notifications as a peer tab rather than a dashboard widget: a list of things
// with times, titles and per-row actions is a feed, and it reads like one.
export default function UpcomingMeetings({ onAsk }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [items, setItems]     = useState([]);
  const [failed, setFailed]   = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setFailed(false);
    try {
      setItems(await fetchTodaysMeetings());
    } catch {
      setItems([]); setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const now = Date.now();

  return (
    <Box>
      <MyMailboxBanner onChanged={load} />

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2, flexWrap: 'wrap' }}>
        <EventNoteIcon sx={{ fontSize: 18, color: '#C3D3F2' }} />
        <Typography fontSize={13} fontWeight={700} color='#FFFFFF'>
          {t('meetings.title', 'Upcoming Meetings')}
        </Typography>
        <Chip size='small' label={loading ? '…' : t('meetings.count', '{{n}} today', { n: items.length })}
          sx={{ fontSize: 10, fontWeight: 700,
                bgcolor: items.length ? 'rgba(91, 157, 255, 0.14)' : '#0F2450',
                color: items.length ? '#8DBCFF' : '#7D92C4' }} />
        <Box sx={{ flex: 1 }} />
        <Tooltip title={t('tasks.refresh', 'Refresh')}>
          <Button size='small' onClick={load} sx={{ minWidth: 0, px: 1 }}>
            <RefreshIcon sx={{ fontSize: 16 }} />
          </Button>
        </Tooltip>
      </Box>

      <Paper elevation={0} sx={{ border: '1px solid #243A72', overflow: 'hidden' }}>
        {loading ? (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 2 }}>
            <CircularProgress size={14} />
            <Typography fontSize={11} color='text.secondary'>{t('meetings.loading', 'Loading calendar…')}</Typography>
          </Box>
        ) : failed ? (
          <Box sx={{ px: 2, py: 2 }}>
            <Typography fontSize={12} color='#FF6B81'>
              {t('meetings.loadError', 'Could not load your calendar.')}
            </Typography>
          </Box>
        ) : items.length === 0 ? (
          <Box sx={{ px: 2, py: 3, textAlign: 'center' }}>
            <Typography fontSize={12} color='text.secondary'>
              {t('meetings.empty', 'Nothing scheduled today. Calendar comes from a Microsoft 365 mailbox — IMAP mailboxes cannot supply it.')}
            </Typography>
          </Box>
        ) : (
          items.map((m, i) => {
            const start = startOf(m);
            const past = Date.parse(start) < now;
            return (
              <Box key={m.id || i}>
                {i > 0 && <Divider />}
                {/* Meetings already finished stay listed — the day's shape matters —
                    but dim, so the eye lands on what is still ahead. */}
                <Box sx={{ display: 'flex', gap: 2, px: 2, py: 1.5, opacity: past ? 0.55 : 1 }}>
                  <Typography fontSize={13} fontWeight={700}
                    sx={{ color: past ? '#7D92C4' : '#8DBCFF', minWidth: 78, flexShrink: 0 }}>
                    {fmtTime(start)}
                  </Typography>

                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography fontSize={13} fontWeight={700} color='#FFFFFF'>
                      {m.title || t('meetings.untitled', 'Untitled meeting')}
                    </Typography>
                    {m.summary && (
                      <Typography fontSize={12} color='#C3D3F2' sx={{ mt: 0.25 }}>
                        {m.summary}
                      </Typography>
                    )}
                    {m.senderDisplay && (
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.5 }}>
                        <PersonOutlineIcon sx={{ fontSize: 13, color: '#7D92C4' }} />
                        <Typography fontSize={11} color='#7D92C4'>{m.senderDisplay}</Typography>
                      </Box>
                    )}
                  </Box>

                  {onAsk && (
                    <Tooltip title={t('meetings.brief', 'Brief me on this meeting')}>
                      <Button
                        size='small'
                        onClick={() => onAsk({
                          label: t('meetings.title', 'Upcoming Meetings'),
                          query: briefingPrompt(m),
                        })}
                        startIcon={<AutoAwesomeIcon sx={{ fontSize: 14 }} />}
                        sx={{ alignSelf: 'flex-start', fontSize: 11, textTransform: 'none',
                              color: '#8DBCFF', flexShrink: 0 }}
                      >
                        {t('meetings.briefShort', 'Brief me')}
                      </Button>
                    </Tooltip>
                  )}
                </Box>
              </Box>
            );
          })
        )}
      </Paper>
    </Box>
  );
}
