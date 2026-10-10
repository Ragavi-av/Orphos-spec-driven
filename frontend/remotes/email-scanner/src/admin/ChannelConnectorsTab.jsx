import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip,
  TextField, MenuItem, Select, FormControl, InputLabel, FormControlLabel, Switch,
  Dialog, DialogTitle, DialogContent, DialogActions, IconButton, Tooltip, Divider,
  Table, TableBody, TableCell, TableHead, TableRow,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import AddIcon from '@mui/icons-material/Add';
import SyncIcon from '@mui/icons-material/Sync';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import ViewListIcon from '@mui/icons-material/ViewList';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { useToast } from 'eip-shell-context/ToastProvider';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import ScheduleEditor, { defaultSchedule } from 'eip-shell-context/ScheduleEditor';

const API = '/api/mail-insight/admin/channels';

// Channel platforms the connector registry implements today (Slack, Teams) vs.
// declared-but-pending (Discord, Google Chat) — shown disabled so the intent is visible.
const KINDS = [
  { value: 'SLACK',       label: 'Slack',                       enabled: true },
  { value: 'MS_TEAMS',    label: 'Microsoft Teams',             enabled: true },
  { value: 'DISCORD',     label: 'Discord (coming soon)',       enabled: false },
  { value: 'GOOGLE_CHAT', label: 'Google Chat (coming soon)',   enabled: false },
];

const KIND_LABEL = Object.fromEntries(KINDS.map(k => [k.value, k.label]));

const defaultConfig = (kind) => {
  switch (kind) {
    case 'MS_TEAMS': return { directoryId: '', clientId: '', graphBaseUrl: '' };
    case 'SLACK':
    default:         return {};
  }
};
const defaultCredential = (kind) => {
  if (kind === 'MS_TEAMS') return { clientSecret: '' };
  return { botToken: '' };   // SLACK
};
const hasCredentialInput = (kind, cred) => {
  if (kind === 'MS_TEAMS') return !!cred.clientSecret;
  return !!cred.botToken;    // SLACK
};
// The account-id hint shown when adding a channel to monitor.
const externalIdHint = (kind) => kind === 'MS_TEAMS'
  ? 'teamId/channelId (both Graph ids)'
  : 'Slack channel id (e.g. C0123ABCD)';

const emptyDraft = (kind = 'SLACK') => ({
  id: null,
  kind,
  name: '',
  config: defaultConfig(kind),
  credential: defaultCredential(kind),
  defaultScanWindowDays: 7,
  enabled: true,
  scheduled: false,
  schedule: null,
  credentialConfigured: false,
});

const isValidDraft = (draft) => {
  if (!draft.name.trim()) return false;
  const k = KINDS.find(x => x.value === draft.kind);
  return !!(k && k.enabled);
};

/**
 * Communication Channels — reusable Slack/Teams/… connections whose messages are
 * ingested, classified and surfaced in the same Tasks/Notifications feed as
 * email. Each connection holds one or more monitored accounts (channels), can be
 * enabled/disabled, scheduled, and scanned on demand.
 */
export default function ChannelConnectorsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [draft, setDraft] = useState(emptyDraft());
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState(null);
  const [busyId, setBusyId] = useState(null);

  // Accounts sub-dialog state
  const [acctConn, setAcctConn] = useState(null);   // the connection whose accounts we're managing

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get(`${API}/connections`);
      setRows(Array.isArray(r.data?.result) ? r.data.result : []);
    } catch (e) {
      setError(e?.response?.data?.error || e?.message || 'Failed to load channel connections.');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const openNew = () => { setDraft(emptyDraft()); setTestResult(null); setDialogOpen(true); };

  const openEdit = (row) => {
    const kind = row.kind || 'SLACK';
    setDraft({
      id: row.id,
      kind,
      name: row.name || '',
      config: { ...defaultConfig(kind), ...(row.config || {}) },
      credential: defaultCredential(kind),   // never pre-filled; blank keeps existing
      defaultScanWindowDays: row.defaultScanWindowDays || 7,
      enabled: row.enabled !== false,
      scheduled: !!row.schedule,
      schedule: row.schedule || null,
      credentialConfigured: !!row.credentialConfigured,
    });
    setTestResult(null);
    setDialogOpen(true);
  };

  const changeKind = (kind) =>
    setDraft(d => ({ ...d, kind, config: defaultConfig(kind), credential: defaultCredential(kind) }));

  const setCfg = (k, v) => setDraft(d => ({ ...d, config: { ...d.config, [k]: v } }));
  const setCred = (k, v) => setDraft(d => ({ ...d, credential: { ...d.credential, [k]: v } }));

  const payload = () => ({
    kind: draft.kind,
    name: draft.name,
    config: draft.config,
    // Omit credential when blank so an edit keeps the stored secret.
    credential: hasCredentialInput(draft.kind, draft.credential) ? draft.credential : undefined,
    defaultScanWindowDays: Number(draft.defaultScanWindowDays) || 7,
    enabled: draft.enabled,
    schedule: draft.scheduled ? (draft.schedule || defaultSchedule()) : undefined,
  });

  const test = async () => {
    setTestResult(null);
    try {
      const r = await axios.post(`${API}/connections/test`, payload());
      setTestResult(r.data);
    } catch (e) {
      setTestResult({ ok: false, message: e?.response?.data?.error || e?.message || 'Test failed' });
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      if (draft.id) await axios.put(`${API}/connections/${draft.id}`, payload());
      else          await axios.post(`${API}/connections`, payload());
      toast.success('Channel connection saved.');
      setDialogOpen(false);
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Save failed.');
    }
    setSaving(false);
  };

  const remove = async (row) => {
    if (!window.confirm('Delete this channel connection and all its monitored channels? Already-ingested items are kept.')) return;
    try {
      await axios.delete(`${API}/connections/${row.id}`);
      toast.success('Channel connection deleted.');
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Delete failed');
    }
  };

  const scanNow = async (row) => {
    setBusyId(row.id);
    try {
      await axios.post(`${API}/connections/${row.id}/scan`);
      toast.success('Scan started in the background.');
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Scan failed');
    }
    setBusyId(null);
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.channels.scopeResource', 'communication channels')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
            {t('admin.screens.channels.title', 'Communication Channels')}
          </Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.channels.subtitle',
              'Connect Slack, Microsoft Teams and other chat platforms. Messages are classified like email and appear in the same Tasks & Notifications feed, tagged by channel.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.channels.refresh', 'Refresh')}</Button>
          <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openNew}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.channels.add', 'Add connection')}</Button>
        </Box>
      </Box>

      {error && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}

      <CollapsibleSection title={t('admin.screens.channels.sectionTitle', 'Channel connections')}
        count={rows.length} detailsSx={{ p: 0 }}>
      <Paper variant='outlined' sx={{ borderRadius: 2 }}>
        {rows.length === 0 ? (
          <Typography fontSize={13} color='#7D92C4' sx={{ p: 3 }}>
            {t('admin.screens.channels.empty', 'No channel connections yet. Add one to start ingesting messages from Slack or Microsoft Teams.')}
          </Typography>
        ) : (
          <Table size='small'>
            <TableHead>
              <TableRow>
                {['Name', 'Channel', 'Channels monitored', 'Credential', 'Enabled', ''].map((h, i) => (
                  <TableCell key={i} sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map(row => (
                <TableRow key={row.id} hover>
                  <TableCell sx={{ fontSize: 13 }}>{row.name || '—'}</TableCell>
                  <TableCell>
                    <Chip size='small' label={KIND_LABEL[row.kind] || row.kind} sx={{ fontSize: 10 }} />
                    {row.supported === false && (
                      <Chip size='small' color='warning' label='pending' sx={{ fontSize: 9, ml: 0.5 }} />
                    )}
                  </TableCell>
                  <TableCell sx={{ fontSize: 13 }}>{row.accountCount ?? 0}</TableCell>
                  <TableCell>{row.credentialConfigured
                    ? <Chip size='small' color='success' icon={<CheckCircleIcon sx={{ fontSize: 13 }} />} label='stored' sx={{ fontSize: 10 }} />
                    : <Chip size='small' label='none' sx={{ fontSize: 10 }} />}</TableCell>
                  <TableCell>{row.enabled !== false
                    ? <Chip size='small' color='success' label='On' sx={{ fontSize: 10 }} />
                    : <Chip size='small' label='Off' sx={{ fontSize: 10 }} />}</TableCell>
                  <TableCell align='right'>
                    <Tooltip title={t('admin.screens.channels.manageAccounts', 'Manage channels')}>
                      <IconButton size='small' onClick={() => setAcctConn(row)}><ViewListIcon sx={{ fontSize: 18 }} /></IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.channels.scanNow', 'Scan now')}>
                      <span>
                        <IconButton size='small' onClick={() => scanNow(row)} disabled={busyId === row.id || row.enabled === false}>
                          {busyId === row.id ? <CircularProgress size={16} /> : <SyncIcon sx={{ fontSize: 18 }} />}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.channels.edit', 'Edit')}>
                      <IconButton size='small' onClick={() => openEdit(row)}><EditIcon sx={{ fontSize: 18 }} /></IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.channels.delete', 'Delete')}>
                      <IconButton size='small' onClick={() => remove(row)}><DeleteIcon sx={{ fontSize: 18, color: '#FF6B81' }} /></IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>
      </CollapsibleSection>

      {/* Add / edit connection dialog */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {draft.id ? t('admin.screens.channels.editTitle', 'Edit channel connection')
                    : t('admin.screens.channels.addTitle', 'Add channel connection')}
        </DialogTitle>
        <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
          <FormControl size='small' fullWidth>
            <InputLabel>{t('admin.screens.channels.kind', 'Channel')}</InputLabel>
            <Select label='Channel' value={draft.kind} onChange={e => changeKind(e.target.value)}>
              {KINDS.map(k => <MenuItem key={k.value} value={k.value} disabled={!k.enabled} sx={{ fontSize: 13 }}>{k.label}</MenuItem>)}
            </Select>
          </FormControl>

          <TextField size='small' label={t('admin.screens.channels.name', 'Display name')} value={draft.name}
            onChange={e => setDraft(d => ({ ...d, name: e.target.value }))}
            placeholder={draft.kind === 'SLACK' ? 'e.g. Acme Slack workspace' : 'e.g. Acme Microsoft 365'} />

          {draft.kind === 'MS_TEAMS' && (
            <>
              <Typography fontSize={11} color='#7D92C4'>
                {t('admin.screens.channels.teamsHint', 'App-only Microsoft Graph. Register an Azure AD app with the ChannelMessage.Read.All application permission (and ChannelMessage.Send to allow replies) and grant admin consent.')}
              </Typography>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Directory (tenant) ID' sx={{ flex: 1, minWidth: 220 }} value={draft.config.directoryId}
                  onChange={e => setCfg('directoryId', e.target.value)} />
                <TextField size='small' label='Application (client) ID' sx={{ flex: 1, minWidth: 220 }} value={draft.config.clientId}
                  onChange={e => setCfg('clientId', e.target.value)} />
              </Box>
              <TextField size='small' label='Graph base URL (optional)' value={draft.config.graphBaseUrl}
                onChange={e => setCfg('graphBaseUrl', e.target.value)} placeholder='https://graph.microsoft.com/v1.0' />
            </>
          )}

          {draft.kind === 'SLACK' && (
            <Typography fontSize={11} color='#7D92C4'>
              {t('admin.screens.channels.slackHint', 'Create a Slack app with a bot token (xoxb-…) holding channels:history / groups:history (and chat:write to allow replies). Invite the bot to each channel you monitor.')}
            </Typography>
          )}

          <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>
            {t('admin.screens.channels.credentials', 'Credentials')}
            {draft.id && draft.credentialConfigured && (
              <Chip size='small' icon={<CheckCircleIcon sx={{ fontSize: 14 }} />} label={t('admin.screens.channels.stored', 'stored')} sx={{ ml: 1, fontSize: 10 }} />
            )}
          </Typography>

          {draft.kind === 'SLACK' && (
            <TextField size='small' type='password' label='Bot token (xoxb-…)' value={draft.credential.botToken}
              onChange={e => setCred('botToken', e.target.value)} autoComplete='new-password'
              helperText={draft.id ? t('admin.screens.channels.leaveBlankKeep', 'Leave blank to keep the stored secret') : ''} />
          )}
          {draft.kind === 'MS_TEAMS' && (
            <TextField size='small' type='password' label='Client secret' value={draft.credential.clientSecret}
              onChange={e => setCred('clientSecret', e.target.value)} autoComplete='new-password'
              helperText={draft.id ? t('admin.screens.channels.leaveBlankKeep', 'Leave blank to keep the stored secret') : ''} />
          )}

          <Divider />
          <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
            <TextField size='small' type='number' label='Default scan window (days)' sx={{ width: 220 }}
              value={draft.defaultScanWindowDays}
              onChange={e => setDraft(d => ({ ...d, defaultScanWindowDays: Number(e.target.value) || 7 }))} />
          </Box>

          <FormControlLabel control={<Switch checked={draft.enabled} onChange={e => setDraft(d => ({ ...d, enabled: e.target.checked }))} />}
            label={<Typography fontSize={13}>{t('admin.screens.channels.enabled', 'Enabled (included in scheduled scans)')}</Typography>} />

          <FormControlLabel control={<Switch checked={draft.scheduled}
            onChange={e => setDraft(d => ({ ...d, scheduled: e.target.checked, schedule: e.target.checked ? (d.schedule || defaultSchedule()) : null }))} />}
            label={<Typography fontSize={13}>{t('admin.screens.channels.customSchedule', 'Custom scan schedule (otherwise scanned on every sweep)')}</Typography>} />
          {draft.scheduled && draft.enabled && (
            <Box sx={{ p: 1.5, border: '1px solid #243A72', borderRadius: 1, bgcolor: '#152652' }}>
              <ScheduleEditor value={draft.schedule} onChange={s => setDraft(d => ({ ...d, schedule: s }))} />
            </Box>
          )}

          {testResult && (
            <Alert severity={testResult.ok ? 'success' : 'error'} sx={{ fontSize: 13 }}>
              {testResult.ok
                ? t('admin.screens.channels.testOk', 'Connection OK.')
                : `${t('admin.screens.channels.testFail', 'Connection failed')}: ${testResult.message}`}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 2, py: 1.5 }}>
          <Button size='small' onClick={test} sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.channels.test', 'Test connection')}
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button size='small' onClick={() => setDialogOpen(false)} sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.channels.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' onClick={save} disabled={saving || !isValidDraft(draft)}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            {saving ? t('admin.screens.channels.saving', 'Saving…') : t('admin.screens.channels.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Manage-accounts sub-dialog */}
      <ChannelAccountsDialog connection={acctConn} onClose={() => setAcctConn(null)} onChanged={load} />
    </Box>
  );
}

/**
 * Manage the monitored channels (accounts) within one connection: list, add,
 * enable/disable, scan, delete.
 */
function ChannelAccountsDialog({ connection, onClose, onChanged }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [externalId, setExternalId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [scanWindowDays, setScanWindowDays] = useState('');
  const [adding, setAdding] = useState(false);
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    if (!connection) return;
    setLoading(true);
    try {
      const r = await axios.get(`${API}/accounts`, { params: { connectionId: connection.id } });
      setAccounts(Array.isArray(r.data?.result) ? r.data.result : []);
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Failed to load channels');
    }
    setLoading(false);
  }, [connection, toast]);

  useEffect(() => {
    if (connection) { setExternalId(''); setDisplayName(''); setScanWindowDays(''); load(); }
  }, [connection, load]);

  const add = async () => {
    setAdding(true);
    try {
      await axios.post(`${API}/accounts`, {
        channelConnectionId: connection.id,
        externalId: externalId.trim(),
        displayName: displayName.trim(),
        scanWindowDays: scanWindowDays ? Number(scanWindowDays) : undefined,
      });
      toast.success('Channel added.');
      setExternalId(''); setDisplayName(''); setScanWindowDays('');
      await load(); onChanged?.();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Add failed');
    }
    setAdding(false);
  };

  const toggle = async (a, enabled) => {
    try {
      await axios.patch(`${API}/accounts/${a.id}`, { enabled });
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Update failed');
    }
  };

  const scan = async (a) => {
    setBusyId(a.id);
    try {
      await axios.post(`${API}/accounts/${a.id}/scan`);
      toast.success('Scan started in the background.');
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Scan failed');
    }
    setBusyId(null);
  };

  const remove = async (a) => {
    if (!window.confirm('Stop monitoring this channel? Already-ingested items are kept.')) return;
    try {
      await axios.delete(`${API}/accounts/${a.id}`);
      await load(); onChanged?.();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Delete failed');
    }
  };

  const kind = connection?.kind;
  const canAdd = !adding && externalId.trim();

  return (
    <Dialog open={!!connection} onClose={onClose} maxWidth='sm' fullWidth>
      <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
        {t('admin.screens.channels.accountsTitle', 'Monitored channels')} — {connection?.name}
      </DialogTitle>
      <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
        {loading ? <Box sx={{ textAlign: 'center', py: 2 }}><CircularProgress size={20} /></Box> : (
          accounts.length === 0
            ? <Typography fontSize={13} color='#7D92C4'>{t('admin.screens.channels.noAccounts', 'No channels monitored yet.')}</Typography>
            : (
              <Table size='small'>
                <TableHead>
                  <TableRow>
                    {['Channel', 'Id', 'Enabled', 'Last scan', ''].map((h, i) => (
                      <TableCell key={i} sx={{ fontSize: 11, fontWeight: 700, color: '#C3D3F2' }}>{h}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {accounts.map(a => (
                    <TableRow key={a.id} hover>
                      <TableCell sx={{ fontSize: 12 }}>{a.displayName || '—'}</TableCell>
                      <TableCell sx={{ fontSize: 11, color: '#C3D3F2', maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.externalId}</TableCell>
                      <TableCell>
                        <Switch size='small' checked={a.enabled !== false} onChange={e => toggle(a, e.target.checked)} />
                      </TableCell>
                      <TableCell sx={{ fontSize: 11 }}>
                        <Chip size='small'
                          label={a.lastScanStatus || 'NEVER_RUN'}
                          color={a.lastScanStatus === 'SUCCESS' ? 'success' : a.lastScanStatus === 'FAILED' ? 'error' : 'default'}
                          sx={{ fontSize: 9 }} />
                      </TableCell>
                      <TableCell align='right'>
                        <Tooltip title={t('admin.screens.channels.scanNow', 'Scan now')}>
                          <span>
                            <IconButton size='small' onClick={() => scan(a)} disabled={busyId === a.id}>
                              {busyId === a.id ? <CircularProgress size={14} /> : <SyncIcon sx={{ fontSize: 16 }} />}
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title={t('admin.screens.channels.delete', 'Delete')}>
                          <IconButton size='small' onClick={() => remove(a)}><DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} /></IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )
        )}

        <Divider />
        <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>{t('admin.screens.channels.addChannel', 'Add a channel')}</Typography>
        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
          <TextField size='small' label='Channel id' sx={{ flex: 2, minWidth: 220 }} value={externalId}
            onChange={e => setExternalId(e.target.value)} helperText={externalIdHint(kind)} />
          <TextField size='small' label='Display name' sx={{ flex: 1, minWidth: 160 }} value={displayName}
            onChange={e => setDisplayName(e.target.value)} />
          <TextField size='small' type='number' label='Window (days)' sx={{ width: 130 }} value={scanWindowDays}
            onChange={e => setScanWindowDays(e.target.value)} placeholder='default' />
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 2, py: 1.5 }}>
        <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={add} disabled={!canAdd}
          sx={{ textTransform: 'none', fontSize: 12 }}>
          {adding ? t('admin.screens.channels.adding', 'Adding…') : t('admin.screens.channels.addChannelBtn', 'Add channel')}
        </Button>
        <Box sx={{ flex: 1 }} />
        <Button size='small' onClick={onClose} sx={{ textTransform: 'none', fontSize: 12 }}>
          {t('admin.screens.channels.close', 'Close')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
