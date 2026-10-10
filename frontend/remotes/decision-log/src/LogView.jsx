import { useEffect, useMemo, useState } from 'react';
import {
  Box, Paper, Stack, Chip, Typography, CircularProgress, Alert, ToggleButtonGroup,
  ToggleButton, TextField, MenuItem, List, ListItemButton, ListItemText, Divider, Button,
} from '@mui/material';
import { scopeFeed, FEED_CAP } from 'eip-shell-context/feedScope';
import ViewTimelineIcon from '@mui/icons-material/ViewTimeline';
import ViewListIcon from '@mui/icons-material/ViewList';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { useTranslation } from 'react-i18next';
import { listDecisions, PRIORITY_COLOR, STATUS_COLOR, fmtDay } from './api';
import ApprovalSeal from './ApprovalSeal';

const STATUSES = ['DETECTED', 'PENDING', 'APPROVED', 'REJECTED'];
const PRIORITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

/**
 * Historical decision view (Req 5): chronological timeline (grouped by
 * month), flat list, and hierarchical by-area modes, with quick filters.
 */
export default function LogView({ onSelect }) {
  const { t } = useTranslation();
  const [mode, setMode] = useState('timeline');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [status, setStatus] = useState('');
  const [priority, setPriority] = useState('');
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    setLoading(true);
    const params = { limit: 200 };
    if (status) params.status = status;
    if (priority) params.priority = priority;
    listDecisions(params)
      .then(data => { setRows(Array.isArray(data) ? data : []); setError(null); })
      .catch(() => setError(t('decisionLog.loadError')))
      .finally(() => setLoading(false));
  }, [status, priority, t]);

  // The log legitimately holds the full history, so the STATUS filter stays
  // "all" by default — but rendering 200 rows at once buried the recent ones.
  // Cap the view and disclose the remainder instead.
  const feed = scopeFeed(rows, { scope: 'all', cap: expanded ? 0 : FEED_CAP * 2 });

  const grouped = useMemo(() => {
    const by = new Map();
    for (const d of feed.rows) {
      const key = mode === 'byArea'
        ? (d.decisionArea || d.domain || '—')
        : String(d.decidedAt || '').slice(0, 7) || '—';
      if (!by.has(key)) by.set(key, []);
      by.get(key).push(d);
    }
    return [...by.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1));
  }, [feed.rows, mode]);

  if (loading) return <CircularProgress />;
  if (error) return <Alert severity="warning">{error}</Alert>;

  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
        <ToggleButtonGroup size="small" exclusive value={mode} onChange={(_, v) => v && setMode(v)}>
          <ToggleButton value="timeline"><ViewTimelineIcon fontSize="small" sx={{ mr: 0.5 }} />{t('decisionLog.views.timeline')}</ToggleButton>
          <ToggleButton value="list"><ViewListIcon fontSize="small" sx={{ mr: 0.5 }} />{t('decisionLog.views.list')}</ToggleButton>
          <ToggleButton value="byArea"><AccountTreeIcon fontSize="small" sx={{ mr: 0.5 }} />{t('decisionLog.views.byArea')}</ToggleButton>
        </ToggleButtonGroup>
        <TextField select size="small" sx={{ minWidth: 140 }} label={t('decisionLog.fields.status')}
          value={status} onChange={e => setStatus(e.target.value)}>
          <MenuItem value="">—</MenuItem>
          {STATUSES.map(s => <MenuItem key={s} value={s}>{t(`decisionLog.status.${s}`)}</MenuItem>)}
        </TextField>
        <TextField select size="small" sx={{ minWidth: 140 }} label={t('decisionLog.fields.priority')}
          value={priority} onChange={e => setPriority(e.target.value)}>
          <MenuItem value="">—</MenuItem>
          {PRIORITIES.map(p => <MenuItem key={p} value={p}>{p}</MenuItem>)}
        </TextField>
      </Stack>

      {rows.length === 0 && (
        <Paper sx={{ p: 3 }}><Typography color="text.secondary">{t('decisionLog.empty')}</Typography></Paper>
      )}

      {feed.hidden > 0 && (
        <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
          <Typography fontSize={12} color="text.secondary">
            {t('decisionLog.showingOf', 'Showing {{shown}} of {{total}} decisions', {
              shown: feed.shown, total: feed.total })}
          </Typography>
          <Button size="small" onClick={() => setExpanded(true)} sx={{ textTransform: 'none', py: 0 }}>
            {t('decisionLog.showAll', 'Show all')}
          </Button>
        </Stack>
      )}

      {mode === 'list' ? (
        <Paper>
          <List disablePadding>
            {rows.map((d, i) => (
              <Box key={d.id || i}>
                <DecisionRow d={d} t={t} onSelect={onSelect} />
                {i < rows.length - 1 && <Divider component="li" />}
              </Box>
            ))}
          </List>
        </Paper>
      ) : (
        grouped.map(([group, items]) => (
          <Box key={group} mb={2}>
            <Typography variant="overline" color="text.secondary">{group}</Typography>
            <Paper>
              <List disablePadding>
                {items.map((d, i) => (
                  <Box key={d.id || i}>
                    <DecisionRow d={d} t={t} onSelect={onSelect} />
                    {i < items.length - 1 && <Divider component="li" />}
                  </Box>
                ))}
              </List>
            </Paper>
          </Box>
        ))
      )}
    </Box>
  );
}

export function DecisionRow({ d, t, onSelect, secondaryOverride }) {
  const aiDetected = String(d.detectionSource || '').endsWith('_RAG');
  return (
    <ListItemButton alignItems="flex-start" onClick={() => onSelect(d.id)}>
      <ListItemText
        primary={
          <Stack direction="row" alignItems="center" gap={1} flexWrap="wrap" useFlexGap>
            {aiDetected && <SmartToyIcon fontSize="small" color="secondary" titleAccess={t('decisionLog.detection.aiFlagged')} />}
            {d.escalated && <WarningAmberIcon fontSize="small" color="warning" titleAccess={t('decisionLog.detail.escalated')} />}
            <Typography component="span" fontWeight={500}>{d.title}</Typography>
            <Chip size="small" color={STATUS_COLOR[d.status] || 'default'}
              label={t(`decisionLog.status.${d.status}`, d.status || 'PENDING')} />
            {d.priorityLevel && <Chip size="small" variant="outlined"
              color={PRIORITY_COLOR[d.priorityLevel] || 'default'} label={d.priorityLevel} />}
            {aiDetected && typeof d.confidenceScore === 'number' && (
              <Chip size="small" variant="outlined"
                label={t('decisionLog.detection.confidence', { pct: Math.round(d.confidenceScore * 100) })} />
            )}
          </Stack>
        }
        secondary={
          secondaryOverride ?? (
            <>
              <Typography variant="body2" component="span" display="block" noWrap>
                {d.description || d.rationale}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {[d.domain, d.decisionArea, d.decidedBy, fmtDay(d.decidedAt)].filter(Boolean).join(' · ')}
              </Typography>
            </>
          )
        }
      />
      {/* Trailing 34px column, as the artboard lays it out — the seal sits
          outside the text block so it stays put whatever the title wraps to.
          Renders nothing unless the decision is approved. */}
      <ApprovalSeal status={d.status} />
    </ListItemButton>
  );
}
