import { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip, Tooltip,
  Table, TableHead, TableRow, TableCell, TableBody, IconButton, LinearProgress,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, Select, MenuItem, FormControl, InputLabel, FormControlLabel, Switch,
  Radio, RadioGroup,
} from '@mui/material';
import AddIcon        from '@mui/icons-material/Add';
import EditIcon       from '@mui/icons-material/Edit';
import DeleteIcon     from '@mui/icons-material/Delete';
import RefreshIcon    from '@mui/icons-material/Refresh';
import PlayArrowIcon  from '@mui/icons-material/PlayArrow';
import PauseIcon      from '@mui/icons-material/Pause';
import HistoryIcon    from '@mui/icons-material/History';
import CancelIcon     from '@mui/icons-material/Cancel';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

const BASE = '/api/data-ingestion/admin';

const EMPTY_FORM = {
  name: '', mappingId: '', scheduleType: 'NONE', cron: '', runAt: '',
  loadMode: 'FULL', mergeStrategy: 'UPSERT', batchSize: 1000, maxRetries: 3,
  dependsOnJobId: '', enabled: true,
};

const RUN_STATUS_STYLE = {
  SUCCEEDED: { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
  FAILED:    { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
  RUNNING:   { bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF' },
  QUEUED:    { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  CANCELLED: { bg: '#243A72', color: '#C3D3F2' },
};

function runStatusChip(status) {
  const sty = RUN_STATUS_STYLE[status] || RUN_STATUS_STYLE.CANCELLED;
  return (
    <Chip size='small' label={status || '—'}
      sx={{ fontSize: 10, fontWeight: 700, bgcolor: sty.bg, color: sty.color }} />
  );
}

const fmt = (s) => s ? new Date(s).toLocaleString() : '—';

function duration(run) {
  if (!run?.startedAt) return '—';
  const end = run.finishedAt ? new Date(run.finishedAt) : new Date();
  const ms = end - new Date(run.startedAt);
  if (ms < 0) return '—';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  return `${min}m ${sec % 60}s`;
}

const ACTIVE = (st) => st === 'RUNNING' || st === 'QUEUED';

/** Data & AI → Ingestion Jobs: scheduled / on-demand ingestion job management. */
export default function IngestionJobsTab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [jobs, setJobs]         = useState([]);
  const [mappings, setMappings] = useState([]);
  const [loading, setLoading]   = useState(true);
  const [err, setErr]           = useState(null);

  // create/edit dialog
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing]       = useState(null);   // JobView | null
  const [form, setForm]             = useState(EMPTY_FORM);
  const [saving, setSaving]         = useState(false);
  const [dialogErr, setDialogErr]   = useState(null);

  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting]         = useState(false);
  const [rowBusy, setRowBusy]           = useState({});   // jobId -> bool

  // history dialog
  const [historyJob, setHistoryJob]     = useState(null); // JobView | null
  const [runs, setRuns]                 = useState([]);
  const [runsLoading, setRunsLoading]   = useState(false);
  const [runsErr, setRunsErr]           = useState(null);
  const pollRef = useRef(null);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      axios.get(`${BASE}/jobs`),
      axios.get(`${BASE}/mappings`),
    ])
      .then(([j, m]) => {
        setJobs(j.data?.jobs || []);
        setMappings(m.data?.mappings || []);
        setErr(null);
      })
      .catch(e => setErr(apiErrorMessage(e, t('admin.screens.ingestionJobs.errLoad', 'Could not load jobs'))))
      .finally(() => setLoading(false));
  }, [t]);
  useEffect(() => { load(); }, [load]);

  const mappingById = (id) => mappings.find(m => m.id === id);
  const mappingName = (id) => mappingById(id)?.name || id || '—';
  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  const scheduleSummary = (j) => {
    if (j.scheduleType === 'CRON')     return t('admin.screens.ingestionJobs.schedCron', 'cron: {{cron}}', { cron: j.cron });
    if (j.scheduleType === 'ONE_TIME') return t('admin.screens.ingestionJobs.schedOnce', 'once @ {{at}}', { at: fmt(j.runAt) });
    return t('admin.screens.ingestionJobs.schedManual', 'manual');
  };

  const openCreate = () => { setEditing(null); setForm(EMPTY_FORM); setDialogErr(null); setDialogOpen(true); };
  const openEdit = (j) => {
    setEditing(j);
    setForm({
      name: j.name || '', mappingId: j.mappingId || '',
      scheduleType: j.scheduleType || 'NONE', cron: j.cron || '',
      runAt: j.runAt ? j.runAt.slice(0, 16) : '',
      loadMode: j.loadMode || 'FULL', mergeStrategy: j.mergeStrategy || 'UPSERT',
      batchSize: j.batchSize ?? 1000, maxRetries: j.maxRetries ?? 3,
      dependsOnJobId: j.dependsOnJobId || '', enabled: j.enabled !== false,
    });
    setDialogErr(null); setDialogOpen(true);
  };

  const save = async () => {
    setSaving(true); setDialogErr(null);
    try {
      const req = {
        name: form.name.trim(),
        mappingId: form.mappingId,
        scheduleType: form.scheduleType,
        cron: form.scheduleType === 'CRON' ? form.cron.trim() : null,
        runAt: form.scheduleType === 'ONE_TIME' && form.runAt ? new Date(form.runAt).toISOString() : null,
        loadMode: form.loadMode,
        mergeStrategy: form.mergeStrategy,
        batchSize: Number(form.batchSize) || 1000,
        maxRetries: Number(form.maxRetries) || 0,
        dependsOnJobId: form.dependsOnJobId || null,
        enabled: form.enabled,
      };
      if (editing) await axios.put(`${BASE}/jobs/${editing.id}`, req);
      else         await axios.post(`${BASE}/jobs`, req);
      toast.success(editing
        ? t('admin.screens.ingestionJobs.updated', 'Job updated.')
        : t('admin.screens.ingestionJobs.created', 'Job created.'));
      setDialogOpen(false); load();
    } catch (e) {
      setDialogErr(apiErrorMessage(e, t('admin.screens.ingestionJobs.errSave', 'Save failed')));
    }
    setSaving(false);
  };

  const jobAction = async (j, action, okMsg) => {
    setRowBusy(m => ({ ...m, [j.id]: true }));
    try {
      const r = await axios.post(`${BASE}/jobs/${j.id}/${action}`);
      toast.success(r.data?.message || okMsg);
      load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.ingestionJobs.errAction', 'Action failed')));
    }
    setRowBusy(m => ({ ...m, [j.id]: false }));
  };

  const confirmDelete = async () => {
    setDeleting(true);
    try {
      await axios.delete(`${BASE}/jobs/${deleteTarget.id}`);
      toast.success(t('admin.screens.ingestionJobs.deleted', 'Job deleted.'));
      setDeleteTarget(null); load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.ingestionJobs.errDelete', 'Delete failed')));
    }
    setDeleting(false);
  };

  const loadRuns = useCallback((job) => {
    setRunsLoading(true); setRunsErr(null);
    axios.get(`${BASE}/jobs/${job.id}/runs`, { params: { limit: 50 } })
      .then(r => setRuns(r.data?.runs || []))
      .catch(e => setRunsErr(apiErrorMessage(e, t('admin.screens.ingestionJobs.errRuns', 'Could not load run history'))))
      .finally(() => setRunsLoading(false));
  }, [t]);

  const openHistory = (j) => { setHistoryJob(j); setRuns([]); loadRuns(j); };
  const closeHistory = () => { setHistoryJob(null); setRuns([]); };

  // While the history dialog shows QUEUED/RUNNING runs, poll each active run
  // every 3 s and merge the fresh RunView back into the table.
  useEffect(() => {
    clearInterval(pollRef.current);
    if (!historyJob) return undefined;
    const activeIds = runs.filter(r => ACTIVE(r.status)).map(r => r.id);
    if (activeIds.length === 0) return undefined;
    pollRef.current = setInterval(async () => {
      try {
        const fresh = await Promise.all(
          activeIds.map(id => axios.get(`${BASE}/runs/${id}`).then(r => r.data))
        );
        setRuns(rs => rs.map(r => fresh.find(f => f?.id === r.id) || r));
      } catch { /* transient poll failure — retry on next tick */ }
    }, 3000);
    return () => clearInterval(pollRef.current);
  }, [historyJob, runs]);

  const cancelRun = async (run) => {
    try {
      await axios.post(`${BASE}/runs/${run.id}/cancel`);
      toast.info(t('admin.screens.ingestionJobs.cancelRequested', 'Cancellation requested.'));
      loadRuns(historyJob);
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.ingestionJobs.errCancel', 'Cancel failed')));
    }
  };

  const chosenMapping = mappingById(form.mappingId);
  const incrementalAvailable = !!chosenMapping?.incrementalColumn;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.ingestionJobs.scopeResource', 'ingestion jobs')} />
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5, gap: 1 }}>
        <Typography fontWeight={700} fontSize={15}>
          {t('admin.screens.ingestionJobs.title', 'Ingestion Jobs')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <IconButton size='small' onClick={load} title={t('admin.common.refresh', 'Refresh')}>
          <RefreshIcon fontSize='small' />
        </IconButton>
        <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openCreate}>
          {t('admin.screens.ingestionJobs.add', 'New job')}
        </Button>
      </Box>
      <Typography fontSize={13} color='#C3D3F2' mb={2}>
        {t('admin.screens.ingestionJobs.intro',
          'Run data mappings on a schedule or on demand. Jobs load from the mapped source into the platform, with retries, batching, and incremental watermarks.')}
      </Typography>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {loading ? <CircularProgress size={22} /> : (
        <CollapsibleSection title={t('admin.screens.ingestionJobs.sectionTitle', 'Ingestion jobs')}
          count={jobs.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined'>
          <Table size='small'>
            <TableHead>
              <TableRow>
                <TableCell>{t('admin.screens.ingestionJobs.colName', 'Job')}</TableCell>
                <TableCell>{t('admin.screens.ingestionJobs.colSchedule', 'Schedule')}</TableCell>
                <TableCell>{t('admin.screens.ingestionJobs.colMode', 'Mode')}</TableCell>
                <TableCell>{t('admin.screens.ingestionJobs.colState', 'State')}</TableCell>
                <TableCell>{t('admin.screens.ingestionJobs.colNextRun', 'Next run')}</TableCell>
                <TableCell>{t('admin.screens.ingestionJobs.colLastRun', 'Last run')}</TableCell>
                <TableCell align='right'>{t('admin.common.actions', 'Actions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {jobs.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7}>
                    <Typography fontSize={12} color='#7D92C4' py={1}>
                      {t('admin.screens.ingestionJobs.empty', 'No jobs yet. Create a mapping first, then schedule it here.')}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {jobs.map(j => (
                <TableRow key={j.id} hover>
                  <TableCell>
                    <Typography fontSize={13} fontWeight={600}>{j.name}</Typography>
                    <Typography fontSize={10} color='#7D92C4'>{mappingName(j.mappingId)}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography fontSize={12} sx={{ fontFamily: 'monospace' }} color='#C3D3F2'>
                      {scheduleSummary(j)}
                    </Typography>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Chip size='small' variant='outlined' label={j.loadMode} sx={{ fontSize: 10, mr: 0.5 }} />
                    <Chip size='small' variant='outlined' label={j.mergeStrategy} sx={{ fontSize: 10 }} />
                  </TableCell>
                  <TableCell>
                    {j.paused ? (
                      <Chip size='small' label={t('admin.screens.ingestionJobs.paused', 'Paused')}
                        sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
                    ) : (
                      <Chip size='small'
                        label={j.enabled !== false
                          ? t('admin.common.enabled', 'Enabled')
                          : t('admin.common.disabled', 'Disabled')}
                        sx={{ fontSize: 10, fontWeight: 700,
                          bgcolor: j.enabled !== false ? 'rgba(61, 220, 151, 0.14)' : '#0F2450',
                          color:   j.enabled !== false ? '#3DDC97' : '#C3D3F2' }} />
                    )}
                  </TableCell>
                  <TableCell>
                    <Typography fontSize={12} color='#C3D3F2'>{fmt(j.nextRunAt)}</Typography>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {j.lastRunStatus ? runStatusChip(j.lastRunStatus) : <Typography fontSize={12} color='#7D92C4'>—</Typography>}
                    {j.lastRunAt && <Typography fontSize={10} color='#7D92C4'>{fmt(j.lastRunAt)}</Typography>}
                  </TableCell>
                  <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                    <Tooltip title={t('admin.screens.ingestionJobs.runNow', 'Run now')}>
                      <span>
                        <IconButton size='small' disabled={!!rowBusy[j.id]}
                          onClick={() => jobAction(j, 'run', t('admin.screens.ingestionJobs.runQueued', 'Run queued.'))}>
                          {rowBusy[j.id] ? <CircularProgress size={14} /> : <PlayArrowIcon sx={{ fontSize: 16, color: '#3DDC97' }} />}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={j.paused
                        ? t('admin.screens.ingestionJobs.resume', 'Resume')
                        : t('admin.screens.ingestionJobs.pause', 'Pause')}>
                      <span>
                        <IconButton size='small' disabled={!!rowBusy[j.id]}
                          onClick={() => jobAction(j, j.paused ? 'resume' : 'pause',
                            j.paused
                              ? t('admin.screens.ingestionJobs.resumed', 'Job resumed.')
                              : t('admin.screens.ingestionJobs.pausedMsg', 'Job paused.'))}>
                          {j.paused ? <PlayArrowIcon sx={{ fontSize: 16 }} /> : <PauseIcon sx={{ fontSize: 16 }} />}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.ingestionJobs.history', 'Run history')}>
                      <IconButton size='small' onClick={() => openHistory(j)}>
                        <HistoryIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.common.edit', 'Edit')}>
                      <IconButton size='small' onClick={() => openEdit(j)}>
                        <EditIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.common.delete', 'Delete')}>
                      <IconButton size='small' onClick={() => setDeleteTarget(j)}>
                        <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      {/* Create / Edit dialog */}
      <Dialog open={dialogOpen} onClose={() => !saving && setDialogOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>
          {editing
            ? t('admin.screens.ingestionJobs.editTitle', 'Edit job')
            : t('admin.screens.ingestionJobs.createTitle', 'New ingestion job')}
        </DialogTitle>
        <DialogContent>
          {dialogErr && <Alert severity='error' sx={{ mb: 2 }}>{dialogErr}</Alert>}
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <TextField size='small' label={t('admin.screens.ingestionJobs.fName', 'Name')} value={form.name}
              onChange={set('name')} fullWidth required />
            <FormControl size='small' fullWidth required>
              <InputLabel>{t('admin.screens.ingestionJobs.fMapping', 'Mapping')}</InputLabel>
              <Select value={form.mappingId} label={t('admin.screens.ingestionJobs.fMapping', 'Mapping')}
                onChange={e => {
                  const mid = e.target.value;
                  setForm(f => ({
                    ...f, mappingId: mid,
                    // FULL if the newly chosen mapping cannot do incremental
                    loadMode: mappingById(mid)?.incrementalColumn ? f.loadMode : 'FULL',
                  }));
                }}>
                {mappings.map(m => (
                  <MenuItem key={m.id} value={m.id} sx={{ fontSize: 13 }}>
                    {m.name} — {m.sourceTable} → {m.targetClass}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <Box>
              <Typography fontSize={12} fontWeight={700} color='#C3D3F2' mb={0.5}>
                {t('admin.screens.ingestionJobs.fSchedule', 'Schedule')}
              </Typography>
              <RadioGroup row value={form.scheduleType} onChange={set('scheduleType')}>
                <FormControlLabel value='NONE' control={<Radio size='small' />}
                  label={<Typography fontSize={13}>{t('admin.screens.ingestionJobs.schedManual', 'manual')}</Typography>} />
                <FormControlLabel value='CRON' control={<Radio size='small' />}
                  label={<Typography fontSize={13}>{t('admin.screens.ingestionJobs.schedCronLabel', 'cron')}</Typography>} />
                <FormControlLabel value='ONE_TIME' control={<Radio size='small' />}
                  label={<Typography fontSize={13}>{t('admin.screens.ingestionJobs.schedOnceLabel', 'one time')}</Typography>} />
              </RadioGroup>
              {form.scheduleType === 'CRON' && (
                <TextField size='small' label={t('admin.screens.ingestionJobs.fCron', 'Cron expression')}
                  value={form.cron} onChange={set('cron')} fullWidth sx={{ mt: 1 }}
                  placeholder='0 0 2 * * *'
                  helperText={t('admin.screens.ingestionJobs.cronHelp',
                    '6 fields (sec min hour day month weekday), e.g. "0 0 2 * * *" = daily at 02:00.')} />
              )}
              {form.scheduleType === 'ONE_TIME' && (
                <TextField size='small' type='datetime-local' label={t('admin.screens.ingestionJobs.fRunAt', 'Run at')}
                  value={form.runAt} onChange={set('runAt')} fullWidth sx={{ mt: 1 }}
                  InputLabelProps={{ shrink: true }} />
              )}
            </Box>

            <Box sx={{ display: 'flex', gap: 2 }}>
              <FormControl size='small' fullWidth>
                <InputLabel>{t('admin.screens.ingestionJobs.fLoadMode', 'Load mode')}</InputLabel>
                <Select value={form.loadMode} label={t('admin.screens.ingestionJobs.fLoadMode', 'Load mode')}
                  onChange={set('loadMode')}>
                  <MenuItem value='FULL' sx={{ fontSize: 13 }}>FULL</MenuItem>
                  <MenuItem value='INCREMENTAL' disabled={!incrementalAvailable} sx={{ fontSize: 13 }}>
                    INCREMENTAL{!incrementalAvailable
                      ? ` — ${t('admin.screens.ingestionJobs.noIncremental', 'mapping has no incremental column')}` : ''}
                  </MenuItem>
                </Select>
              </FormControl>
              <FormControl size='small' fullWidth>
                <InputLabel>{t('admin.screens.ingestionJobs.fMerge', 'Merge strategy')}</InputLabel>
                <Select value={form.mergeStrategy} label={t('admin.screens.ingestionJobs.fMerge', 'Merge strategy')}
                  onChange={set('mergeStrategy')}>
                  <MenuItem value='UPSERT' sx={{ fontSize: 13 }}>UPSERT</MenuItem>
                  <MenuItem value='INSERT' sx={{ fontSize: 13 }}>INSERT</MenuItem>
                </Select>
              </FormControl>
            </Box>

            <Box sx={{ display: 'flex', gap: 2 }}>
              <TextField size='small' type='number' label={t('admin.screens.ingestionJobs.fBatch', 'Batch size')}
                value={form.batchSize} onChange={set('batchSize')} sx={{ width: 140 }}
                inputProps={{ min: 1 }} />
              <TextField size='small' type='number' label={t('admin.screens.ingestionJobs.fRetries', 'Max retries')}
                value={form.maxRetries} onChange={set('maxRetries')} sx={{ width: 140 }}
                inputProps={{ min: 0, max: 10 }} />
              <FormControl size='small' sx={{ flex: 1 }}>
                <InputLabel>{t('admin.screens.ingestionJobs.fDependsOn', 'Depends on')}</InputLabel>
                <Select value={form.dependsOnJobId} label={t('admin.screens.ingestionJobs.fDependsOn', 'Depends on')}
                  onChange={set('dependsOnJobId')}>
                  <MenuItem value='' sx={{ fontSize: 13, color: '#7D92C4' }}>
                    {t('admin.screens.ingestionJobs.noDependency', '(none)')}
                  </MenuItem>
                  {jobs.filter(j => !editing || j.id !== editing.id).map(j => (
                    <MenuItem key={j.id} value={j.id} sx={{ fontSize: 13 }}>{j.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Box>

            <FormControlLabel
              control={<Switch size='small' checked={form.enabled}
                onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />}
              label={<Typography fontSize={13}>{t('admin.common.enabled', 'Enabled')}</Typography>} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setDialogOpen(false)} disabled={saving}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' onClick={save}
            disabled={saving || !form.name.trim() || !form.mappingId
              || (form.scheduleType === 'CRON' && !form.cron.trim())
              || (form.scheduleType === 'ONE_TIME' && !form.runAt)}>
            {saving ? <CircularProgress size={14} /> : t('admin.common.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={!!deleteTarget} onClose={() => !deleting && setDeleteTarget(null)} maxWidth='xs' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.ingestionJobs.deleteTitle', 'Delete job?')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={13}>
            {t('admin.screens.ingestionJobs.deleteBody',
              '"{{name}}" and its schedule will be removed. Run history is kept.',
              { name: deleteTarget?.name })}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setDeleteTarget(null)} disabled={deleting}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' color='error' variant='contained' onClick={confirmDelete} disabled={deleting}>
            {deleting ? <CircularProgress size={14} /> : t('admin.common.delete', 'Delete')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Run history */}
      <Dialog open={!!historyJob} onClose={closeHistory} maxWidth='md' fullWidth>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
          {t('admin.screens.ingestionJobs.historyTitle', 'Run history — {{name}}', { name: historyJob?.name })}
          <Box sx={{ flex: 1 }} />
          <IconButton size='small' onClick={() => loadRuns(historyJob)} disabled={runsLoading}>
            <RefreshIcon fontSize='small' />
          </IconButton>
        </DialogTitle>
        <DialogContent>
          {runsErr && <Alert severity='error' sx={{ mb: 2 }}>{runsErr}</Alert>}
          {runsLoading ? <CircularProgress size={22} sx={{ my: 2 }} /> : (
            <Table size='small'>
              <TableHead>
                <TableRow>
                  <TableCell>{t('admin.screens.ingestionJobs.colStatus', 'Status')}</TableCell>
                  <TableCell>{t('admin.screens.ingestionJobs.colTrigger', 'Trigger')}</TableCell>
                  <TableCell>{t('admin.screens.ingestionJobs.colStarted', 'Started')}</TableCell>
                  <TableCell>{t('admin.screens.ingestionJobs.colDuration', 'Duration')}</TableCell>
                  <TableCell>{t('admin.screens.ingestionJobs.colRecords', 'Records (r / w / f)')}</TableCell>
                  <TableCell sx={{ minWidth: 120 }}>{t('admin.screens.ingestionJobs.colProgress', 'Progress')}</TableCell>
                  <TableCell align='right' />
                </TableRow>
              </TableHead>
              <TableBody>
                {runs.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={7}>
                      <Typography fontSize={12} color='#7D92C4' py={1}>
                        {t('admin.screens.ingestionJobs.noRuns', 'No runs yet.')}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {runs.map(run => (
                  <TableRow key={run.id} hover>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {runStatusChip(run.status)}
                      {run.attempt > 1 && (
                        <Typography fontSize={10} color='#7D92C4'>
                          {t('admin.screens.ingestionJobs.attempt', 'attempt {{n}}', { n: run.attempt })}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Typography fontSize={12} color='#C3D3F2'>{run.triggeredBy || '—'}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography fontSize={12} color='#C3D3F2'>{fmt(run.startedAt)}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography fontSize={12} color='#C3D3F2'>{duration(run)}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography fontSize={12} color='#C3D3F2'>
                        {run.recordsRead ?? 0} / {run.recordsWritten ?? 0} /{' '}
                        <Box component='span' sx={{ color: (run.recordsFailed ?? 0) > 0 ? '#FF6B81' : 'inherit' }}>
                          {run.recordsFailed ?? 0}
                        </Box>
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <LinearProgress variant='determinate'
                          value={Math.max(0, Math.min(100, run.progressPercent ?? (run.status === 'SUCCEEDED' ? 100 : 0)))}
                          sx={{ flex: 1, height: 6, borderRadius: 3 }} />
                        <Typography fontSize={10} color='#7D92C4'>
                          {Math.round(run.progressPercent ?? (run.status === 'SUCCEEDED' ? 100 : 0))}%
                        </Typography>
                      </Box>
                    </TableCell>
                    <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                      {run.error && (
                        <Tooltip title={run.error}>
                          <Chip size='small' label={t('admin.screens.ingestionJobs.error', 'error')}
                            sx={{ fontSize: 10, bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', mr: 0.5 }} />
                        </Tooltip>
                      )}
                      {run.status === 'RUNNING' && (
                        <Tooltip title={t('admin.screens.ingestionJobs.cancel', 'Cancel run')}>
                          <IconButton size='small' onClick={() => cancelRun(run)}>
                            <CancelIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                          </IconButton>
                        </Tooltip>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={closeHistory}>{t('admin.common.close', 'Close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
