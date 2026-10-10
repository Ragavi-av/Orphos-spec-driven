import { useEffect, useState } from 'react';
import { Box, Typography, Chip } from '@mui/material';
import EventNoteIcon from '@mui/icons-material/EventNote';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { useTranslation } from 'react-i18next';
import { fetchTodaysMeetings, nextUpcoming, startOf, fmtTime } from 'eip-shell-context/meetings';

/**
 * Calendar highlight strip — what is on today and what is next.
 *
 * Sits at the top of the mail-derived tabs, where the mailbox-monitoring banner
 * used to dominate. That banner reports plumbing ("we are watching this inbox"),
 * which matters once during setup; this reports the thing an executive actually
 * needs at a glance. Clicking it opens the Upcoming Meetings tab.
 *
 * Renders nothing when the day is empty, so a tenant with no calendar connected
 * sees no dead furniture — the strip only appears when it has something to say.
 */
export default function MeetingsHighlight({ onOpen }) {
  const { t } = useTranslation();
  const [items, setItems] = useState([]);

  useEffect(() => {
    let cancelled = false;
    fetchTodaysMeetings()
      .then(rows => { if (!cancelled) setItems(rows); })
      .catch(() => { if (!cancelled) setItems([]); });
    return () => { cancelled = true; };
  }, []);

  if (items.length === 0) return null;

  const next = nextUpcoming(items);
  const clickable = typeof onOpen === 'function';

  return (
    <Box
      onClick={clickable ? onOpen : undefined}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onKeyDown={clickable ? (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(); }
      } : undefined}
      sx={{
        display: 'flex', alignItems: 'center', gap: 1.5, mb: 2,
        px: 2, py: 1.25, borderRadius: 2,
        bgcolor: '#0F2450', border: '1px solid rgba(91, 157, 255, 0.42)',
        cursor: clickable ? 'pointer' : 'default',
        transition: 'background-color .15s, border-color .15s',
        ...(clickable && {
          '&:hover': { bgcolor: '#2C3868', borderColor: '#8DBCFF' },
          '&:focus-visible': { outline: '2px solid #8DBCFF', outlineOffset: 2 },
        }),
      }}
    >
      <EventNoteIcon sx={{ fontSize: 18, color: '#8DBCFF', flexShrink: 0 }} />

      <Typography fontSize={13} fontWeight={700} color='#FFFFFF' sx={{ flexShrink: 0 }}>
        {t('meetings.count', '{{n}} today', { n: items.length })}
      </Typography>

      {next ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
          <Chip size='small' label={fmtTime(startOf(next))}
            sx={{ fontSize: 10, fontWeight: 700, height: 20,
                  bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
          <Typography fontSize={13} color='#C3D3F2' noWrap>{next.title}</Typography>
        </Box>
      ) : (
        <Typography fontSize={13} color='#7D92C4'>
          {t('meetings.allDone', 'Nothing further today')}
        </Typography>
      )}

      <Box sx={{ flex: 1 }} />
      {clickable && (
        <Typography fontSize={12} fontWeight={700}
          sx={{ color: '#8DBCFF', display: 'flex', alignItems: 'center', flexShrink: 0 }}>
          {t('meetings.viewAll', 'View calendar')}
          <ChevronRightIcon sx={{ fontSize: 16 }} />
        </Typography>
      )}
    </Box>
  );
}
