import { useCallback, useEffect, useState } from 'react';
import {
  Drawer, Box, Stack, Typography, Chip, Divider, CircularProgress, IconButton, Button,
  TextField, MenuItem, List, ListItem, ListItemText, ListItemButton, Alert, Tooltip,
  Accordion, AccordionSummary, AccordionDetails, LinearProgress,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import InsightsIcon from '@mui/icons-material/Insights';
import LinkIcon from '@mui/icons-material/Link';
import HistoryIcon from '@mui/icons-material/History';
import RefreshIcon from '@mui/icons-material/Refresh';
import DownloadIcon from '@mui/icons-material/Download';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { useTranslation } from 'react-i18next';
import {
  getDecision, changeStatus, recordOutcome, getTimeline, getCorrelations, getRelated,
  getAnnotation, predictImpact, exportDecisionUrl, PRIORITY_COLOR, STATUS_COLOR, IMPL_COLOR, fmtDate,
} from './api';

const STATUSES = ['DETECTED', 'PENDING', 'APPROVED', 'REJECTED'];
const IMPLS = ['NOT_STARTED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED'];

/**
 * Full decision context (Req 5.1-5.4): all fields, AI annotation, impact
 * prediction, related decisions, cross-system correlations, milestone/status
 * tracking and the audit timeline — in one drawer shared by every view.
 */
export default function DetailDrawer({ decisionId, onClose, onChanged }) {
  const { t } = useTranslation();
  const [d, setD] = useState(null);
  const [timeline, setTimeline] = useState([]);
  const [correlations, setCorrelations] = useState([]);
  const [related, setRelated] = useState([]);
  const [annotation, setAnnotation] = useState(null);
  const [impact, setImpact] = useState(null);
  const [outcomeDraft, setOutcomeDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [currentId, setCurrentId] = useState(null);

  useEffect(() => { setCurrentId(decisionId); }, [decisionId]);

  const load = useCallback((id) => {
    if (!id) return;
    setD(null); setAnnotation(null); setImpact(null); setError(null);
    getDecision(id)
      .then(dec => {
        setD(dec);
        setImpact(dec.predictedImpact && Object.keys(dec.predictedImpact).length ? dec.predictedImpact : null);
        setOutcomeDraft(dec.actualOutcome || '');
      })
      .catch(() => setError(t('decisionLog.loadError')));
    getTimeline(id).then(setTimeline).catch(() => setTimeline([]));
    getCorrelations(id).then(setCorrelations).catch(() => setCorrelations([]));
    getRelated(id).then(setRelated).catch(() => setRelated([]));
    getAnnotation(id).then(setAnnotation).catch(() => setAnnotation(null));
  }, [t]);

  useEffect(() => { load(currentId); }, [currentId, load]);

  const patch = async (body) => {
    setBusy(true);
    try {
      const updated = await changeStatus(currentId, body);
      setD(updated);
      getTimeline(currentId).then(setTimeline).catch(() => {});
      onChanged?.();
    } catch { setError(t('decisionLog.loadError')); }
    setBusy(false);
  };

  const saveOutcome = async () => {
    setBusy(true);
    try {
      const updated = await recordOutcome(currentId, outcomeDraft);
      setD(updated);
      onChanged?.();
    } catch { setError(t('decisionLog.loadError')); }
    setBusy(false);
  };

  const runImpact = async () => {
    setBusy(true);
    try { setImpact(await predictImpact(currentId)); } catch { /* keep old */ }
    setBusy(false);
  };

  const refreshAnnotation = async () => {
    setAnnotation(null);
    try { setAnnotation(await getAnnotation(currentId, true)); } catch { setAnnotation(null); }
  };

  return (
    <Drawer anchor="right" open={!!decisionId} onClose={onClose}
      PaperProps={{ sx: { width: { xs: '100%', sm: 560 }, p: 2 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h6" sx={{ pr: 2 }}>{d?.title || '…'}</Typography>
        <IconButton onClick={onClose}><CloseIcon /></IconButton>
      </Stack>
      {error && <Alert severity="warning" sx={{ my: 1 }}>{error}</Alert>}
      {!d && !error && <CircularProgress sx={{ m: 4 }} />}
      {d && (
        <Box>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap my={1}>
            <Chip size="small" color={STATUS_COLOR[d.status] || 'default'}
              label={t(`decisionLog.status.${d.status}`, d.status)} />
            {d.priorityLevel && <Chip size="small" color={PRIORITY_COLOR[d.priorityLevel] || 'default'}
              variant="outlined" label={d.priorityLevel} />}
            {d.implementationStatus && <Chip size="small" color={IMPL_COLOR[d.implementationStatus] || 'default'}
              variant="outlined" label={t(`decisionLog.impl.${d.implementationStatus}`, d.implementationStatus)} />}
            {d.classificationType && <Chip size="small" variant="outlined" label={d.classificationType} />}
            {d.detectionSource && <Chip size="small" variant="outlined" icon={
              String(d.detectionSource).endsWith('_RAG') ? <SmartToyIcon /> : undefined}
              label={t(`decisionLog.detection.${d.detectionSource}`, d.detectionSource)} />}
            {d.escalated && <Chip size="small" color="warning" icon={<WarningAmberIcon />}
              label={t('decisionLog.detail.escalated')} />}
          </Stack>

          {String(d.status) === 'DETECTED' && (
            <Alert severity="info" sx={{ mb: 1 }}
              action={
                <Stack direction="row" spacing={1}>
                  <Button size="small" disabled={busy} onClick={() => patch({ status: 'PENDING' })}>
                    {t('decisionLog.actions.confirm')}
                  </Button>
                  <Button size="small" color="error" disabled={busy} onClick={() => patch({ status: 'REJECTED' })}>
                    {t('decisionLog.actions.reject')}
                  </Button>
                </Stack>
              }>
              {t('decisionLog.detection.aiFlagged')}
              {typeof d.confidenceScore === 'number' &&
                ` (${t('decisionLog.detection.confidence', { pct: Math.round(d.confidenceScore * 100) })})`}
            </Alert>
          )}

          <Field label={t('decisionLog.fields.description')} value={d.description} multiline />
          <Field label={t('decisionLog.fields.rationale')} value={d.rationale} multiline />
          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
            <Field label={t('decisionLog.fields.domain')} value={[d.domain, d.decisionArea].filter(Boolean).join(' / ')} />
            <Field label={t('decisionLog.fields.decidedBy')} value={d.decidedBy} />
            <Field label={t('decisionLog.fields.decidedAt')} value={fmtDate(d.decidedAt)} />
            <Field label={t('decisionLog.fields.dueDate')} value={fmtDate(d.dueDate)} />
          </Stack>

          {(d.involvedPeople || []).length > 0 && (
            <Field label={t('decisionLog.fields.involvedPeople')}
              value={d.involvedPeople.map(p => [p.name, p.role, p.participation].filter(Boolean).join(' · ')).join('\n')}
              multiline />
          )}
          {(d.affectedAreas || []).length > 0 && (
            <Box my={1}>
              <Typography variant="caption" color="text.secondary">{t('decisionLog.fields.affectedAreas')}</Typography>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap mt={0.5}>
                {d.affectedAreas.map((a, i) => (
                  <Chip key={i} size="small" label={`${a.kind || ''} ${a.name || ''}`.trim()} />
                ))}
              </Stack>
            </Box>
          )}
          {(d.milestones || []).length > 0 && (
            <Box my={1}>
              <Typography variant="caption" color="text.secondary">{t('decisionLog.fields.milestones')}</Typography>
              <List dense disablePadding>
                {d.milestones.map((m, i) => (
                  <ListItem key={i} disableGutters>
                    <ListItemText primary={m.name}
                      secondary={[m.dueDate, m.status].filter(Boolean).join(' · ')} />
                  </ListItem>
                ))}
              </List>
            </Box>
          )}

          <Stack direction="row" spacing={1} my={1.5} flexWrap="wrap" useFlexGap>
            <TextField select size="small" sx={{ minWidth: 150 }} label={t('decisionLog.fields.status')}
              value={d.status || ''} disabled={busy}
              onChange={e => patch({ status: e.target.value })}>
              {STATUSES.map(s => <MenuItem key={s} value={s}>{t(`decisionLog.status.${s}`)}</MenuItem>)}
            </TextField>
            <TextField select size="small" sx={{ minWidth: 170 }} label={t('decisionLog.fields.implementation')}
              value={d.implementationStatus || 'NOT_STARTED'} disabled={busy}
              onChange={e => patch({ implementationStatus: e.target.value })}>
              {IMPLS.map(s => <MenuItem key={s} value={s}>{t(`decisionLog.impl.${s}`)}</MenuItem>)}
            </TextField>
            <Tooltip title={t('decisionLog.actions.exportMarkdown')}>
              <Button size="small" startIcon={<DownloadIcon />} href={exportDecisionUrl(d.id, 'markdown')}>
                {t('decisionLog.actions.export')}
              </Button>
            </Tooltip>
          </Stack>
          {busy && <LinearProgress sx={{ mb: 1 }} />}

          <Divider sx={{ my: 1 }} />

          {/* AI copilot annotation (Req 7) */}
          <Section icon={<SmartToyIcon fontSize="small" />} title={t('decisionLog.detail.annotation')}
            defaultExpanded
            action={<IconButton size="small" onClick={refreshAnnotation}><RefreshIcon fontSize="small" /></IconButton>}>
            {!annotation && <Typography variant="body2" color="text.secondary">
              {t('decisionLog.detail.noAnnotation')}
            </Typography>}
            {annotation && (
              <>
                <SubHeading text={t('decisionLog.detail.summary')} />
                <Typography variant="body2" paragraph>{annotation.executiveSummary}</Typography>
                <BulletList heading={t('decisionLog.detail.keyImpacts')} items={annotation.keyImpacts} />
                <BulletList heading={t('decisionLog.detail.recommendations')} items={annotation.recommendations} />
              </>
            )}
          </Section>

          {/* Impact prediction (Req 4) */}
          <Section icon={<InsightsIcon fontSize="small" />} title={t('decisionLog.detail.impact')}
            action={
              <Button size="small" disabled={busy} onClick={runImpact}>
                {t('decisionLog.actions.predictImpact')}
              </Button>
            }>
            {impact ? (
              <>
                <Stack direction="row" spacing={1} mb={1}>
                  {impact.overallRisk && <Chip size="small"
                    color={impact.overallRisk === 'HIGH' ? 'error' : impact.overallRisk === 'MEDIUM' ? 'warning' : 'success'}
                    label={`${t('decisionLog.detail.overallRisk')}: ${impact.overallRisk}`} />}
                  {typeof impact.confidence === 'number' && <Chip size="small" variant="outlined"
                    label={t('decisionLog.detection.confidence', { pct: Math.round(impact.confidence * 100) })} />}
                </Stack>
                <Typography variant="body2" paragraph>{impact.summary}</Typography>
                {(impact.impacts || []).map((im, i) => (
                  <Typography key={i} variant="body2" sx={{ mb: 0.5 }}>
                    • <b>{im.dimension}</b> {im.area ? `(${im.area})` : ''} [{im.severity}] — {im.description}
                  </Typography>
                ))}
              </>
            ) : (
              <Typography variant="body2" color="text.secondary">—</Typography>
            )}
            <Box mt={1.5}>
              <Typography variant="caption" color="text.secondary">
                {t('decisionLog.detail.outcomePrompt')}
              </Typography>
              <Stack direction="row" spacing={1} mt={0.5}>
                <TextField size="small" fullWidth multiline maxRows={4}
                  label={t('decisionLog.fields.outcome')}
                  value={outcomeDraft} onChange={e => setOutcomeDraft(e.target.value)} />
                <Button size="small" variant="outlined" disabled={busy || !outcomeDraft.trim()}
                  onClick={saveOutcome}>
                  {t('decisionLog.actions.recordOutcome')}
                </Button>
              </Stack>
            </Box>
          </Section>

          {/* Related decisions + cross-system correlations (Req 5.2, 7.3, 11) */}
          <Section icon={<LinkIcon fontSize="small" />}
            title={`${t('decisionLog.detail.related')} / ${t('decisionLog.detail.correlations')}`}>
            {related.length > 0 && (
              <List dense disablePadding>
                {related.map(r => (
                  <ListItemButton key={r.id} onClick={() => setCurrentId(r.id)} disableGutters>
                    <ListItemText
                      primary={r.title}
                      secondary={`${t('decisionLog.detail.similarity')} ${Math.round((r.similarity || 0) * 100)}% · ${r.status || ''}`} />
                  </ListItemButton>
                ))}
              </List>
            )}
            {correlations.length > 0 && (
              <>
                <SubHeading text={t('decisionLog.detail.correlations')} />
                {correlations.map(c => (
                  <Typography key={c.id} variant="body2" sx={{ mb: 0.5 }}>
                    • <b>{c.targetType}</b> — {c.targetTitle}
                    {typeof c.confidence === 'number' ? ` (${Math.round(c.confidence * 100)}%)` : ''}
                  </Typography>
                ))}
              </>
            )}
            {related.length === 0 && correlations.length === 0 && (
              <Typography variant="body2" color="text.secondary">—</Typography>
            )}
          </Section>

          {/* Audit timeline (Req 5.3, 10) */}
          <Section icon={<HistoryIcon fontSize="small" />} title={t('decisionLog.detail.timeline')}>
            {timeline.length === 0 && <Typography variant="body2" color="text.secondary">—</Typography>}
            {timeline.map(ev => (
              <Typography key={ev.id} variant="body2" sx={{ mb: 0.5 }}>
                <Typography component="span" variant="caption" color="text.secondary">
                  {fmtDate(ev.occurredAt)}
                </Typography>
                {' '}<b>{ev.kind}</b> {ev.fromValue ? `${ev.fromValue} → ` : ''}{ev.toValue}
                {ev.note ? ` — ${ev.note}` : ''}{ev.changedBy ? ` (${ev.changedBy})` : ''}
              </Typography>
            ))}
          </Section>
        </Box>
      )}
    </Drawer>
  );
}

function Field({ label, value, multiline }) {
  if (!value) return null;
  return (
    <Box my={1}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={multiline ? { whiteSpace: 'pre-wrap' } : undefined}>{value}</Typography>
    </Box>
  );
}

function SubHeading({ text }) {
  return <Typography variant="subtitle2" sx={{ mt: 1 }}>{text}</Typography>;
}

function BulletList({ heading, items }) {
  if (!items || items.length === 0) return null;
  return (
    <>
      <SubHeading text={heading} />
      {items.map((it, i) => (
        <Typography key={i} variant="body2" sx={{ mb: 0.5 }}>• {typeof it === 'string' ? it : JSON.stringify(it)}</Typography>
      ))}
    </>
  );
}

function Section({ icon, title, action, defaultExpanded, children }) {
  return (
    <Accordion defaultExpanded={defaultExpanded} disableGutters sx={{ '&:before': { display: 'none' } }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1} alignItems="center" width="100%">
          {icon}
          <Typography variant="subtitle2" sx={{ flexGrow: 1 }}>{title}</Typography>
          {action && <Box onClick={e => e.stopPropagation()}>{action}</Box>}
        </Stack>
      </AccordionSummary>
      <AccordionDetails sx={{ pt: 0 }}>{children}</AccordionDetails>
    </Accordion>
  );
}
