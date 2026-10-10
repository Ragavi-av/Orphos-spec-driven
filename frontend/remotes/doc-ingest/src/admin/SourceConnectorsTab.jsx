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
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { useToast } from 'eip-shell-context/ToastProvider';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';

// Backends the connector registry implements today (S3, FTP) vs. declared-but-pending
// (SFTP/Google Drive/SharePoint/Confluence) — shown disabled so the intent is visible.
const KINDS = [
  { value: 'S3',         label: 'S3 / S3-compatible (MinIO, Ceph)', enabled: true },
  { value: 'FTP',        label: 'FTP',                              enabled: true },
  { value: 'SFTP',       label: 'SFTP',                             enabled: true },
  { value: 'SHAREPOINT', label: 'SharePoint / Teams',              enabled: true },
  { value: 'GDRIVE',     label: 'Google Drive (coming soon)',      enabled: false },
  { value: 'CONFLUENCE', label: 'Confluence (coming soon)',        enabled: false },
];

const defaultConfig = (kind) => {
  switch (kind) {
    case 'FTP':        return { host: '', port: 21, basePath: '/', passive: true };
    case 'SFTP':       return { host: '', port: 22, basePath: '/', strictHostKey: false };
    case 'SHAREPOINT': return { tenantId: '', clientId: '', siteId: '', driveId: '', basePath: '' };
    case 'S3':
    default:           return { bucket: '', prefix: '', region: 'us-east-1', endpoint: '', pathStyle: false };
  }
};
const defaultCredential = (kind) => {
  if (kind === 'FTP' || kind === 'SFTP') return { username: '', password: '' };
  if (kind === 'SHAREPOINT')             return { clientSecret: '' };
  return { accessKeyId: '', secretAccessKey: '' };
};

const hasCredentialInput = (kind, cred) => {
  if (kind === 'FTP' || kind === 'SFTP') return !!(cred.username || cred.password);
  if (kind === 'SHAREPOINT')             return !!cred.clientSecret;
  return !!(cred.accessKeyId || cred.secretAccessKey);
};

const isValidDraft = (draft) => {
  switch (draft.kind) {
    case 'FTP':
    case 'SFTP':       return !!draft.config.host;
    case 'SHAREPOINT': return !!(draft.config.tenantId && draft.config.clientId && draft.config.siteId);
    case 'S3':
    default:           return !!draft.config.bucket;
  }
};

const emptyDraft = (kind = 'S3') => ({
  id: null,
  kind,
  displayName: '',
  config: defaultConfig(kind),
  credential: defaultCredential(kind),
  defaultDepartment: '',
  defaultCategory: '',
  defaultFolder: '',
  documentType: '',
  enabled: true,
});

/**
 * Common Folder Connections pool — reusable storage/ingestion folder backends (S3, FTP, …).
 * A connection can be used as a document STORAGE location (Ingestion Defaults → Storage layout)
 * and/or as a pull-based ingestion SOURCE; its capabilities are shown per row.
 */
export default function SourceConnectorsTab() {
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

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/doc-ingest/admin/sources');
      setRows(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.folderConnections.loadError', 'Failed to load folder connections.'));
    }
    setLoading(false);
  }, [t]);

  useEffect(() => { load(); }, [load]);

  const openNew = () => { setDraft(emptyDraft()); setTestResult(null); setDialogOpen(true); };

  const openEdit = (row) => {
    const kind = row.kind || 'S3';
    let config = {};
    try { config = row.configJson ? JSON.parse(row.configJson) : {}; } catch { config = {}; }
    setDraft({
      id: row.id,
      kind,
      displayName: row.displayName || '',
      config: { ...defaultConfig(kind), ...config },
      credential: defaultCredential(kind),   // never pre-filled; blank keeps existing
      defaultDepartment: row.defaultDepartment || '',
      defaultCategory: row.defaultCategory || '',
      defaultFolder: row.defaultFolder || '',
      documentType: row.documentType || '',
      enabled: row.enabled !== false,
      hasCredential: row.hasCredential,
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
    displayName: draft.displayName,
    config: draft.config,
    // Omit credential when blank so an edit keeps the stored secret.
    credential: hasCredentialInput(draft.kind, draft.credential) ? draft.credential : undefined,
    defaultDepartment: draft.defaultDepartment,
    defaultCategory: draft.defaultCategory,
    defaultFolder: draft.defaultFolder,
    documentType: draft.documentType,
    enabled: draft.enabled,
  });

  const test = async () => {
    setTestResult(null);
    try {
      const r = await axios.post('/api/doc-ingest/admin/sources/test', payload());
      setTestResult(r.data);
    } catch (e) {
      setTestResult({ ok: false, message: e?.response?.data?.error || e?.message || 'Test failed' });
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      if (draft.id) await axios.put(`/api/doc-ingest/admin/sources/${draft.id}`, payload());
      else          await axios.post('/api/doc-ingest/admin/sources', payload());
      toast.success(t('admin.screens.folderConnections.saved', 'Folder connection saved.'));
      setDialogOpen(false);
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || t('admin.screens.folderConnections.saveError', 'Save failed.'));
    }
    setSaving(false);
  };

  const remove = async (row) => {
    if (!window.confirm(t('admin.screens.folderConnections.deleteConfirm', 'Delete this folder connection? Already-ingested documents are kept.'))) return;
    try {
      await axios.delete(`/api/doc-ingest/admin/sources/${row.id}`);
      toast.success(t('admin.screens.folderConnections.deleted', 'Folder connection deleted.'));
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Delete failed');
    }
  };

  const syncNow = async (row) => {
    setBusyId(row.id);
    try {
      const r = await axios.post(`/api/doc-ingest/admin/sources/${row.id}/sync`);
      toast.success(t('admin.screens.folderConnections.syncStarted', 'Sync started: {{n}} document(s) submitted.', { n: r.data?.documentsSubmitted ?? 0 }));
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.error || e?.message || 'Sync failed');
    }
    setBusyId(null);
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.folderConnections.scopeResource', 'folder connections')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
            {t('admin.screens.folderConnections.title', 'Folder Connections')}
          </Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.folderConnections.subtitle', 'Reusable folder backends (S3, FTP, …) shared across the platform — use one as a document storage location (Ingestion Defaults) and/or an ingestion source. Capabilities per connection are shown below.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.folderConnections.refresh', 'Refresh')}</Button>
          <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openNew}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.folderConnections.add', 'Add connection')}</Button>
        </Box>
      </Box>

      {error && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}

      <CollapsibleSection title={t('admin.screens.folderConnections.sectionTitle', 'Folder connections')}
        count={rows.length} detailsSx={{ p: 0 }}>
      <Paper variant='outlined' sx={{ borderRadius: 2 }}>
        {rows.length === 0 ? (
          <Typography fontSize={13} color='#7D92C4' sx={{ p: 3 }}>
            {t('admin.screens.folderConnections.empty', 'No folder connections yet. Add one to store documents in — or pull documents from — an S3 bucket or FTP folder.')}
          </Typography>
        ) : (
          <Table size='small'>
            <TableHead>
              <TableRow>
                {['Name', 'Kind', 'Capabilities', 'Enabled', 'Last sync', 'Status', ''].map((h, i) => (
                  <TableCell key={i} sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map(row => (
                <TableRow key={row.id} hover>
                  <TableCell sx={{ fontSize: 13 }}>{row.displayName || '—'}</TableCell>
                  <TableCell>
                    <Chip size='small' label={row.kind} sx={{ fontSize: 10 }} />
                    {row.supported === false && (
                      <Chip size='small' color='warning' label='pending' sx={{ fontSize: 9, ml: 0.5 }} />
                    )}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                      {(row.capabilities || []).map(c => (
                        <Chip key={c} size='small' variant='outlined'
                          color={c === 'WRITE' ? 'primary' : 'default'}
                          label={c === 'BROWSE' ? 'Storage' : c === 'INGEST' ? 'Ingest' : c}
                          sx={{ fontSize: 9 }} />
                      ))}
                    </Box>
                  </TableCell>
                  <TableCell>{row.enabled !== false
                    ? <Chip size='small' color='success' label='On' sx={{ fontSize: 10 }} />
                    : <Chip size='small' label='Off' sx={{ fontSize: 10 }} />}</TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>{row.lastSyncAt || '—'}</TableCell>
                  <TableCell sx={{ fontSize: 12 }}>
                    <Chip size='small'
                      label={row.lastSyncStatus || 'NEVER_RUN'}
                      color={row.lastSyncStatus === 'SUCCESS' ? 'success' : row.lastSyncStatus === 'FAILED' ? 'error' : 'default'}
                      sx={{ fontSize: 10 }} />
                  </TableCell>
                  <TableCell align='right'>
                    <Tooltip title={t('admin.screens.folderConnections.syncNow', 'Sync now')}>
                      <span>
                        <IconButton size='small' onClick={() => syncNow(row)}
                          disabled={busyId === row.id || !(row.capabilities || []).includes('INGEST')}>
                          {busyId === row.id ? <CircularProgress size={16} /> : <SyncIcon sx={{ fontSize: 18 }} />}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.folderConnections.edit', 'Edit')}>
                      <IconButton size='small' onClick={() => openEdit(row)}><EditIcon sx={{ fontSize: 18 }} /></IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.folderConnections.delete', 'Delete')}>
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

      {/* Add / edit dialog */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {draft.id ? t('admin.screens.folderConnections.editTitle', 'Edit folder connection')
                    : t('admin.screens.folderConnections.addTitle', 'Add folder connection')}
        </DialogTitle>
        <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
          <FormControl size='small' fullWidth>
            <InputLabel>{t('admin.screens.folderConnections.kind', 'Backend')}</InputLabel>
            <Select label='Backend' value={draft.kind} onChange={e => changeKind(e.target.value)}>
              {KINDS.map(k => <MenuItem key={k.value} value={k.value} disabled={!k.enabled} sx={{ fontSize: 13 }}>{k.label}</MenuItem>)}
            </Select>
          </FormControl>

          <TextField size='small' label={t('admin.screens.folderConnections.displayName', 'Display name')} value={draft.displayName}
            onChange={e => setDraft(d => ({ ...d, displayName: e.target.value }))} />

          <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>{t('admin.screens.folderConnections.connection', 'Connection')}</Typography>

          {draft.kind === 'S3' && (
            <>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Bucket' sx={{ flex: 1, minWidth: 200 }} value={draft.config.bucket}
                  onChange={e => setCfg('bucket', e.target.value)} />
                <TextField size='small' label='Prefix (folder)' sx={{ flex: 1, minWidth: 200 }} value={draft.config.prefix}
                  onChange={e => setCfg('prefix', e.target.value)} placeholder='e.g. contracts/2026/' />
              </Box>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Region' sx={{ flex: 1, minWidth: 160 }} value={draft.config.region}
                  onChange={e => setCfg('region', e.target.value)} />
                <TextField size='small' label='Endpoint (optional, S3-compatible)' sx={{ flex: 2, minWidth: 220 }} value={draft.config.endpoint}
                  onChange={e => setCfg('endpoint', e.target.value)} placeholder='e.g. http://minio:9000' />
              </Box>
              <FormControlLabel control={<Switch checked={!!draft.config.pathStyle} onChange={e => setCfg('pathStyle', e.target.checked)} />}
                label={<Typography fontSize={13}>{t('admin.screens.folderConnections.pathStyle', 'Path-style access (required for MinIO)')}</Typography>} />
            </>
          )}

          {draft.kind === 'FTP' && (
            <>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Host' sx={{ flex: 2, minWidth: 200 }} value={draft.config.host}
                  onChange={e => setCfg('host', e.target.value)} placeholder='e.g. ftp.example.com' />
                <TextField size='small' label='Port' type='number' sx={{ flex: 1, minWidth: 100 }} value={draft.config.port}
                  onChange={e => setCfg('port', Number(e.target.value) || 21)} />
              </Box>
              <TextField size='small' label='Base path' value={draft.config.basePath}
                onChange={e => setCfg('basePath', e.target.value)} placeholder='/' />
              <FormControlLabel control={<Switch checked={draft.config.passive !== false} onChange={e => setCfg('passive', e.target.checked)} />}
                label={<Typography fontSize={13}>{t('admin.screens.folderConnections.passive', 'Passive mode (recommended)')}</Typography>} />
            </>
          )}

          {draft.kind === 'SFTP' && (
            <>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Host' sx={{ flex: 2, minWidth: 200 }} value={draft.config.host}
                  onChange={e => setCfg('host', e.target.value)} placeholder='e.g. sftp.example.com' />
                <TextField size='small' label='Port' type='number' sx={{ flex: 1, minWidth: 100 }} value={draft.config.port}
                  onChange={e => setCfg('port', Number(e.target.value) || 22)} />
              </Box>
              <TextField size='small' label='Base path' value={draft.config.basePath}
                onChange={e => setCfg('basePath', e.target.value)} placeholder='/' />
              <FormControlLabel control={<Switch checked={!!draft.config.strictHostKey} onChange={e => setCfg('strictHostKey', e.target.checked)} />}
                label={<Typography fontSize={13}>{t('admin.screens.folderConnections.strictHostKey', 'Strict host-key checking')}</Typography>} />
            </>
          )}

          {draft.kind === 'SHAREPOINT' && (
            <>
              <Typography fontSize={11} color='#7D92C4'>
                {t('admin.screens.folderConnections.sharepointHint', 'App-only Microsoft Graph. Register an Azure AD app with the Sites.ReadWrite.All application permission and grant admin consent.')}
              </Typography>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Directory (tenant) ID' sx={{ flex: 1, minWidth: 220 }} value={draft.config.tenantId}
                  onChange={e => setCfg('tenantId', e.target.value)} />
                <TextField size='small' label='Application (client) ID' sx={{ flex: 1, minWidth: 220 }} value={draft.config.clientId}
                  onChange={e => setCfg('clientId', e.target.value)} />
              </Box>
              <TextField size='small' label='Site ID (Graph)' value={draft.config.siteId}
                onChange={e => setCfg('siteId', e.target.value)}
                placeholder='e.g. contoso.sharepoint.com,<siteGuid>,<webGuid>' />
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size='small' label='Drive ID (optional)' sx={{ flex: 1, minWidth: 220 }} value={draft.config.driveId}
                  onChange={e => setCfg('driveId', e.target.value)} placeholder='defaults to the site’s document library' />
                <TextField size='small' label='Base folder (optional)' sx={{ flex: 1, minWidth: 200 }} value={draft.config.basePath}
                  onChange={e => setCfg('basePath', e.target.value)} placeholder='e.g. Shared Documents/Contracts' />
              </Box>
            </>
          )}

          <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>
            {t('admin.screens.folderConnections.credentials', 'Credentials')}
            {draft.id && draft.hasCredential && (
              <Chip size='small' icon={<CheckCircleIcon sx={{ fontSize: 14 }} />} label={t('admin.screens.folderConnections.stored', 'stored')} sx={{ ml: 1, fontSize: 10 }} />
            )}
          </Typography>

          {draft.kind === 'S3' && (
            <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
              <TextField size='small' label='Access Key ID' sx={{ flex: 1, minWidth: 200 }} value={draft.credential.accessKeyId}
                onChange={e => setCred('accessKeyId', e.target.value)} autoComplete='off' />
              <TextField size='small' type='password' label='Secret Access Key' sx={{ flex: 1, minWidth: 200 }} value={draft.credential.secretAccessKey}
                onChange={e => setCred('secretAccessKey', e.target.value)} autoComplete='new-password'
                helperText={draft.id ? t('admin.screens.folderConnections.leaveBlankKeep', 'Leave blank to keep the stored secret') : ''} />
            </Box>
          )}

          {(draft.kind === 'FTP' || draft.kind === 'SFTP') && (
            <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
              <TextField size='small' label='Username' sx={{ flex: 1, minWidth: 200 }} value={draft.credential.username}
                onChange={e => setCred('username', e.target.value)} autoComplete='off'
                placeholder={draft.kind === 'FTP' ? 'blank = anonymous' : ''} />
              <TextField size='small' type='password' label='Password' sx={{ flex: 1, minWidth: 200 }} value={draft.credential.password}
                onChange={e => setCred('password', e.target.value)} autoComplete='new-password'
                helperText={draft.id ? t('admin.screens.folderConnections.leaveBlankKeep', 'Leave blank to keep the stored secret') : ''} />
            </Box>
          )}

          {draft.kind === 'SHAREPOINT' && (
            <TextField size='small' type='password' label='Client secret' value={draft.credential.clientSecret}
              onChange={e => setCred('clientSecret', e.target.value)} autoComplete='new-password'
              helperText={draft.id ? t('admin.screens.folderConnections.leaveBlankKeep', 'Leave blank to keep the stored secret') : ''} />
          )}

          <Divider />
          <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>
            {t('admin.screens.folderConnections.filing', 'Filing (applied to documents pulled from this source)')}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
            <TextField size='small' label='Default department' sx={{ flex: 1, minWidth: 160 }} value={draft.defaultDepartment}
              onChange={e => setDraft(d => ({ ...d, defaultDepartment: e.target.value }))} />
            <TextField size='small' label='Default category' sx={{ flex: 1, minWidth: 160 }} value={draft.defaultCategory}
              onChange={e => setDraft(d => ({ ...d, defaultCategory: e.target.value }))} />
          </Box>
          <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
            <TextField size='small' label='Default folder' sx={{ flex: 1, minWidth: 160 }} value={draft.defaultFolder}
              onChange={e => setDraft(d => ({ ...d, defaultFolder: e.target.value }))} />
            <TextField size='small' label='Document type' sx={{ flex: 1, minWidth: 160 }} value={draft.documentType}
              onChange={e => setDraft(d => ({ ...d, documentType: e.target.value }))} />
          </Box>
          <FormControlLabel control={<Switch checked={draft.enabled} onChange={e => setDraft(d => ({ ...d, enabled: e.target.checked }))} />}
            label={<Typography fontSize={13}>{t('admin.screens.folderConnections.enabled', 'Enabled (included in scheduled sync)')}</Typography>} />

          {testResult && (
            <Alert severity={testResult.ok ? 'success' : 'error'} sx={{ fontSize: 13 }}>
              {testResult.ok
                ? t('admin.screens.folderConnections.testOk', 'Connection OK ({{n}} sample item(s) visible).', { n: testResult.sampleCount ?? 0 })
                : `${t('admin.screens.folderConnections.testFail', 'Connection failed')}: ${testResult.message}`}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 2, py: 1.5 }}>
          <Button size='small' onClick={test} sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.folderConnections.test', 'Test connection')}
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button size='small' onClick={() => setDialogOpen(false)} sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.folderConnections.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' onClick={save} disabled={saving || !isValidDraft(draft)}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            {saving ? t('admin.screens.folderConnections.saving', 'Saving…') : t('admin.screens.folderConnections.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
