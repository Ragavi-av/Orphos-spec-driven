import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Chip, Accordion, AccordionSummary, AccordionDetails,
  IconButton, Tooltip,
} from '@mui/material';
import ExpandMoreIcon   from '@mui/icons-material/ExpandMore';
import AutoAwesomeIcon  from '@mui/icons-material/AutoAwesome';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useTranslation } from 'react-i18next';
import { fetchSessions, removeSession, clearSessions } from './sessionStore';

/**
 * Bucket sessions under a date heading.
 *
 * Takes `t` and `lng` rather than reaching for the singleton: the heading is
 * both translated copy ("Today") and a locale-formatted date, and hardcoding
 * either — 'en-GB' was hardcoded here — left "Wednesday, 20 August 2026" above
 * an otherwise Japanese history panel.
 */
function groupByDate(sessions, t, lng) {
  const groups = {};
  for (const s of sessions) {
    const d = new Date(s.startedAt);
    const today     = new Date();
    const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
    let label;
    if (d.toDateString() === today.toDateString())     label = t('copilotHistory.today');
    else if (d.toDateString() === yesterday.toDateString()) label = t('copilotHistory.yesterday');
    else label = d.toLocaleDateString(lng, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

    if (!groups[label]) groups[label] = [];
    groups[label].push(s);
  }
  return groups;
}

export default function CopilotHistory() {
  const { t, i18n } = useTranslation();
  const lng = i18n.language || 'en';
  const [sessions, setSessions] = useState([]);

  const refresh = useCallback(() => {
    fetchSessions().then(setSessions).catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    refresh();
    // 'orphos:sessions-updated' only covers saves made in THIS tab. The mobile app
    // writes to the same per-user store, so also re-read when the page regains focus.
    // That way a question asked on the phone shows up without remounting the panel.
    const onVisible = () => { if (document.visibilityState === 'visible') refresh(); };
    window.addEventListener('orphos:sessions-updated', refresh);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('orphos:sessions-updated', refresh);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);

  const deleteSession = (id) => {
    setSessions(prev => prev.filter(s => s.id !== id));   // optimistic
    removeSession(id).catch(refresh);
  };

  const clearAll = () => {
    setSessions([]);                                       // optimistic
    clearSessions().catch(refresh);
  };

  if (sessions.length === 0) {
    return (
      <Box sx={{ py: 8, textAlign: 'center' }}>
        <AutoAwesomeIcon sx={{ fontSize: 36, color: '#7D92C4', mb: 1.5 }} />
        <Typography fontSize={14} fontWeight={600} color="#C3D3F2">
          {t('copilotHistory.emptyTitle', 'No co-pilot history yet')}
        </Typography>
        <Typography fontSize={12} color="#7D92C4" mt={0.5}>
          {t('copilotHistory.emptyBody',
             'Questions you ask in the AI Co-pilot will appear here, grouped by session and date.')}
        </Typography>
      </Box>
    );
  }

  const grouped = groupByDate(sessions, t, lng);

  return (
    <Box>
      {/* Header row */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        {/* Counts go through i18next rather than an inline `s` suffix — that
            suffix is English grammar hardcoded into the layout, and there is no
            plural form to append in Japanese. */}
        <Typography fontSize={13} color="#C3D3F2">
          {t('copilotHistory.summary', '{{sessions}} session(s) · {{questions}} questions total', {
            sessions: sessions.length,
            questions: sessions.reduce((n, s) => n + (s.messages?.length || 0), 0),
          })}
        </Typography>
        <Tooltip title={t('copilotHistory.clearAll', 'Clear all history')}>
          <IconButton size="small" onClick={clearAll} sx={{ color: '#7D92C4', '&:hover': { color: '#FF6B81' } }}>
            <DeleteOutlineIcon sx={{ fontSize: 18 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Date groups */}
      {Object.entries(grouped).map(([dateLabel, daySessions]) => (
        <Box key={dateLabel} sx={{ mb: 3 }}>
          <Typography fontSize={11} fontWeight={700} color="#7D92C4"
            sx={{ textTransform: 'uppercase', letterSpacing: 0.8, mb: 1 }}>
            {dateLabel}
          </Typography>

          {daySessions.map(session => (
            <Accordion key={session.id} elevation={0} disableGutters
              sx={{ border: '1px solid #243A72', borderRadius: '8px !important', mb: 1, '&:before': { display: 'none' }, overflow: 'hidden' }}>
              <AccordionSummary expandIcon={<ExpandMoreIcon sx={{ fontSize: 18, color: '#7D92C4' }} />}
                sx={{ px: 1.5, py: 0.5, minHeight: 44, '& .MuiAccordionSummary-content': { my: 0.5 } }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flex: 1, minWidth: 0, mr: 1 }}>
                  <AutoAwesomeIcon sx={{ fontSize: 14, color: '#8DBCFF', flexShrink: 0 }} />
                  <Typography fontSize={13} fontWeight={600} color="#FFFFFF" noWrap sx={{ flex: 1 }}>
                    {session.label || 'Session'}
                  </Typography>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexShrink: 0 }}>
                    <Chip
                      label={`${session.messages?.length || 0} Q`}
                      size="small"
                      sx={{ fontSize: 10, height: 18, bgcolor: '#0F2450', color: '#C3D3F2' }}
                    />
                    <Typography fontSize={11} color="#7D92C4">
                      {new Date(session.startedAt).toLocaleTimeString(lng, { hour: '2-digit', minute: '2-digit' })}
                    </Typography>
                    {session.endedAt && (
                      <Chip label="ended" size="small" sx={{ fontSize: 9, height: 16, bgcolor: '#0F2450', color: '#7D92C4' }} />
                    )}
                  </Box>
                </Box>
              </AccordionSummary>

              <AccordionDetails sx={{ p: 0, borderTop: '1px solid #243A72' }}>
                {/* Q&A list */}
                {(session.messages || []).map((msg, idx) => (
                  <Box key={msg.id}
                    sx={{ px: 1.5, py: 1.25, borderBottom: idx < session.messages.length - 1 ? '1px solid #243A72' : 'none' }}>
                    {/* Source badge */}
                    {msg.source && (
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.75 }}>
                        <AutoAwesomeIcon sx={{ fontSize: 11, color: '#8DBCFF' }} />
                        <Typography fontSize={10} color="#8DBCFF" fontWeight={600}>Context: {msg.source}</Typography>
                      </Box>
                    )}
                    {/* Question */}
                    <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 0.75 }}>
                      <Box sx={{ bgcolor: 'rgba(91, 157, 255, 0.14)', border: '1px solid rgba(91, 157, 255, 0.42)', borderRadius: '8px 8px 2px 8px', px: 1.25, py: 0.6, maxWidth: '85%' }}>
                        <Typography fontSize={12} color="#8DBCFF" fontWeight={500} lineHeight={1.5}>
                          {msg.question}
                        </Typography>
                      </Box>
                    </Box>
                    {/* Answer */}
                    <Box sx={{ bgcolor: '#0B1533', border: '1px solid rgba(91, 157, 255, 0.42)', borderRadius: '2px 8px 8px 8px', px: 1.25, py: 1 }}>
                      <Typography fontSize={12} color="#FFFFFF" lineHeight={1.65} sx={{ whiteSpace: 'pre-wrap' }}>
                        {msg.answer}
                      </Typography>
                    </Box>
                    {/* Meta */}
                    <Typography fontSize={10} color="#7D92C4" mt={0.4} textAlign="right">
                      {new Date(msg.timestamp).toLocaleTimeString(lng, { hour: '2-digit', minute: '2-digit' })} · {msg.provider}
                    </Typography>
                  </Box>
                ))}

                {/* Session delete */}
                <Box sx={{ px: 1.5, py: 0.75, display: 'flex', justifyContent: 'flex-end', borderTop: '1px solid #243A72' }}>
                  <Tooltip title={t('copilotHistory.deleteSession', 'Delete this session')}>
                    <IconButton size="small" onClick={() => deleteSession(session.id)}
                      sx={{ color: '#7D92C4', '&:hover': { color: '#FF6B81' } }}>
                      <DeleteOutlineIcon sx={{ fontSize: 15 }} />
                    </IconButton>
                  </Tooltip>
                </Box>
              </AccordionDetails>
            </Accordion>
          ))}
        </Box>
      ))}
    </Box>
  );
}
