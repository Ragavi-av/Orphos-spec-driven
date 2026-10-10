import { useEffect, useState, useCallback } from 'react';
import { Box, Typography, CircularProgress, ButtonBase } from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import { ORPHOS_TOKENS, ORPHOS_COLORS, orphosAnim } from 'eip-shell-context/orphosTheme';
import useSectionPref from 'eip-shell-context/useSectionPref';
import SectionToggle from 'eip-shell-context/SectionToggle';
import {
  fetchTodaysMeetings, nextUpcoming, startOf, fmtTime,
  minutesUntil, meetingSubtitle, briefingPrompt,
} from 'eip-shell-context/meetings';

/** Width of the next-meeting panel, read off the artboard. */
const MEETING_PANEL_W = 224;

/**
 * Status-light colour per severity band.
 *
 * Red / amber / green rather than the status tokens' own graphic colours: the
 * `info` token is blue, which on a "what needs you today" banner reads as
 * another shade of the card rather than as "this one is calm".
 */
const SEVERITY_DOT = {
  critical: ORPHOS_COLORS.critical,
  warning: ORPHOS_COLORS.warning,
  info: ORPHOS_COLORS.success,
};

/**
 * Tint behind a task's rank chip — its own severity hue at 14% over the inset
 * well, matching the artboard's rgba(255,107,129,.14) / rgba(255,184,77,.14).
 * Derived rather than hardcoded so a third severity cannot render untinted.
 */
function chipTint(severity) {
  const hex = (SEVERITY_DOT[severity] || SEVERITY_DOT.info).replace('#', '');
  const n = parseInt(hex, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, 0.14)`;
}

/**
 * Greeting bucket for the current hour, resolved in the TENANT's timezone
 * (branding profile) rather than the viewer's browser — mirrors AICopilot's
 * greetingKey() in the ai-copilot remote. Duplicated rather than imported:
 * each Module Federation remote/host bundles its own copy of this kind of
 * helper (see eip-shell-context bundling notes elsewhere in this repo).
 */
function greetingKey(tz) {
  let hour;
  try {
    hour = Number(new Intl.DateTimeFormat('en-GB', {
      hour: 'numeric', hour12: false, timeZone: tz || undefined,
    }).format(new Date()));
  } catch {
    hour = new Date().getHours();
  }
  if (!Number.isFinite(hour)) hour = new Date().getHours();
  hour %= 24;

  if (hour < 12) return 'morning';
  if (hour < 17) return 'afternoon';
  return 'evening';
}

/**
 * One ranked task in the brief — rank chip, category flag, title, why-line.
 *
 * The WHOLE ROW is the click target, and it hands this one item to the AI
 * Co-pilot. The design draws no button here: the title carries the interactive
 * blue and a quiet mark sits at the trailing edge, which is why the row reads
 * as three facts rather than as three buttons.
 *
 * That replaced a per-row "Ask Orphos" button. Three of them stacked in one
 * panel turned the brief into a column of chrome — the buttons were the widest,
 * loudest thing in a band whose job is to be read at a glance, and the top one
 * being filled blue implied a ranking the digest had already stated with the
 * numbered chips. The affordance is unchanged; only its weight is.
 *
 * One interactive element per row, not a clickable title nested inside a
 * clickable row: nesting gives the row two tab stops and two accessible names
 * for a single action. The row owns the click and `aria-label` names what it
 * does, since the visible text is the task, not the verb.
 */
function TaskRow({ item, rank, flag, title, last, onAsk, t }) {
  const tone = SEVERITY_DOT[item.severity] || SEVERITY_DOT.info;

  return (
    <Box
      onClick={onAsk || undefined}
      role={onAsk ? 'button' : undefined}
      tabIndex={onAsk ? 0 : undefined}
      aria-label={onAsk ? t('dashboard.digest.askAboutItem', { title }) : undefined}
      onKeyDown={onAsk
        ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onAsk(); } }
        : undefined}
      sx={{
        display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0,
        pb: last ? 0 : '11px',
        borderBottom: last ? 'none' : `1px solid #1C2F5F`,
        cursor: onAsk ? 'pointer' : 'default',
        // Negative margin + matching padding: the hover tint reaches the well's
        // inner edge instead of stopping short at the text column, so the whole
        // strip lights up as one target rather than a floating band.
        mx: '-9px', px: '9px', pt: '3px', borderRadius: '9px',
        transition: 'background-color 160ms ease',
        '&:hover .brief-row-title': onAsk ? { textDecoration: 'underline' } : undefined,
        '&:hover': onAsk ? { bgcolor: 'rgba(47, 111, 240, 0.10)' } : undefined,
        '&:focus-visible': {
          outline: `2px solid ${ORPHOS_COLORS.accent}`,
          outlineOffset: '2px',
        },
      }}
    >
      <Box aria-hidden sx={{
        flex: '0 0 26px', height: 26, borderRadius: '8px',
        display: 'grid', placeItems: 'center',
        fontSize: 14, fontWeight: 800,
        bgcolor: chipTint(item.severity), color: tone,
      }}>
        {rank}
      </Box>

      <Box sx={{ flex: 1, minWidth: 0 }}>
        {flag && (
          <Typography component='div' fontSize={12} fontWeight={700} color={tone}
            sx={{ textTransform: 'uppercase', letterSpacing: '1px' }}>
            {flag}
          </Typography>
        )}
        {/* Interactive blue, per the design. White here would have made the
            row's one clickable thing look like plain copy. */}
        <Typography component='div' className='brief-row-title'
          fontSize={15} fontWeight={650}
          color={onAsk ? ORPHOS_TOKENS.text.link : ORPHOS_TOKENS.text.primary}
          mt='4px' sx={{ textWrap: 'pretty' }}>
          {title}
        </Typography>
        {item.detail && (
          <Typography component='div' fontSize={13.5} color={ORPHOS_TOKENS.text.muted}
            mt='3px' sx={{ textWrap: 'pretty' }}>
            {item.detail}
          </Typography>
        )}
      </Box>

      {/* The trailing mark. Decorative only — the row is the button and already
          carries the accessible name, so announcing this too would say the same
          thing twice. */}
      {onAsk && (
        <Box component='span' aria-hidden sx={{
          flex: '0 0 auto', fontSize: 15, lineHeight: 1,
          color: ORPHOS_TOKENS.text.link,
        }}>
          →
        </Box>
      )}
    </Box>
  );
}

/**
 * "Next meeting" panel on the right of the brief.
 *
 * Reads the same calendar the AI Summary rail does (eip-shell-context/meetings)
 * so the two can never name different meetings as next. The artboard draws a
 * "16:30 – 17:15" range, but a meeting record carries only a start — rendering
 * an invented end time would be worse than rendering the start alone, so this
 * shows the start and lets the title carry the rest.
 */
function NextMeetingPanel({ meeting, onAsk, onOpenDay, t }) {
  const mins = meeting ? minutesUntil(meeting) : null;
  const subtitle = meeting ? meetingSubtitle(meeting) : '';

  return (
    <Box
      onClick={meeting && onAsk ? onAsk : undefined}
      role={meeting && onAsk ? 'button' : undefined}
      tabIndex={meeting && onAsk ? 0 : undefined}
      onKeyDown={meeting && onAsk
        ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onAsk(); } }
        : undefined}
      title={meeting && onAsk ? t('dashboard.nextMeeting.brief', 'Brief me for this meeting') : undefined}
      sx={{
        flex: `0 0 ${MEETING_PANEL_W}px`, minWidth: 0, px: '2px',
        cursor: meeting && onAsk ? 'pointer' : 'default',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: '8px' }}>
        <Typography component='div' fontSize={12.5} fontWeight={700}
          color={ORPHOS_TOKENS.text.muted}
          sx={{ textTransform: 'uppercase', letterSpacing: '0.9px' }}>
          {t('dashboard.nextMeeting.label', 'Next meeting')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {/* A countdown, not a link — never the interactive blue. Red rather
            than amber, per the design: at the top of the band this is the one
            number with a clock running against it. */}
        {mins !== null && (
          <Typography component='div' fontSize={12.5} noWrap
            color={ORPHOS_TOKENS.status.critical.fg}>
            {t('meetings.inMinutes', 'in {{n}} min', { n: mins })}
          </Typography>
        )}
      </Box>

      {meeting ? (
        <>
          <Typography component='div' fontSize={14} color={ORPHOS_TOKENS.text.bright}
            mt='6px' sx={{ fontVariantNumeric: 'tabular-nums' }}>
            {fmtTime(startOf(meeting))}
          </Typography>
          <Typography component='div' fontSize={14.5} fontWeight={600}
            color={ORPHOS_TOKENS.text.primary} mt='3px' sx={{ textWrap: 'pretty' }}>
            {meeting.title || t('meetings.untitled', 'Untitled meeting')}
          </Typography>
          {subtitle && (
            <Typography component='div' fontSize={12.5} color={ORPHOS_TOKENS.text.muted}
              mt='4px' sx={{ textWrap: 'pretty' }}>
              {subtitle}
            </Typography>
          )}
        </>
      ) : (
        <Typography component='div' fontSize={13.5} color={ORPHOS_TOKENS.text.muted} mt='6px'>
          {t('dashboard.nextMeeting.clear', 'Nothing else on the calendar today.')}
        </Typography>
      )}

      {onOpenDay && (
        <ButtonBase
          onClick={(e) => { e.stopPropagation(); onOpenDay(); }}
          sx={{
            mt: '9px', fontSize: 13.5, fontWeight: 600, borderRadius: '6px',
            color: ORPHOS_COLORS.primaryAlt,
            '&:hover': { textDecoration: 'underline' },
          }}
        >
          {t('dashboard.nextMeeting.viewDay', 'View full day')} →
        </ButtonBase>
      )}
    </Box>
  );
}

/**
 * Dashboard-top banner: a time-of-day greeting, one headline naming today's
 * most important thing, the ranked items that need the user personally, and
 * what is next on their calendar.
 *
 * The prose comes from briefing-service's /api/briefing/daily-digest, which
 * has the tenant's model phrase facts it reads from the same rows the widget
 * board shows. When the model is unavailable the endpoint returns translation
 * keys + counts instead of sentences (`titleKey`/`n`), which this renders
 * through i18next — so the banner never depends on the AI stack being up.
 *
 * The whole brief collapses to a one-line summary, and that choice can be
 * pinned as a default — see useSectionPref. A user who reads the brief on their
 * phone before opening the dashboard does not want to close it every morning.
 *
 * @param {function} [onAsk] hands a question to the AI Co-pilot side panel
 * @param {function} [onOpenMeetings] opens the full calendar view
 * @param {number}   [refreshKey] bumped by the header refresh button; forces a re-read
 */
export default function DashboardGreeting({
  userFullName, tenantTimezone, onAsk, onOpenMeetings, refreshKey = 0,
}) {
  const { t, i18n } = useTranslation();
  const [digest, setDigest] = useState(null);   // null = loading
  const [failed, setFailed] = useState(false);
  const [meeting, setMeeting] = useState(null);

  const language = i18n.language || 'en';
  const brief = useSectionPref('brief', true);

  useEffect(() => {
    let cancelled = false;
    setDigest(null);
    setFailed(false);

    // refreshKey > 0 means the user asked for fresh data, so bypass the
    // service-side per-tenant cache rather than re-reading the same answer.
    axios.get('/api/briefing/daily-digest', {
      params: { lang: language, ...(refreshKey > 0 ? { refresh: true } : {}) },
    })
      .then(r => { if (!cancelled) setDigest(r.data || {}); })
      .catch(() => { if (!cancelled) { setDigest({}); setFailed(true); } });

    return () => { cancelled = true; };
  }, [language, refreshKey]);

  // The calendar is a SEPARATE failure domain from the digest: the briefing
  // model being down must not blank the next-meeting panel, and an empty
  // calendar must not make the brief look broken. Hence its own request and its
  // own silent failure — the panel simply falls back to "nothing else today".
  useEffect(() => {
    let cancelled = false;
    fetchTodaysMeetings()
      .then(rows => { if (!cancelled) setMeeting(nextUpcoming(rows)); })
      .catch(() => { if (!cancelled) setMeeting(null); });
    return () => { cancelled = true; };
  }, [refreshKey]);

  const greeting = t(`dashboard.greeting.${greetingKey(tenantTimezone)}`);
  const hello = userFullName
    ? t('dashboard.hello', { greeting, name: userFullName.split(' ')[0] })
    : greeting;

  const items = Array.isArray(digest?.items) ? digest.items : [];
  const headline = digest?.headline
    || (digest?.headlineKey ? t(digest.headlineKey) : '');

  /** Model prose when present, otherwise the translated fallback line. */
  const itemTitle = useCallback(
    item => (item.title ? item.title : item.titleKey ? t(item.titleKey, { count: item.n }) : ''),
    [t]);

  /**
   * Hand ONE item to the co-pilot.
   *
   * `{ label, query }` — the same shape every other "ask the co-pilot about
   * this" caller uses (WidgetBoard, TasksForAction, NotificationsPanel). The
   * panel reads `.query` and badges the message with `.label`, so the issue
   * this came from stays visible in the conversation.
   */
  const askAboutItem = useCallback((item) => {
    if (!onAsk) return;
    const title = itemTitle(item);
    onAsk({
      label: item.kind ? t(`dashboard.digest.kind.${item.kind}`, item.kind) : t('dashboard.digest.askLabel'),
      // The detail is parenthesised HERE rather than in the catalogue, so a
      // task with no detail line does not leave an empty "()" in the prompt.
      query: t('dashboard.digest.askItemPrompt', {
        title,
        detail: item.detail ? ` (${item.detail})` : '',
      }),
    });
  }, [onAsk, itemTitle, t]);

  const askAboutMeeting = useCallback(() => {
    if (!onAsk || !meeting) return;
    onAsk({
      label: t('dashboard.nextMeeting.label', 'Next meeting'),
      query: briefingPrompt(meeting),
    });
  }, [onAsk, meeting, t]);


  const hasBrief = digest !== null && !failed && items.length > 0;

  return (
    <Box sx={{
      display: 'flex', mb: 2, borderRadius: '14px', overflow: 'hidden',
      border: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
      bgcolor: ORPHOS_TOKENS.bg.surface,
    }}>
      {/* Full-bleed accent rail rather than a thick left border — it runs the
          banner's whole height, including past the rounded corners.
          Blue, per the artboard, NOT keyed to the worst item's severity. It had
          been red/amber/green here, which double-encoded something the band
          already says twice — every task carries a severity-toned rank chip and
          flag, and the headline names the worst one in words. A red rail on top
          of that made the whole band read as one undifferentiated alarm. */}
      <Box sx={{ flex: '0 0 4px', bgcolor: ORPHOS_COLORS.primary }} />

      <Box sx={{ flex: 1, minWidth: 0, px: '22px', pt: '18px', pb: '20px' }}>
        {/* ── Greeting, with the fiscal period and the collapse control at the
               far edge ── */}
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, flexWrap: 'wrap' }}>
          <Typography fontWeight={700} fontSize={21} letterSpacing='-0.3px'
            color={ORPHOS_TOKENS.text.primary}>
            {hello}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {digest?.period && (
            <Typography fontSize={13} fontWeight={600} color={ORPHOS_TOKENS.text.muted}
              sx={{ textTransform: 'uppercase', letterSpacing: '0.9px',
                    whiteSpace: 'nowrap', textAlign: 'right' }}>
              {digest.period}
            </Typography>
          )}
          {/* Only offered once there is something to collapse — a toggle over a
              spinner or an "all clear" line hides nothing. */}
          {hasBrief && (
            <SectionToggle
              // The row aligns on `baseline`, which is right for the greeting
              // and the fiscal period beside it — two type sizes sharing one
              // baseline. It is wrong for this control: a flex container's
              // baseline comes from its first item, so the ▲ glyph's baseline
              // was being matched to 21px display type and the button sat low.
              sx={{ alignSelf: 'center' }}
              open={brief.open}
              always={brief.always}
              onToggle={brief.toggle}
              onSetAlways={brief.setAlways}
              menuTitle={t('dashboard.brief.menuTitle', 'Morning brief default')}
              words={{
                expand: t('dashboard.brief.expanded', 'expanded'),
                collapse: t('dashboard.brief.minimised', 'minimised'),
              }}
            />
          )}
        </Box>

        {digest === null ? (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.25 }}>
            <CircularProgress size={14} />
            <Typography fontSize={13} color={ORPHOS_TOKENS.text.muted}>
              {t('dashboard.loading')}
            </Typography>
          </Box>
        ) : failed ? (
          <Typography fontSize={13} color={ORPHOS_TOKENS.text.muted} mt={1.25}>
            {t('dashboard.digest.unavailable')}
          </Typography>
        ) : items.length === 0 ? (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 1.25 }}>
            <CheckCircleIcon sx={{ fontSize: 19, color: ORPHOS_TOKENS.status.success.graphic }} />
            <Typography fontSize={13.5} color={ORPHOS_TOKENS.status.success.fg}>
              {t('dashboard.allClear')}
            </Typography>
          </Box>
        ) : brief.open ? (
          /* `grow`, the 6px travel, is what the artboard uses here — the brief
             expands in place inside a band that is already on screen, so it has
             a short distance to come from. */
          <Box sx={{ animation: orphosAnim('grow', brief.closing) }}>
            {headline && (
              <Typography fontWeight={700} fontSize={16} color={ORPHOS_TOKENS.text.primary}
                mt='10px' sx={{ textWrap: 'pretty' }}>
                {headline}
              </Typography>
            )}

            <Box sx={{
              display: 'flex', alignItems: 'stretch', gap: '22px', mt: '14px',
              // The meeting panel is a fixed 224px beside a flexible list; below
              // roughly a tablet the two together leave the task titles too
              // narrow to read, so the panel drops underneath instead.
              flexWrap: { xs: 'wrap', md: 'nowrap' },
            }}>
              {/* ── Ranked tasks, in a well recessed into the banner ── */}
              <Box sx={{
                flex: '1 1 340px', minWidth: 0,
                bgcolor: ORPHOS_TOKENS.bg.inset,
                border: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
                borderRadius: '13px', p: '13px 15px',
                display: 'flex', flexDirection: 'column', gap: '11px',
              }}>
                {/* No "N things need you today" header here — the design has
                    the card open straight onto row 1. The count would restate
                    what three numbered chips already show, and it pushed the
                    first task below the fold of the band. The collapsed state
                    still carries it, which is where a count actually earns its
                    line. */}
                {items.map((item, idx) => (
                  <TaskRow
                    key={`${item.kind || ''}-${item.id || item.titleKey || item.title}-${idx}`}
                    item={item}
                    rank={idx + 1}
                    flag={item.kind ? t(`dashboard.digest.kind.${item.kind}`, item.kind) : ''}
                    title={itemTitle(item)}
                    last={idx === items.length - 1}
                    onAsk={onAsk ? () => askAboutItem(item) : null}
                    t={t}
                  />
                ))}
              </Box>

              {/* Hairline rather than a border on the panel — the artboard
                  separates the two columns without boxing the right-hand one. */}
              <Box aria-hidden sx={{
                flex: '0 0 1px', bgcolor: ORPHOS_TOKENS.line.subtle,
                display: { xs: 'none', md: 'block' },
              }} />

              <NextMeetingPanel
                meeting={meeting}
                onAsk={onAsk ? askAboutMeeting : null}
                onOpenDay={onOpenMeetings}
                t={t}
              />
            </Box>
          </Box>
        ) : (
          /* ── Collapsed: the one line that justifies re-opening it ── */
          <Box sx={{
            display: 'flex', alignItems: 'center', gap: '12px', mt: '9px',
            animation: orphosAnim('grow'),
          }}>
            <Typography component='div' fontSize={15} color={ORPHOS_TOKENS.text.secondary}
              sx={{ minWidth: 0, textWrap: 'pretty' }}>
              <Box component='span' sx={{ fontWeight: 700, color: '#FFFFFF' }}>
                {headline || itemTitle(items[0])}
              </Box>
              {' · '}
              {t('dashboard.digest.needsYou', { count: items.length })}
            </Typography>
            <Box sx={{ flex: 1 }} />
            {meeting && (
              <Typography component='div' fontSize={14} noWrap
                color={ORPHOS_TOKENS.text.bright}
                sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmtTime(startOf(meeting))} · {meeting.title}
              </Typography>
            )}
          </Box>
        )}
      </Box>
    </Box>
  );
}
