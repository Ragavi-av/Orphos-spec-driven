import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip, Tooltip,
  Table, TableHead, TableRow, TableCell, TableBody, IconButton,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, Select, MenuItem, FormControl, InputLabel, FormControlLabel, Switch, Divider,
} from '@mui/material';
import AddIcon      from '@mui/icons-material/Add';
import EditIcon     from '@mui/icons-material/Edit';
import DeleteIcon   from '@mui/icons-material/Delete';
import RefreshIcon  from '@mui/icons-material/Refresh';
import BoltIcon     from '@mui/icons-material/Bolt';
import KeyIcon      from '@mui/icons-material/VpnKey';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

const BASE = '/api/admin/db-connections';

const TYPES = [
  { value: 'ARCADEDB', label: 'ArcadeDB', port: 2480 },
  { value: 'POSTGRES', label: 'PostgreSQL', port: 5432 },
  { value: 'NEO4J',    label: 'Neo4J', port: 7687 },
];
const SCHEMA_MODES = [
  { value: 'DEFAULT',  label: 'Default' },
  { value: 'EXISTING', label: 'Pre-existing' },
];

const EMPTY_FORM = { name: '', type: 'ARCADEDB', host: '', port: '', user: '', url: '', tls: false, secret: '' };

const defaultPort = (type) => (TYPES.find(t => t.value === type)?.port ?? '');

function truncate(s, n = 48) {
  if (!s) return '';
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

/**
 * Platform Operations → DB Connections (platform admin only): define external
 * database connections, validate them, and assign them to the services that use
 * a database. Changes are written to the connections registry; affected services
 * must be restarted to pick them up.
 */
export default function DbConnectionsTab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState(null);

  // create/edit dialog
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing]       = useState(null);
  const [form, setForm]             = useState(EMPTY_FORM);
  const [saving, setSaving]         = useState(false);
  const [dialogErr, setDialogErr]   = useState(null);
  const [testResult, setTestResult] = useState(null);
  const [testing, setTesting]       = useState(false);

  // delete + row test
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting]         = useState(false);
  const [rowTesting, setRowTesting]     = useState({});

  // assignment matrix
  const [rows, setRows]           = useState([]);      // AssignmentRow[]
  const [rowsDirty, setRowsDirty] = useState(false);
  const [savingRows, setSavingRows] = useState(false);
  const [applyResult, setApplyResult] = useState(null); // { affected, message }
  const [confirmData, setConfirmData] = useState(null); // { message, confirmations } when a risky change needs confirming

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      axios.get(BASE),
      axios.get(`${BASE}/assignments`),
    ])
      .then(([c, a]) => {
        setConnections(c.data?.connections || []);
        setRows(a.data?.assignments || []);
        setRowsDirty(false);
        setErr(null);
      })
      .catch(e => setErr(apiErrorMessage(e, t('admin.screens.dbConnections.errLoad', 'Could not load DB connections'))))
      .finally(() => setLoading(false));
  }, [t]);
  useEffect(() => { load(); }, [load]);

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  const openCreate = () => {
    setEditing(null); setForm(EMPTY_FORM);
    setDialogErr(null); setTestResult(null); setDialogOpen(true);
  };
  const openEdit = (c) => {
    setEditing(c);
    setForm({
      name: c.name || '', type: c.type || 'ARCADEDB',
      host: c.host || '', port: c.port ?? '', user: c.user || '',
      url: c.url || '', tls: c.options?.tls === 'true', secret: '',
    });
    setDialogErr(null); setTestResult(null); setDialogOpen(true);
  };

  const buildRequest = () => ({
    name: form.name.trim(),
    type: form.type,
    host: form.host.trim() || null,
    port: form.port === '' ? null : Number(form.port),
    user: form.user.trim() || null,
    url: form.url.trim() || null,
    options: form.type === 'ARCADEDB' && form.tls ? { tls: 'true' } : {},
    secret: form.secret || null,      // blank on edit → keep stored credential
  });

  const testInDialog = async () => {
    setTesting(true); setTestResult(null);
    try {
      const r = await axios.post(`${BASE}/validate`, buildRequest());
      setTestResult(r.data);
    } catch (e) {
      setTestResult({ ok: false, message: apiErrorMessage(e, t('admin.screens.dbConnections.errTest', 'Connection test failed')) });
    }
    setTesting(false);
  };

  const save = async () => {
    setSaving(true); setDialogErr(null);
    try {
      if (editing) await axios.put(`${BASE}/${editing.id}`, buildRequest());
      else         await axios.post(BASE, buildRequest());
      toast.success(editing
        ? t('admin.screens.dbConnections.updated', 'Connection updated.')
        : t('admin.screens.dbConnections.created', 'Connection created.'));
      setDialogOpen(false); load();
    } catch (e) {
      setDialogErr(apiErrorMessage(e, t('admin.screens.dbConnections.errSave', 'Save failed')));
    }
    setSaving(false);
  };

  const testRow = async (c) => {
    setRowTesting(m => ({ ...m, [c.id]: true }));
    try {
      const r = await axios.post(`${BASE}/${c.id}/validate`);
      const res = r.data || {};
      if (res.ok) toast.success(t('admin.screens.dbConnections.testOk', '{{name}}: connection OK ({{ms}} ms)', { name: c.name, ms: res.latencyMs ?? '—' }));
      else        toast.error(res.message || t('admin.screens.dbConnections.errTest', 'Connection test failed'));
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dbConnections.errTest', 'Connection test failed')));
    }
    setRowTesting(m => ({ ...m, [c.id]: false }));
    load();
  };

  const confirmDelete = async () => {
    setDeleting(true);
    try {
      await axios.delete(`${BASE}/${deleteTarget.id}`);
      toast.success(t('admin.screens.dbConnections.deleted', 'Connection deleted.'));
      setDeleteTarget(null); load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dbConnections.errDelete', 'Delete failed')));
    }
    setDeleting(false);
  };

  const editRow = (serviceId, patch) => {
    setRows(rs => rs.map(r => r.serviceId === serviceId ? { ...r, ...patch } : r));
    setRowsDirty(true);
  };

  const saveAssignments = async (confirm = false) => {
    setSavingRows(true); setApplyResult(null);
    try {
      const r = await axios.put(`${BASE}/assignments`, { assignments: rows, confirm });
      if (r.data?.requiresConfirmation) {
        setConfirmData(r.data);   // nothing was written — ask the operator to confirm
        setSavingRows(false);
        return;
      }
      setConfirmData(null);
      setApplyResult(r.data);
      setRowsDirty(false);
      toast.success(r.data?.message || t('admin.screens.dbConnections.assignSaved', 'Assignments saved.'));
      load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dbConnections.errAssign', 'Could not save assignments')));
    }
    setSavingRows(false);
  };

  const connName = (id) => connections.find(c => c.id === id)?.name || id;
  const fmt = (s) => s ? new Date(s).toLocaleString() : '—';

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5, gap: 1 }}>
        <Typography fontWeight={700} fontSize={15}>
          {t('admin.screens.dbConnections.title', 'Database Connections')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <IconButton size='small' onClick={load} title={t('admin.common.refresh', 'Refresh')}>
          <RefreshIcon fontSize='small' />
        </IconButton>
        <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openCreate}>
          {t('admin.screens.dbConnections.add', 'Add connection')}
        </Button>
      </Box>
      <Typography fontSize={13} color='#C3D3F2' mb={2}>
        {t('admin.screens.dbConnections.intro',
          'Define externally-hosted databases (on-prem or cloud), validate them, then assign them to the services that use a database. A connection can be shared by many services, each using its own database/schema. Assigned services must be restarted to pick up a change.')}
      </Typography>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {loading ? <CircularProgress size={22} /> : (
        <>
          {/* ── Connections ─────────────────────────────────────────── */}
          <Box sx={{ mb: 3 }}>
          <CollapsibleSection title={t('admin.screens.dbConnections.connectionsTitle', 'Connections')}
            count={connections.length} detailsSx={{ p: 0 }}>
          <Paper variant='outlined'>
            <Table size='small'>
              <TableHead>
                <TableRow>
                  <TableCell>{t('admin.screens.dbConnections.colName', 'Name')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colType', 'Type')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colEndpoint', 'Endpoint')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colStatus', 'Last test')}</TableCell>
                  <TableCell align='right'>{t('admin.common.actions', 'Actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {connections.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={5}>
                      <Typography fontSize={12} color='#7D92C4' py={1}>
                        {t('admin.screens.dbConnections.empty', 'No connections yet. Add one to get started.')}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {connections.map(c => (
                  <TableRow key={c.id} hover>
                    <TableCell>
                      <Typography fontSize={13} fontWeight={600}>
                        {c.name}
                        {c.hasCredential && (
                          <Tooltip title={t('admin.screens.dbConnections.credentialStored', 'Credential stored')}>
                            <KeyIcon sx={{ fontSize: 12, color: '#7D92C4', ml: 0.5, verticalAlign: 'middle' }} />
                          </Tooltip>
                        )}
                      </Typography>
                      <Typography fontSize={10} color='#7D92C4' sx={{ fontFamily: 'monospace' }}>{c.id}</Typography>
                    </TableCell>
                    <TableCell>
                      <Chip size='small' variant='outlined' label={c.type} sx={{ fontSize: 10 }} />
                    </TableCell>
                    <TableCell>
                      <Tooltip title={c.url || ''}>
                        <Typography fontSize={12} color='#C3D3F2' sx={{ fontFamily: 'monospace' }}>
                          {truncate(c.url)}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      {c.lastTestStatus
                        ? <Box>
                            <Tooltip title={c.lastTestError || ''}>
                              <Chip size='small'
                                label={c.lastTestStatus === 'OK'
                                  ? t('admin.screens.dbConnections.statusOk', 'OK')
                                  : t('admin.screens.dbConnections.statusFailed', 'Failed')}
                                sx={{ fontSize: 10, fontWeight: 700,
                                  bgcolor: c.lastTestStatus === 'OK' ? 'rgba(61, 220, 151, 0.14)' : 'rgba(255, 0, 0, 0.14)',
                                  color: c.lastTestStatus === 'OK' ? '#3DDC97' : '#FF6B81' }} />
                            </Tooltip>
                            {c.lastTestAt && (
                              <Typography fontSize={10} color='#7D92C4' mt={0.25}>{fmt(c.lastTestAt)}</Typography>
                            )}
                          </Box>
                        : <Typography fontSize={11} color='#7D92C4'>{t('admin.screens.dbConnections.statusNever', 'Never tested')}</Typography>}
                    </TableCell>
                    <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                      <Tooltip title={t('admin.screens.dbConnections.test', 'Test connection')}>
                        <span>
                          <IconButton size='small' onClick={() => testRow(c)} disabled={!!rowTesting[c.id]}>
                            {rowTesting[c.id] ? <CircularProgress size={14} /> : <BoltIcon sx={{ fontSize: 16 }} />}
                          </IconButton>
                        </span>
                      </Tooltip>
                      <Tooltip title={t('admin.common.edit', 'Edit')}>
                        <IconButton size='small' onClick={() => openEdit(c)}>
                          <EditIcon sx={{ fontSize: 16 }} />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title={t('admin.common.delete', 'Delete')}>
                        <IconButton size='small' onClick={() => setDeleteTarget(c)}>
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
          </Box>

          {/* ── Assignment matrix ───────────────────────────────────── */}
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5, gap: 1 }}>
            <Typography fontWeight={700} fontSize={14}>
              {t('admin.screens.dbConnections.assignTitle', 'Service assignments')}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Button size='small' variant='contained' onClick={() => saveAssignments()}
              disabled={savingRows || !rowsDirty}>
              {savingRows ? <CircularProgress size={14} /> : t('admin.screens.dbConnections.saveAssign', 'Save assignments')}
            </Button>
          </Box>
          <Typography fontSize={12} color='#C3D3F2' mb={1}>
            {t('admin.screens.dbConnections.assignIntro',
              'Leave a service unassigned to use the bundled default. "Pre-existing" points at a database you already created; "Default" lets the platform use/create its standard database.')}
          </Typography>

          {applyResult && applyResult.affected?.length > 0 && (
            <Alert severity='info' sx={{ mb: 2 }}>
              {t('admin.screens.dbConnections.restartHint', 'Restart these services to apply:')}{' '}
              {applyResult.affected.map(a => a.containerName).join(', ')}
            </Alert>
          )}

          <CollapsibleSection title={t('admin.screens.dbConnections.assignSectionTitle', 'Service assignments')}
            count={rows.length} detailsSx={{ p: 0 }}>
          <Paper variant='outlined'>
            <Table size='small'>
              <TableHead>
                <TableRow>
                  <TableCell>{t('admin.screens.dbConnections.colService', 'Service')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colConnection', 'Connection')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colDatabase', 'Database / schema')}</TableCell>
                  <TableCell>{t('admin.screens.dbConnections.colSchemaMode', 'Mode')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map(r => (
                  <TableRow key={r.serviceId} hover>
                    <TableCell>
                      <Typography fontSize={13} fontWeight={600}>{r.serviceId}</Typography>
                      <Typography fontSize={10} color='#7D92C4' sx={{ fontFamily: 'monospace' }}>{r.containerName}</Typography>
                    </TableCell>
                    <TableCell>
                      <FormControl size='small' sx={{ minWidth: 180 }}>
                        <Select displayEmpty value={r.connectionId || ''}
                          onChange={e => editRow(r.serviceId, { connectionId: e.target.value || null })}>
                          <MenuItem value='' sx={{ fontSize: 13, fontStyle: 'italic' }}>
                            {t('admin.screens.dbConnections.useDefault', '(bundled default)')}
                          </MenuItem>
                          {connections.map(c => (
                            <MenuItem key={c.id} value={c.id} sx={{ fontSize: 13 }}>{connName(c.id)}</MenuItem>
                          ))}
                        </Select>
                      </FormControl>
                      {!r.connectionId && r.effectiveConnectionName && (
                        <Typography fontSize={10} color='#7D92C4' mt={0.25}>
                          {t('admin.screens.dbConnections.resolvesTo', '→ {{name}}', { name: r.effectiveConnectionName })}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <TextField size='small' value={r.database || ''}
                        disabled={!r.connectionId}
                        placeholder={r.effectiveDatabase || t('admin.screens.dbConnections.dbPlaceholder', 'e.g. orphos')}
                        onChange={e => editRow(r.serviceId, { database: e.target.value })}
                        sx={{ width: 180 }} />
                      {r.effectiveDatabase && (
                        <Typography fontSize={10} color='#7D92C4' mt={0.25}>
                          {t('admin.screens.dbConnections.effectiveDb', 'Uses: {{db}}', { db: r.effectiveDatabase })}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <FormControl size='small' sx={{ minWidth: 140 }}>
                        <Select value={r.schemaMode || 'DEFAULT'} disabled={!r.connectionId}
                          onChange={e => editRow(r.serviceId, { schemaMode: e.target.value })}>
                          {SCHEMA_MODES.map(m => (
                            <MenuItem key={m.value} value={m.value} sx={{ fontSize: 13 }}>{m.label}</MenuItem>
                          ))}
                        </Select>
                      </FormControl>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
          </CollapsibleSection>
        </>
      )}

      {/* Create / Edit dialog */}
      <Dialog open={dialogOpen} onClose={() => !saving && setDialogOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>
          {editing
            ? t('admin.screens.dbConnections.editTitle', 'Edit connection')
            : t('admin.screens.dbConnections.createTitle', 'New connection')}
        </DialogTitle>
        <DialogContent>
          {dialogErr && <Alert severity='error' sx={{ mb: 2 }}>{dialogErr}</Alert>}
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <Box sx={{ display: 'flex', gap: 2 }}>
              <TextField size='small' label={t('admin.screens.dbConnections.fName', 'Name')} value={form.name}
                onChange={set('name')} fullWidth required />
              <FormControl size='small' sx={{ minWidth: 150 }}>
                <InputLabel>{t('admin.screens.dbConnections.fType', 'Type')}</InputLabel>
                <Select value={form.type} label={t('admin.screens.dbConnections.fType', 'Type')} onChange={set('type')}>
                  {TYPES.map(ty => <MenuItem key={ty.value} value={ty.value} sx={{ fontSize: 13 }}>{ty.label}</MenuItem>)}
                </Select>
              </FormControl>
            </Box>

            <Box sx={{ display: 'flex', gap: 2 }}>
              <TextField size='small' label={t('admin.screens.dbConnections.fHost', 'Host')} value={form.host}
                onChange={set('host')} fullWidth placeholder='db.example.com' />
              <TextField size='small' type='number' label={t('admin.screens.dbConnections.fPort', 'Port')}
                value={form.port} onChange={set('port')} sx={{ width: 120 }}
                placeholder={String(defaultPort(form.type))} />
            </Box>

            <Box sx={{ display: 'flex', gap: 2 }}>
              <TextField size='small' label={t('admin.screens.dbConnections.fUser', 'Username')} value={form.user}
                onChange={set('user')} fullWidth />
              <TextField size='small' type='password' label={t('admin.screens.dbConnections.fPassword', 'Password')}
                value={form.secret} onChange={set('secret')} fullWidth
                placeholder={editing?.hasCredential ? '••••••' : ''}
                helperText={editing?.hasCredential
                  ? t('admin.screens.dbConnections.secretKept', 'Credential stored — leave blank to keep it.')
                  : undefined} />
            </Box>

            {form.type === 'ARCADEDB' && (
              <FormControlLabel
                control={<Switch size='small' checked={form.tls}
                  onChange={e => setForm(f => ({ ...f, tls: e.target.checked }))} />}
                label={<Typography fontSize={13}>{t('admin.screens.dbConnections.fTls', 'Use TLS (https)')}</Typography>} />
            )}

            <Divider />
            <TextField size='small' label={t('admin.screens.dbConnections.fUrl', 'Explicit URL (optional)')} value={form.url}
              onChange={set('url')} fullWidth
              placeholder={form.type === 'POSTGRES' ? 'jdbc:postgresql://host:5432/'
                : form.type === 'NEO4J' ? 'bolt://host:7687' : 'http://host:2480'}
              helperText={t('admin.screens.dbConnections.urlHelp', 'Overrides host/port — use for cloud endpoints with a non-standard shape.')} />

            {testResult && (
              <Alert severity={testResult.ok ? 'success' : 'error'}>
                {testResult.ok
                  ? t('admin.screens.dbConnections.testResultOk', 'Connection OK — {{ms}} ms{{detail}}',
                      { ms: testResult.latencyMs ?? '—', detail: testResult.detail ? ` · ${testResult.detail}` : '' })
                  : (testResult.message || t('admin.screens.dbConnections.errTest', 'Connection test failed'))}
                {testResult.testedFrom && (
                  <Typography fontSize={11} color='inherit' mt={0.5}>
                    {t('admin.screens.dbConnections.testedFrom', 'Tested from: {{from}}', { from: testResult.testedFrom })}
                  </Typography>
                )}
              </Alert>
            )}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={testInDialog} disabled={testing || saving}
            startIcon={testing ? <CircularProgress size={14} /> : <BoltIcon />}>
            {t('admin.screens.dbConnections.test', 'Test connection')}
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button size='small' onClick={() => setDialogOpen(false)} disabled={saving}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' onClick={save}
            disabled={saving || !form.name.trim() || (!form.host.trim() && !form.url.trim())}>
            {saving ? <CircularProgress size={14} /> : t('admin.common.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={!!deleteTarget} onClose={() => !deleting && setDeleteTarget(null)} maxWidth='xs' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.dbConnections.deleteTitle', 'Delete connection?')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={13}>
            {t('admin.screens.dbConnections.deleteBody',
              '"{{name}}" will be removed and its stored credential deleted. Reassign any service using it first.',
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

      {/* Assignment change confirmation — a service is being re-pointed to a
          different database where the platform would create its schema on boot. */}
      <Dialog open={!!confirmData} onClose={() => !savingRows && setConfirmData(null)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.dbConnections.confirmTitle', 'Confirm database change')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={13} mb={1.5}>
            {confirmData?.message || t('admin.screens.dbConnections.confirmBody',
              'These services will be re-pointed to a different database where the platform creates its schema on boot. Confirm only if schema creation is safe and no data will be lost.')}
          </Typography>
          {(confirmData?.confirmations || []).map(c => (
            <Alert key={c.serviceId} severity='warning' sx={{ mb: 1 }}>
              <Typography fontSize={12} fontWeight={700}>{c.containerName || c.serviceId}</Typography>
              <Typography fontSize={12}>{c.reason}</Typography>
            </Alert>
          ))}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setConfirmData(null)} disabled={savingRows}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' color='warning' variant='contained'
            onClick={() => saveAssignments(true)} disabled={savingRows}>
            {savingRows ? <CircularProgress size={14} />
              : t('admin.screens.dbConnections.confirmApply', 'Confirm & save')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
