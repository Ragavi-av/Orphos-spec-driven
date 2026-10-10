import { useEffect, useState, useCallback } from 'react';
import { Box, Typography, CircularProgress, Button, Tooltip, ButtonBase } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import EventAvailableIcon from '@mui/icons-material/EventAvailable';
import EventBusyIcon from '@mui/icons-material/EventBusy';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { useTranslation } from 'react-i18next';
import { ORPHOS_TOKENS } from 'eip-shell-context/orphosTheme';
import { startOf, fmtTime, briefingPrompt, nextUpcoming, fetchTodaysMeetings } from 'eip-shell-context/meetings';

/** Body height held in every state, so the panel never collapses or jumps. */
const MIN_BODY_H = 190;

// Today's meetings come from the shared fetchTodaysMeetings() — the tenant's
// day, midnight to midnight, the same window the greeting band and the mobile
// app ask for. No sample fallback: an empty rail means a free day, and a failed
// load shows the error state, so the rail only ever shows the tenant's own data.


/**
 * "Upcoming meetings" rail — the narrow companion to the AI Summary tab.
 *
 * Shares its data helpers with the full UpcomingMeetings view (meetings.js) so
 * the two can never disagree about what is on the calendar; only presentation
 * differs. The mailbox banner and the per-row organiser line are dropped here —
 * at rail width they push the times off the card.
 *
 * The panel fills the height its host gives it and holds that height in every
 * state. Only the meeting list scrolls; the header stays put.
 *
 * @param {function} [onAsk] hands a briefing question to the AI Co-pilot
 */
export default function MeetingsRail({ onAsk }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState([]);
  const [failed, setFailed] = useState(false);

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
  const next = nextUpcoming(items);
  // Minutes until the next meeting starts — the rail's headline fact. Null once
  // everything in the window is behind us.
  const minutesToNext = next
    ? Math.max(0, Math.round((Date.parse(startOf(next)) - now) / 60000))
    : null;

  const centred = {
    display: 'flex', flexDirection: 'column', alignItems: 'center',
    justifyContent: 'center', gap: 0.75, minHeight: MIN_BODY_H,
    px: 2, textAlign: 'center', height: '100%',
  };

  return (
    <Box sx={{
      border: `1px solid ${ORPHOS_TOKENS.line.subtle}`, borderRadius: '12px',
      bgcolor: ORPHOS_TOKENS.bg.deep, overflow: 'hidden',
      px: '13px', pt: '12px', pb: 0,
      height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column',
    }}>
      {/* ── Header: label + countdown ── */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexShrink: 0 }}>
        <Typography fontSize={10.5} fontWeight={600} color={ORPHOS_TOKENS.text.muted} noWrap
          sx={{ textTransform: 'uppercase', letterSpacing: 0.9 }}>
          {t('meetings.upcoming', 'Upcoming meetings')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {minutesToNext !== null && (
          // Amber, not the interactive blue: how soon the next meeting starts
          // is a deadline, and the design reserves blue for things you click.
          <Typography fontSize={10.5} color={ORPHOS_TOKENS.status.warning.fg} noWrap>
            {t('meetings.inMinutes', 'in {{n}} min', { n: minutesToNext })}
          </Typography>
        )}
        <Tooltip title={t('tasks.refresh', 'Refresh')}>
          <Button size="small" onClick={load}
            sx={{ minWidth: 0, px: 0.5, color: ORPHOS_TOKENS.text.muted }}>
            <RefreshIcon sx={{ fontSize: 14 }} />
          </Button>
        </Tooltip>
      </Box>

      {onAsk && items.length > 0 && (
        <Typography fontSize={10.2} color={ORPHOS_TOKENS.text.hint} sx={{ mt: '2px', mb: '5px' }}>
          {t('meetings.railHint', 'click a meeting for its own brief')}
        </Typography>
      )}

      {/* ── Body: the only scrolling region ── */}
      <Box sx={{ flex: 1, minHeight: MIN_BODY_H, overflowY: 'auto', overflowX: 'hidden' }}>
        {loading ? (
          <Box sx={centred}>
            <CircularProgress size={16} />
            <Typography fontSize={11} color={ORPHOS_TOKENS.text.muted}>
              {t('meetings.loading', 'Loading calendar…')}
            </Typography>
          </Box>
        ) : failed ? (
          <Box sx={centred}>
            <EventBusyIcon sx={{ fontSize: 26, color: ORPHOS_TOKENS.status.critical.graphic }} />
            <Typography fontSize={11.5} color={ORPHOS_TOKENS.status.critical.fg}>
              {t('meetings.loadError', 'Could not load your calendar.')}
            </Typography>
            <Button size="small" onClick={load}
              sx={{ fontSize: 10.5, textTransform: 'none', color: ORPHOS_TOKENS.text.link }}>
              {t('tasks.refresh', 'Refresh')}
            </Button>
          </Box>
        ) : items.length === 0 ? (
          <Box sx={centred}>
            <EventAvailableIcon sx={{ fontSize: 26, color: ORPHOS_TOKENS.text.muted }} />
            <Typography fontSize={12} fontWeight={600} color={ORPHOS_TOKENS.text.secondary}>
              {t('meetings.emptyToday', 'Nothing scheduled today')}
            </Typography>
            <Typography fontSize={10.5} color={ORPHOS_TOKENS.text.muted}>
              {t('meetings.emptyHint', 'Meetings appear here from your connected mailbox.')}
            </Typography>
          </Box>
        ) : (
          items.map((m, i) => {
            const start = startOf(m);
            const past = Date.parse(start) < now;
            const isNext = next && (m.id ? m.id === next.id : m === next);
            // The leading bar carries where the meeting sits in the day: done,
            // the one you are about to walk into, or still ahead.
            const bar = past ? ORPHOS_TOKENS.text.hint
              : isNext ? ORPHOS_TOKENS.status.warning.graphic
              : ORPHOS_TOKENS.status.info.graphic;
            const askable = Boolean(onAsk);
            return (
              // The whole row is the briefing button — the design drops the
              // per-row "Brief me" link, which at rail width was wider than the
              // meeting title it belonged to.
              <ButtonBase
                key={m.id || i}
                component={askable ? 'button' : 'div'}
                disabled={!askable}
                onClick={askable ? () => onAsk({
                  label: t('meetings.title', 'Upcoming Meetings'),
                  query: briefingPrompt(m),
                }) : undefined}
                title={askable ? t('meetings.briefShort', 'Brief me') : undefined}
                sx={{
                  display: 'flex', alignItems: 'center', gap: '9px',
                  width: '100%', textAlign: 'left', borderRadius: '6px',
                  py: '7px', pr: '4px',
                  borderBottom: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
                  // Finished meetings stay listed — the day's shape matters —
                  // but dim, so the eye lands on what is still ahead.
                  opacity: past ? 0.55 : 1,
                  '&:hover': { bgcolor: ORPHOS_TOKENS.bg.surface },
                }}
              >
                <Box sx={{ width: 3, alignSelf: 'stretch', flex: '0 0 3px',
                           borderRadius: '3px', bgcolor: bar }} />
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  {/* Time alone: every row is today, so a date would repeat
                      the panel's own title on every line. */}
                  <Typography fontSize={11.5} color={ORPHOS_TOKENS.text.bright}
                    sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {fmtTime(start)}
                  </Typography>
                  <Typography fontSize={12} fontWeight={600} color={ORPHOS_TOKENS.text.primary}
                    sx={{ mt: '1px', textWrap: 'pretty' }}>
                    {m.title || t('meetings.untitled', 'Untitled meeting')}
                  </Typography>
                  {m.summary && (
                    <Typography fontSize={10.8} color={ORPHOS_TOKENS.text.hint} sx={{ mt: '2px' }}>
                      {m.summary}
                    </Typography>
                  )}
                </Box>
                {askable && (
                  <ChevronRightIcon sx={{ fontSize: 15, color: ORPHOS_TOKENS.text.hint, flexShrink: 0 }} />
                )}
              </ButtonBase>
            );
          })
        )}
      </Box>
    </Box>
  );
}
