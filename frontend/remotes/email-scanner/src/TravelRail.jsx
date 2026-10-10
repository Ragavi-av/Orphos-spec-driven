import { useEffect, useState, useCallback } from 'react';
import { Box, Typography, CircularProgress, Button, Tooltip, ButtonBase } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import FlightTakeoffIcon from '@mui/icons-material/FlightTakeoff';
import DirectionsRailwayIcon from '@mui/icons-material/DirectionsRailway';
import LocalTaxiIcon from '@mui/icons-material/LocalTaxi';
import LuggageIcon from '@mui/icons-material/Luggage';
import ExploreOffIcon from '@mui/icons-material/ExploreOff';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { ORPHOS_TOKENS } from 'eip-shell-context/orphosTheme';
import { formatDate, formatTime, tenantDayStart } from 'eip-shell-context/datetime';

/** Body height held in every state, so the panel never collapses or jumps. */
const MIN_BODY_H = 132;

/** Mode → icon. `mode` is one of the values the tenant's travel rules emit. */
const MODE_ICON = {
  FLIGHT: FlightTakeoffIcon,
  TRAIN: DirectionsRailwayIcon,
  CAB: LocalTaxiIcon,
};

/**
 * "Mon, 22 Sep" — the shape seeded rows carry. Inferred rows carry an ISO
 * instant instead, so this only has to catch the human form.
 */
const HUMAN_DATE = /(\d{1,2})\s+([A-Za-z]{3,})/;

/**
 * A trip's departure as epoch ms, or null when the row carries no readable date.
 *
 * `travelDate`/`time` arrive in two shapes. Inferred trips copy the source
 * meeting's ISO instant into both; seeded rows carry text ("Mon, 22 Sep" plus
 * "6:30 AM") that Date.parse rejects outright, and which omits the year — so
 * the text form is re-read against the current one.
 */
function tripStart(trip) {
  const iso = Date.parse(trip?.time || '');
  if (Number.isFinite(iso)) return iso;
  const isoDate = Date.parse(trip?.travelDate || '');
  if (Number.isFinite(isoDate)) return isoDate;
  const m = HUMAN_DATE.exec(trip?.travelDate || '');
  if (!m) return null;
  const guess = Date.parse(
    `${m[1]} ${m[2]} ${new Date().getFullYear()} ${trip?.time || ''}`.trim());
  return Number.isFinite(guess) ? guess : null;
}

/**
 * "Wed, 24 Sep · 8:10 AM" — the departure line above the route, in the tenant's
 * zone and notation like every other timestamp on the board.
 */
function fmtWhen(trip) {
  const at = tripStart(trip);
  if (at === null) {
    // Nothing parsable: show whatever text the row does carry rather than
    // dropping the only "when" the user has.
    return [trip?.travelDate, trip?.time].filter(Boolean).join(' · ');
  }
  const day = formatDate(at, { weekday: 'short', day: 'numeric', month: 'short' });
  const time = formatTime(at, { hour: 'numeric', minute: '2-digit' });
  return `${day} · ${time}`;
}

/** Whole days from the tenant's today to the tenant's calendar day of departure. */
function daysAway(at) {
  const today = tenantDayStart(0).getTime();
  const day = tenantDayStart(0, new Date(at)).getTime();
  return Math.round((day - today) / 86400000);
}

/**
 * Trips still ahead, soonest first.
 *
 * Anything that departed before today is dropped — the panel answers "what is
 * next", not "where have I been". Rows with no readable date are kept, at the
 * end: a trip the inference could not date is still a trip worth seeing.
 */
function upcoming(rows) {
  const dated = [];
  const undated = [];
  for (const trip of rows) {
    const at = tripStart(trip);
    if (at === null) undated.push(trip);
    else if (daysAway(at) >= 0) dated.push(trip);
  }
  dated.sort((a, b) => tripStart(a) - tripStart(b));
  return [...dated, ...undated];
}

/** The question the AI Co-pilot is handed when a trip row is clicked. */
function travelPrompt(trip, when) {
  const route = [trip?.from, trip?.to].filter(Boolean).join(' to ');
  return `Brief me for my ${when} trip${route ? ` from ${route}` : ''}`
    + `${trip?.mode && trip.mode !== 'UNSPECIFIED' ? ` by ${trip.mode.toLowerCase()}` : ''}. `
    + `${trip?.purpose ? `The purpose on record is "${trip.purpose}". ` : ''}`
    + 'Tell me why I am going, who I am meeting, what decisions are expected of '
    + 'me there, and anything I should settle or carry before I leave.';
}

/**
 * "Next travel" rail — the lower half of the column beside Tasks for Action,
 * under the meetings rail.
 *
 * Same reasoning that puts the calendar there: what needs doing is read against
 * what the week has already committed you to, and a trip commits more of it
 * than a meeting does. Trips are inferred from meetings and decisions, never
 * entered by hand, so this panel is read-only by design.
 *
 * The panel fills the height its host gives it and holds that height in every
 * state. Only the trip list scrolls; the header stays put.
 *
 * @param {function} [onAsk] hands a trip-briefing question to the AI Co-pilot
 */
export default function TravelRail({ onAsk }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState([]);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setFailed(false);
    try {
      // Travel lives on the mobile BFF, which the shell's nginx proxies at
      // /api/mobile/ — the shared axios interceptor supplies Authorization and
      // X-Tenant-Id, the two headers the endpoint scopes its answer by.
      const r = await axios.get('/api/mobile/travel');
      // No sample fallback: the rail shows the same trips the mobile app does —
      // an empty list is "no trips", a failure is the error state.
      setItems(Array.isArray(r.data) ? r.data : (r.data?.result || []));
    } catch {
      setItems([]); setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const trips = upcoming(items);
  const next = trips[0] || null;
  const nextAt = next ? tripStart(next) : null;
  // Days until the next departure — the panel's headline fact, and the reason
  // the countdown is in days where the meetings rail counts minutes.
  const nextIn = nextAt === null ? null : daysAway(nextAt);

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
          {t('travel.next', 'Next travel')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {nextIn !== null && (
          // Amber for the same reason the meetings rail uses it: a departure is
          // a deadline, and blue is reserved for things you click.
          <Typography fontSize={10.5} color={ORPHOS_TOKENS.status.warning.fg} noWrap>
            {nextIn === 0 ? t('travel.today', 'today')
              : nextIn === 1 ? t('travel.tomorrow', 'tomorrow')
              : t('travel.inDays', 'in {{n}} days', { n: nextIn })}
          </Typography>
        )}
        <Tooltip title={t('tasks.refresh', 'Refresh')}>
          <Button size="small" onClick={load}
            sx={{ minWidth: 0, px: 0.5, color: ORPHOS_TOKENS.text.muted }}>
            <RefreshIcon sx={{ fontSize: 14 }} />
          </Button>
        </Tooltip>
      </Box>

      {onAsk && trips.length > 0 && (
        <Typography fontSize={10.2} color={ORPHOS_TOKENS.text.hint} sx={{ mt: '2px', mb: '5px' }}>
          {t('travel.railHint', 'click a trip for its own brief')}
        </Typography>
      )}

      {/* ── Body: the only scrolling region ── */}
      <Box sx={{ flex: 1, minHeight: MIN_BODY_H, overflowY: 'auto', overflowX: 'hidden' }}>
        {loading ? (
          <Box sx={centred}>
            <CircularProgress size={16} />
            <Typography fontSize={11} color={ORPHOS_TOKENS.text.muted}>
              {t('travel.loading', 'Loading trips…')}
            </Typography>
          </Box>
        ) : failed ? (
          <Box sx={centred}>
            <ExploreOffIcon sx={{ fontSize: 26, color: ORPHOS_TOKENS.status.critical.graphic }} />
            <Typography fontSize={11.5} color={ORPHOS_TOKENS.status.critical.fg}>
              {t('travel.loadError', 'Could not load your travel.')}
            </Typography>
            <Button size="small" onClick={load}
              sx={{ fontSize: 10.5, textTransform: 'none', color: ORPHOS_TOKENS.text.link }}>
              {t('tasks.refresh', 'Refresh')}
            </Button>
          </Box>
        ) : trips.length === 0 ? (
          <Box sx={centred}>
            <LuggageIcon sx={{ fontSize: 26, color: ORPHOS_TOKENS.text.muted }} />
            <Typography fontSize={12} fontWeight={600} color={ORPHOS_TOKENS.text.secondary}>
              {t('travel.empty', 'No trips coming up')}
            </Typography>
            <Typography fontSize={10.5} color={ORPHOS_TOKENS.text.muted}>
              {t('travel.emptyHint', 'Trips appear here from your meetings and decisions.')}
            </Typography>
          </Box>
        ) : (
          trips.map((trip, i) => {
            const isNext = trip === next;
            const Icon = MODE_ICON[trip.mode] || LuggageIcon;
            const when = fmtWhen(trip);
            const confirmed = trip.confirmationStatus === 'CONFIRMED';
            // The leading bar carries how close the trip is: the one you leave
            // for next, or one further out.
            const bar = isNext ? ORPHOS_TOKENS.status.warning.graphic
              : ORPHOS_TOKENS.status.info.graphic;
            const askable = Boolean(onAsk);
            return (
              // The whole row is the briefing button, as in the meetings rail —
              // at rail width a per-row link is wider than its own route line.
              <ButtonBase
                key={trip.sourceId || i}
                component={askable ? 'button' : 'div'}
                disabled={!askable}
                onClick={askable ? () => onAsk({
                  label: t('travel.title', 'Travel'),
                  query: travelPrompt(trip, when),
                }) : undefined}
                title={askable ? t('travel.briefShort', 'Brief me') : undefined}
                sx={{
                  display: 'flex', alignItems: 'center', gap: '9px',
                  width: '100%', textAlign: 'left', borderRadius: '6px',
                  py: '7px', pr: '4px',
                  borderBottom: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
                  '&:hover': { bgcolor: ORPHOS_TOKENS.bg.surface },
                }}
              >
                <Box sx={{ width: 3, alignSelf: 'stretch', flex: '0 0 3px',
                           borderRadius: '3px', bgcolor: bar }} />
                <Icon sx={{ fontSize: 16, flexShrink: 0,
                            color: isNext ? ORPHOS_TOKENS.status.warning.graphic
                                          : ORPHOS_TOKENS.text.muted }} />
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  <Typography fontSize={11.5} color={ORPHOS_TOKENS.text.bright}
                    sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {when}
                  </Typography>
                  <Typography fontSize={12} fontWeight={600} color={ORPHOS_TOKENS.text.primary}
                    sx={{ mt: '1px', textWrap: 'pretty' }}>
                    {trip.from && trip.to
                      ? `${trip.from} → ${trip.to}`
                      : (trip.purpose || t('travel.untitled', 'Untitled trip'))}
                  </Typography>
                  {trip.from && trip.to && trip.purpose && (
                    <Typography fontSize={10.8} color={ORPHOS_TOKENS.text.hint} sx={{ mt: '2px' }}>
                      {trip.purpose}
                    </Typography>
                  )}
                  {/* Tentative is the state worth calling out: a confirmed trip
                      needs nothing from you, an unconfirmed one might. */}
                  {!confirmed && (
                    <Typography fontSize={10} color={ORPHOS_TOKENS.status.warning.fg}
                      sx={{ mt: '2px', textTransform: 'uppercase', letterSpacing: 0.6 }}>
                      {t('travel.tentative', 'Tentative')}
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
