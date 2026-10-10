import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip, Tooltip,
  Table, TableHead, TableRow, TableCell, TableBody, Switch, IconButton,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, Select, MenuItem, FormControl, InputLabel, FormControlLabel,
  Autocomplete,
} from '@mui/material';
import AddIcon           from '@mui/icons-material/Add';
import EditIcon          from '@mui/icons-material/Edit';
import DeleteIcon        from '@mui/icons-material/Delete';
import RefreshIcon       from '@mui/icons-material/Refresh';
import BoltIcon          from '@mui/icons-material/Bolt';
import ManageSearchIcon  from '@mui/icons-material/ManageSearch';
import KeyIcon           from '@mui/icons-material/VpnKey';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

const BASE = '/api/data-ingestion/admin';

const KINDS = [
  { value: 'POSTGRESQL',   label: 'PostgreSQL' },
  { value: 'SQLSERVER',    label: 'SQL Server' },
  { value: 'GENERIC_JDBC', label: 'Generic JDBC' },
  { value: 'REST',         label: 'REST API' },
  { value: 'REST_OPENAPI', label: 'Enterprise API (OpenAPI/Swagger)' },
  { value: 'ZOHO_ERP',     label: 'Zoho ERP' },
];
const JDBC_KINDS = ['POSTGRESQL', 'SQLSERVER', 'GENERIC_JDBC'];
const ERP_KINDS  = ['REST_OPENAPI', 'ZOHO_ERP'];
const AUTH_TYPES = ['NONE', 'BASIC', 'BEARER', 'API_KEY', 'OAUTH2'];
// Zoho data centers → accounts/api hostnames are derived server-side.
const ZOHO_DATA_CENTERS = ['com', 'eu', 'in', 'au', 'jp', 'ca', 'sa'];
const PAGINATION_STYLES = ['NONE', 'PAGE', 'OFFSET', 'CURSOR'];
const EMPTY_PAGINATION = {
  style: 'NONE', pageParam: '', sizeParam: '', pageSize: '', startPage: '',
  moreFlagPath: '', cursorParam: '', cursorPath: '',
};

const EMPTY_FORM = {
  name: '', kind: 'POSTGRESQL', jdbcUrl: '', driverClass: '', username: '',
  baseUrl: '', authType: 'NONE', authHeaderName: '', testPath: '',
  secret: '', maxPoolSize: 5, enabled: true,
  // HTTP kinds (REST/REST_OPENAPI/ZOHO_ERP): objects + pagination → configJson; secrets travel in `secret`
  dataCenter: 'com', clientId: '', clientSecret: '', refreshToken: '', scope: '',
  specText: '', objects: [], objectsMsg: '', pagination: { ...EMPTY_PAGINATION },
};

const STATUS_STYLE = {
  SUCCESS:      { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
  FAILED:       { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
  NEVER_TESTED: { bg: '#243A72', color: '#C3D3F2' },
};

function statusChip(ds, t) {
  const st = ds.lastTestStatus || 'NEVER_TESTED';
  const sty = STATUS_STYLE[st] || STATUS_STYLE.NEVER_TESTED;
  const label = st === 'SUCCESS' ? t('admin.screens.dataSources.statusOk', 'OK')
    : st === 'FAILED' ? t('admin.screens.dataSources.statusFailed', 'Failed')
    : t('admin.screens.dataSources.statusNever', 'Never tested');
  const chip = (
    <Chip size='small' label={label}
      sx={{ fontSize: 10, fontWeight: 700, bgcolor: sty.bg, color: sty.color }} />
  );
  return st === 'FAILED' && ds.lastTestError
    ? <Tooltip title={ds.lastTestError}>{chip}</Tooltip>
    : chip;
}

function truncate(s, n = 44) {
  if (!s) return '';
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

/** Form pagination → config shape; NONE/blank → null (single page). */
function buildPagination(p) {
  if (!p || !p.style || p.style === 'NONE') return null;
  return {
    style: p.style,
    pageParam: p.pageParam || null,
    sizeParam: p.sizeParam || null,
    pageSize: p.pageSize !== '' && p.pageSize != null ? Number(p.pageSize) : null,
    startPage: p.startPage !== '' && p.startPage != null ? Number(p.startPage) : null,
    moreFlagPath: p.moreFlagPath || null,
    cursorParam: p.cursorParam || null,
    cursorPath: p.cursorPath || null,
  };
}

/** Data & AI → Data Sources: manage enterprise JDBC / REST connections. */
export default function DataSourcesTab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [sources, setSources] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);

  // create/edit dialog
  const [dialogOpen, setDialogOpen]   = useState(false);
  const [editing, setEditing]         = useState(null);   // DataSourceView | null
  const [form, setForm]               = useState(EMPTY_FORM);
  const [saving, setSaving]           = useState(false);
  const [dialogErr, setDialogErr]     = useState(null);
  const [testResult, setTestResult]   = useState(null);   // TestResult | null
  const [testing, setTesting]         = useState(false);

  // delete confirm + row-level test
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting]         = useState(false);
  const [rowTesting, setRowTesting]     = useState({});    // id -> bool

  // schema browser
  const [browse, setBrowse]             = useState(null);  // DataSourceView | null
  const [snapshot, setSnapshot]         = useState(null);
  const [snapErr, setSnapErr]           = useState(null);
  const [snapLoading, setSnapLoading]   = useState(false);
  const [selectedTable, setSelectedTable] = useState(null);
  const [tableRefreshing, setTableRefreshing] = useState(false);

  // Mailbox addresses THIS user configured for scanning (mail-insight) — offered
  // as BASIC-auth username suggestions (e.g. Jira Cloud uses the account email).
  // Fail-soft: if mail-insight is disabled/unreachable the picker is just empty
  // and free typing still works.
  const [scanEmails, setScanEmails] = useState([]);

  const load = useCallback(() => {
    setLoading(true);
    axios.get(`${BASE}/datasources`)
      .then(r => { setSources(r.data?.dataSources || []); setErr(null); })
      .catch(e => setErr(apiErrorMessage(e, t('admin.screens.dataSources.errLoad', 'Could not load data sources'))))
      .finally(() => setLoading(false));
  }, [t]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    axios.get('/api/mail-insight/me/scan-emails')
      .then(r => setScanEmails(Array.isArray(r.data?.emails) ? r.data.emails : []))
      .catch(() => setScanEmails([]));
  }, []);

  const isJdbc = JDBC_KINDS.includes(form.kind);
  const isErp  = ERP_KINDS.includes(form.kind);
  const isZoho = form.kind === 'ZOHO_ERP';
  const isHttp = isErp || form.kind === 'REST';
  const [discovering, setDiscovering] = useState(false);

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  // BASIC auth needs a username alongside the secret. For HTTP APIs this is
  // typically an email (Jira Cloud: account email). Offer the user's own
  // scan-configured mailbox addresses, but allow a new one (freeSolo).
  const renderBasicUserField = () => (
    <Autocomplete
      freeSolo
      size='small'
      options={scanEmails}
      value={form.username || ''}
      onChange={(_, v) => setForm(f => ({ ...f, username: v || '' }))}
      onInputChange={(_, v) => setForm(f => ({ ...f, username: v || '' }))}
      renderInput={(params) => (
        <TextField {...params}
          label={t('admin.screens.dataSources.fBasicUser', 'Email / Username')}
          placeholder='name@company.com'
          helperText={t('admin.screens.dataSources.fBasicUserHint',
            'Basic-auth username. For Jira Cloud this is your Atlassian account email. Pick a configured scan address or type a new one.')}
          fullWidth />
      )}
    />
  );

  // Switching to Zoho defaults auth to OAuth2; keep the rest of the form intact.
  const onKindChange = (e) => {
    const kind = e.target.value;
    setForm(f => ({
      ...f,
      kind,
      authType: kind === 'ZOHO_ERP' ? 'OAUTH2'
        : (JDBC_KINDS.includes(kind) ? f.authType : f.authType),
      dataCenter: f.dataCenter || 'com',
    }));
    setTestResult(null);
  };

  const openCreate = () => {
    setEditing(null); setForm(EMPTY_FORM);
    setDialogErr(null); setTestResult(null); setDialogOpen(true);
  };
  const openEdit = (ds) => {
    setEditing(ds);
    let cfg = {};
    try { cfg = ds.configJson ? JSON.parse(ds.configJson) : {}; } catch { cfg = {}; }
    setForm({
      name: ds.name || '', kind: ds.kind || 'POSTGRESQL',
      jdbcUrl: ds.jdbcUrl || '', driverClass: ds.driverClass || '',
      username: ds.username || '', baseUrl: ds.baseUrl || '',
      authType: ds.authType || 'NONE', authHeaderName: ds.authHeaderName || '',
      testPath: ds.testPath || '', secret: '',
      maxPoolSize: ds.maxPoolSize ?? 5, enabled: ds.enabled !== false,
      dataCenter: cfg.dataCenter || 'com', clientId: cfg.clientId || '',
      clientSecret: '', refreshToken: '', scope: cfg.scope || '',
      specText: '', objects: Array.isArray(cfg.objects) ? cfg.objects : [],
      objectsMsg: Array.isArray(cfg.objects) && cfg.objects.length
        ? `${cfg.objects.length} object(s) configured` : '',
      pagination: cfg.pagination ? {
        ...EMPTY_PAGINATION, ...cfg.pagination,
        pageSize: cfg.pagination.pageSize ?? '', startPage: cfg.pagination.startPage ?? '',
      } : { ...EMPTY_PAGINATION },
    });
    setDialogErr(null); setTestResult(null); setDialogOpen(true);
  };

  /** Parse an uploaded OpenAPI/Swagger doc (or fetch the Zoho preset) into objects. */
  const discoverObjects = async () => {
    setDiscovering(true);
    try {
      const r = await axios.post(`${BASE}/datasources/parse-openapi`, {
        specJson: form.specText || null,
        vendor: isZoho ? 'ZOHO' : null,
      });
      const objects = r.data?.objects || [];
      const warnings = r.data?.warnings || [];
      setForm(f => ({
        ...f,
        objects,
        baseUrl: f.baseUrl || r.data?.suggestedBaseUrl || '',
        objectsMsg: `${objects.length} object(s) discovered${warnings.length ? ` · ${warnings.length} warning(s)` : ''}`,
      }));
      if (!objects.length) toast.error(t('admin.screens.dataSources.noObjects', 'No objects discovered — check the spec'));
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataSources.errParse', 'Could not parse the OpenAPI document')));
    }
    setDiscovering(false);
  };

  const onSpecFile = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => setForm(f => ({ ...f, specText: String(reader.result || '') }));
    reader.readAsText(file);
  };

  // Manual endpoint (object) definition — for REST APIs without a spec (e.g. Jira).
  const addObject = () => setForm(f => ({
    ...f,
    objects: [...(f.objects || []), { name: '', path: '', method: 'GET', dataPath: '', fields: [] }],
    objectsMsg: '',
  }));
  const setObject = (i, patch) => setForm(f => ({
    ...f, objects: (f.objects || []).map((o, j) => j === i ? { ...o, ...patch } : o),
  }));
  const removeObject = (i) => setForm(f => ({
    ...f, objects: (f.objects || []).filter((_, j) => j !== i),
  }));
  const setPag = (k) => (e) => setForm(f => ({ ...f, pagination: { ...f.pagination, [k]: e.target.value } }));

  const buildRequest = () => {
    // OAuth2 sources carry two secrets as one JSON bundle in `secret`.
    let secret = form.secret || null;
    if (isHttp && form.authType === 'OAUTH2') {
      secret = (form.clientSecret || form.refreshToken)
        ? JSON.stringify({ clientSecret: form.clientSecret, refreshToken: form.refreshToken })
        : null;                          // blank on edit → keep stored credential
    }
    let configJson = null;
    if (isHttp) {
      configJson = JSON.stringify({
        erpVendor: isZoho ? 'ZOHO' : 'GENERIC',
        dataCenter: isZoho ? (form.dataCenter || 'com') : null,
        clientId: form.clientId || null,
        scope: form.scope || null,
        pagination: buildPagination(form.pagination),
        objects: (form.objects || [])
          .filter(o => o.name && o.name.trim())
          .map(o => ({
            name: o.name.trim(),
            path: (o.path || '').trim(),
            method: o.method || 'GET',
            dataPath: (o.dataPath || '').trim(),
            fields: o.fields || [],
          })),
      });
    }
    return {
      name: form.name.trim(),
      kind: form.kind,
      jdbcUrl: isJdbc ? form.jdbcUrl.trim() : null,
      driverClass: form.kind === 'GENERIC_JDBC' ? form.driverClass.trim() : null,
      // Username is sent for JDBC and for HTTP BASIC auth (the other half of the
      // Basic credential; e.g. Jira Cloud email). Other HTTP auth types don't use it.
      username: (isJdbc || (isHttp && form.authType === 'BASIC'))
        ? (form.username || '').trim() : null,
      baseUrl: isHttp ? (form.baseUrl || '').trim() : null,
      authType: isHttp ? form.authType : null,
      authHeaderName: isHttp && form.authType === 'API_KEY' ? form.authHeaderName.trim() : null,
      testPath: isHttp ? (form.testPath || '').trim() : null,
      secret,
      configJson,
      maxPoolSize: Number(form.maxPoolSize) || 5,
      enabled: form.enabled,
    };
  };

  const testInDialog = async () => {
    setTesting(true); setTestResult(null);
    try {
      const r = await axios.post(`${BASE}/datasources/test`, buildRequest());
      setTestResult(r.data);
    } catch (e) {
      setTestResult({ ok: false, message: apiErrorMessage(e, t('admin.screens.dataSources.errTest', 'Connection test failed')) });
    }
    setTesting(false);
  };

  const save = async () => {
    setSaving(true); setDialogErr(null);
    try {
      if (editing) await axios.put(`${BASE}/datasources/${editing.id}`, buildRequest());
      else         await axios.post(`${BASE}/datasources`, buildRequest());
      toast.success(editing
        ? t('admin.screens.dataSources.updated', 'Data source updated.')
        : t('admin.screens.dataSources.created', 'Data source created.'));
      setDialogOpen(false); load();
    } catch (e) {
      setDialogErr(apiErrorMessage(e, t('admin.screens.dataSources.errSave', 'Save failed')));
    }
    setSaving(false);
  };

  const testRow = async (ds) => {
    setRowTesting(m => ({ ...m, [ds.id]: true }));
    try {
      const r = await axios.post(`${BASE}/datasources/${ds.id}/test`);
      const res = r.data || {};
      if (res.ok) toast.success(t('admin.screens.dataSources.testOk', '{{name}}: connection OK ({{ms}} ms)', { name: ds.name, ms: res.latencyMs ?? '—' }));
      else        toast.error(res.message || t('admin.screens.dataSources.errTest', 'Connection test failed'));
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataSources.errTest', 'Connection test failed')));
    }
    setRowTesting(m => ({ ...m, [ds.id]: false }));
    load();   // pick up lastTestStatus / lastTestAt
  };

  const toggleEnabled = async (ds) => {
    try {
      await axios.put(`${BASE}/datasources/${ds.id}`, {
        name: ds.name, kind: ds.kind, jdbcUrl: ds.jdbcUrl, driverClass: ds.driverClass,
        username: ds.username, baseUrl: ds.baseUrl, authType: ds.authType,
        authHeaderName: ds.authHeaderName, testPath: ds.testPath,
        secret: null, maxPoolSize: ds.maxPoolSize, enabled: !ds.enabled,
      });
      load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataSources.errSave', 'Save failed')));
    }
  };

  const confirmDelete = async () => {
    setDeleting(true);
    try {
      await axios.delete(`${BASE}/datasources/${deleteTarget.id}`);
      toast.success(t('admin.screens.dataSources.deleted', 'Data source deleted.'));
      setDeleteTarget(null); load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataSources.errDelete', 'Delete failed')));
    }
    setDeleting(false);
  };

  const loadSnapshot = useCallback((ds, refresh) => {
    setSnapLoading(true); setSnapErr(null);
    axios.get(`${BASE}/datasources/${ds.id}/metadata`, { params: { refresh: !!refresh } })
      .then(r => { setSnapshot(r.data); setSelectedTable(null); })
      .catch(e => setSnapErr(apiErrorMessage(e, t('admin.screens.dataSources.errMetadata', 'Could not load schema metadata'))))
      .finally(() => setSnapLoading(false));
  }, [t]);

  const openBrowse = (ds) => { setBrowse(ds); setSnapshot(null); loadSnapshot(ds, false); };

  const refreshTable = async (tbl) => {
    setTableRefreshing(true);
    try {
      const name = tbl.schema ? `${tbl.schema}.${tbl.name}` : tbl.name;
      await axios.post(`${BASE}/datasources/${browse.id}/metadata/tables/${encodeURIComponent(name)}/refresh`);
      loadSnapshot(browse, false);
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataSources.errRefresh', 'Table refresh failed')));
    }
    setTableRefreshing(false);
  };

  const fmt = (s) => s ? new Date(s).toLocaleString() : '—';

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.dataSources.scopeResource', 'data sources')} />
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5, gap: 1 }}>
        <Typography fontWeight={700} fontSize={15}>
          {t('admin.screens.dataSources.title', 'Data Sources')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <IconButton size='small' onClick={load} title={t('admin.common.refresh', 'Refresh')}>
          <RefreshIcon fontSize='small' />
        </IconButton>
        <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openCreate}>
          {t('admin.screens.dataSources.add', 'Add data source')}
        </Button>
      </Box>
      <Typography fontSize={13} color='#C3D3F2' mb={2}>
        {t('admin.screens.dataSources.intro',
          'Connect enterprise databases (PostgreSQL, SQL Server, any JDBC) and REST APIs. Test connectivity, browse schemas, then map tables under Data Mapping.')}
      </Typography>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {loading ? <CircularProgress size={22} /> : (
        <CollapsibleSection title={t('admin.screens.dataSources.sectionTitle', 'Data sources')}
          count={sources.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined'>
          <Table size='small'>
            <TableHead>
              <TableRow>
                <TableCell>{t('admin.screens.dataSources.colName', 'Name')}</TableCell>
                <TableCell>{t('admin.screens.dataSources.colKind', 'Kind')}</TableCell>
                <TableCell>{t('admin.screens.dataSources.colTarget', 'Target')}</TableCell>
                <TableCell>{t('admin.screens.dataSources.colStatus', 'Status')}</TableCell>
                <TableCell>{t('admin.screens.dataSources.colEnabled', 'Enabled')}</TableCell>
                <TableCell align='right'>{t('admin.common.actions', 'Actions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sources.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6}>
                    <Typography fontSize={12} color='#7D92C4' py={1}>
                      {t('admin.screens.dataSources.empty', 'No data sources yet. Add one to get started.')}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {sources.map(ds => (
                <TableRow key={ds.id} hover>
                  <TableCell>
                    <Typography fontSize={13} fontWeight={600}>{ds.name}</Typography>
                    {ds.hasCredential && (
                      <Tooltip title={t('admin.screens.dataSources.credentialStored', 'Credential stored')}>
                        <KeyIcon sx={{ fontSize: 12, color: '#7D92C4', ml: 0.5, verticalAlign: 'middle' }} />
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip size='small' variant='outlined' label={ds.kind} sx={{ fontSize: 10 }} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={ds.jdbcUrl || ds.baseUrl || ''}>
                      <Typography fontSize={12} color='#C3D3F2' sx={{ fontFamily: 'monospace' }}>
                        {truncate(ds.jdbcUrl || ds.baseUrl)}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{statusChip(ds, t)}</TableCell>
                  <TableCell>
                    <Switch size='small' checked={ds.enabled !== false} onChange={() => toggleEnabled(ds)} />
                  </TableCell>
                  <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                    <Tooltip title={t('admin.screens.dataSources.test', 'Test connection')}>
                      <span>
                        <IconButton size='small' onClick={() => testRow(ds)} disabled={!!rowTesting[ds.id]}>
                          {rowTesting[ds.id] ? <CircularProgress size={14} /> : <BoltIcon sx={{ fontSize: 16 }} />}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.dataSources.browse', 'Browse schema')}>
                      <IconButton size='small' onClick={() => openBrowse(ds)}>
                        <ManageSearchIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.common.edit', 'Edit')}>
                      <IconButton size='small' onClick={() => openEdit(ds)}>
                        <EditIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.common.delete', 'Delete')}>
                      <IconButton size='small' onClick={() => setDeleteTarget(ds)}>
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
            ? t('admin.screens.dataSources.editTitle', 'Edit data source')
            : t('admin.screens.dataSources.createTitle', 'New data source')}
        </DialogTitle>
        <DialogContent>
          {dialogErr && <Alert severity='error' sx={{ mb: 2 }}>{dialogErr}</Alert>}
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <Box sx={{ display: 'flex', gap: 2 }}>
              <TextField size='small' label={t('admin.screens.dataSources.fName', 'Name')} value={form.name}
                onChange={set('name')} fullWidth required />
              <FormControl size='small' sx={{ minWidth: 160 }}>
                <InputLabel>{t('admin.screens.dataSources.fKind', 'Kind')}</InputLabel>
                <Select value={form.kind} label={t('admin.screens.dataSources.fKind', 'Kind')} onChange={onKindChange}>
                  {KINDS.map(k => <MenuItem key={k.value} value={k.value} sx={{ fontSize: 13 }}>{k.label}</MenuItem>)}
                </Select>
              </FormControl>
            </Box>

            {isJdbc && (
              <>
                <TextField size='small' label={t('admin.screens.dataSources.fJdbcUrl', 'JDBC URL')} value={form.jdbcUrl}
                  onChange={set('jdbcUrl')} fullWidth required
                  placeholder='jdbc:postgresql://host:5432/db' />
                {form.kind === 'GENERIC_JDBC' && (
                  <TextField size='small' label={t('admin.screens.dataSources.fDriverClass', 'Driver class')} value={form.driverClass}
                    onChange={set('driverClass')} fullWidth
                    placeholder='com.example.jdbc.Driver' />
                )}
                <Box sx={{ display: 'flex', gap: 2 }}>
                  <TextField size='small' label={t('admin.screens.dataSources.fUsername', 'Username')} value={form.username}
                    onChange={set('username')} fullWidth />
                  <TextField size='small' type='password' label={t('admin.screens.dataSources.fPassword', 'Password')}
                    value={form.secret} onChange={set('secret')} fullWidth
                    placeholder={editing?.hasCredential ? '••••••' : ''}
                    helperText={editing?.hasCredential
                      ? t('admin.screens.dataSources.secretKept', 'Credential stored — leave blank to keep it.')
                      : undefined} />
                </Box>
              </>
            )}

            {form.kind === 'REST' && (
              <>
                <TextField size='small' label={t('admin.screens.dataSources.fBaseUrl', 'Base URL')} value={form.baseUrl}
                  onChange={set('baseUrl')} fullWidth required
                  placeholder='https://api.example.com/v1' />
                <Box sx={{ display: 'flex', gap: 2 }}>
                  <FormControl size='small' sx={{ minWidth: 140 }}>
                    <InputLabel>{t('admin.screens.dataSources.fAuthType', 'Auth')}</InputLabel>
                    <Select value={form.authType} label={t('admin.screens.dataSources.fAuthType', 'Auth')} onChange={set('authType')}>
                      {AUTH_TYPES.map(a => <MenuItem key={a} value={a} sx={{ fontSize: 13 }}>{a}</MenuItem>)}
                    </Select>
                  </FormControl>
                  {form.authType === 'API_KEY' && (
                    <TextField size='small' label={t('admin.screens.dataSources.fAuthHeader', 'Header name')} value={form.authHeaderName}
                      onChange={set('authHeaderName')} fullWidth placeholder='X-Api-Key' />
                  )}
                  <TextField size='small' label={t('admin.screens.dataSources.fTestPath', 'Test path')} value={form.testPath}
                    onChange={set('testPath')} fullWidth placeholder='/health' />
                </Box>
                {form.authType === 'BASIC' && renderBasicUserField()}
                {form.authType !== 'NONE' && (
                  <TextField size='small' type='password' label={t('admin.screens.dataSources.fSecret', 'Secret')}
                    value={form.secret} onChange={set('secret')} fullWidth
                    placeholder={editing?.hasCredential ? '••••••' : ''}
                    helperText={editing?.hasCredential
                      ? t('admin.screens.dataSources.secretKept', 'Credential stored — leave blank to keep it.')
                      : undefined} />
                )}
              </>
            )}

            {isErp && (
              <>
                <Box sx={{ display: 'flex', gap: 2 }}>
                  <TextField size='small' label={t('admin.screens.dataSources.fBaseUrl', 'Base URL')} value={form.baseUrl}
                    onChange={set('baseUrl')} fullWidth
                    placeholder={isZoho ? 'https://www.zohoapis.com (derived if blank)' : 'https://api.example.com'}
                    helperText={isZoho
                      ? t('admin.screens.dataSources.zohoBaseHint', 'Optional — derived from the data center if blank.')
                      : undefined} />
                  {isZoho && (
                    <FormControl size='small' sx={{ minWidth: 130 }}>
                      <InputLabel>{t('admin.screens.dataSources.fDataCenter', 'Data center')}</InputLabel>
                      <Select value={form.dataCenter} label={t('admin.screens.dataSources.fDataCenter', 'Data center')}
                        onChange={set('dataCenter')}>
                        {ZOHO_DATA_CENTERS.map(dc => <MenuItem key={dc} value={dc} sx={{ fontSize: 13 }}>{dc}</MenuItem>)}
                      </Select>
                    </FormControl>
                  )}
                </Box>

                <Box sx={{ display: 'flex', gap: 2 }}>
                  <FormControl size='small' sx={{ minWidth: 140 }}>
                    <InputLabel>{t('admin.screens.dataSources.fAuthType', 'Auth')}</InputLabel>
                    <Select value={form.authType} label={t('admin.screens.dataSources.fAuthType', 'Auth')} onChange={set('authType')}>
                      {AUTH_TYPES.map(a => <MenuItem key={a} value={a} sx={{ fontSize: 13 }}>{a}</MenuItem>)}
                    </Select>
                  </FormControl>
                  <TextField size='small' label={t('admin.screens.dataSources.fTestPath', 'Test path')} value={form.testPath}
                    onChange={set('testPath')} fullWidth placeholder='/crm/v2/settings/modules' />
                </Box>

                {form.authType === 'OAUTH2' && (
                  <>
                    <Box sx={{ display: 'flex', gap: 2 }}>
                      <TextField size='small' label={t('admin.screens.dataSources.fClientId', 'Client ID')} value={form.clientId}
                        onChange={set('clientId')} fullWidth />
                      <TextField size='small' label={t('admin.screens.dataSources.fScope', 'Scope')} value={form.scope}
                        onChange={set('scope')} fullWidth
                        placeholder={isZoho ? 'ZohoCRM.modules.ALL,…' : ''} />
                    </Box>
                    <Box sx={{ display: 'flex', gap: 2 }}>
                      <TextField size='small' type='password' label={t('admin.screens.dataSources.fClientSecret', 'Client secret')}
                        value={form.clientSecret} onChange={set('clientSecret')} fullWidth
                        placeholder={editing?.hasCredential ? '••••••' : ''} />
                      <TextField size='small' type='password' label={t('admin.screens.dataSources.fRefreshToken', 'Refresh token')}
                        value={form.refreshToken} onChange={set('refreshToken')} fullWidth
                        placeholder={editing?.hasCredential ? '••••••' : ''} />
                    </Box>
                    {editing?.hasCredential && (
                      <Typography fontSize={11} color='#7D92C4'>
                        {t('admin.screens.dataSources.oauthKept', 'Credentials stored — leave both blank to keep them.')}
                      </Typography>
                    )}
                  </>
                )}
                {form.authType === 'API_KEY' && (
                  <Box sx={{ display: 'flex', gap: 2 }}>
                    <TextField size='small' label={t('admin.screens.dataSources.fAuthHeader', 'Header name')} value={form.authHeaderName}
                      onChange={set('authHeaderName')} sx={{ minWidth: 160 }} placeholder='X-Api-Key' />
                    <TextField size='small' type='password' label={t('admin.screens.dataSources.fSecret', 'Secret')}
                      value={form.secret} onChange={set('secret')} fullWidth
                      placeholder={editing?.hasCredential ? '••••••' : ''} />
                  </Box>
                )}
                {form.authType === 'BASIC' && renderBasicUserField()}
                {(form.authType === 'BASIC' || form.authType === 'BEARER') && (
                  <TextField size='small' type='password' label={t('admin.screens.dataSources.fSecret', 'Secret')}
                    value={form.secret} onChange={set('secret')} fullWidth
                    placeholder={editing?.hasCredential ? '••••••' : ''} />
                )}
              </>
            )}

            {/* Endpoints (objects) + pagination — every HTTP source needs at least
                one object to be mappable, like a table for a database source. */}
            {isHttp && (
              <Box sx={{ border: '1px dashed #2E4A8C', borderRadius: 1, p: 1.5,
                         display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                <Typography fontSize={12} fontWeight={600}>
                  {t('admin.screens.dataSources.objectsTitle', 'Endpoints (objects to ingest)')}
                </Typography>
                <Typography fontSize={11} color='#C3D3F2'>
                  {t('admin.screens.dataSources.objectsHint',
                    'Each endpoint becomes a selectable "table" in Data Mapping. Add them manually or import from an OpenAPI/Swagger spec. Column fields are sampled automatically when you browse the schema.')}
                </Typography>

                {/* Import from a spec (or Zoho preset) */}
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                  {!isZoho && (
                    <>
                      <Button size='small' variant='outlined' component='label'>
                        {t('admin.screens.dataSources.uploadSpec', 'Upload OpenAPI/Swagger JSON')}
                        <input type='file' hidden accept='.json,.yaml,.yml,application/json' onChange={onSpecFile} />
                      </Button>
                      {form.specText && (
                        <Chip size='small' label={t('admin.screens.dataSources.specLoaded', 'Spec loaded')} sx={{ fontSize: 10 }} />
                      )}
                    </>
                  )}
                  <Button size='small' variant='outlined' onClick={discoverObjects}
                    disabled={discovering || (!isZoho && !form.specText)}
                    startIcon={discovering ? <CircularProgress size={14} /> : <ManageSearchIcon />}>
                    {isZoho
                      ? t('admin.screens.dataSources.loadZohoModules', 'Load Zoho modules')
                      : t('admin.screens.dataSources.discover', 'Import from spec')}
                  </Button>
                  <Box sx={{ flex: 1 }} />
                  <Button size='small' startIcon={<AddIcon sx={{ fontSize: 14 }} />} onClick={addObject}>
                    {t('admin.screens.dataSources.addObject', 'Add endpoint')}
                  </Button>
                </Box>
                {form.objectsMsg && (
                  <Typography fontSize={11} color='#C3D3F2'>{form.objectsMsg}</Typography>
                )}

                {/* Editable object rows */}
                {(form.objects || []).map((o, i) => (
                  <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                    <TextField size='small' label={t('admin.screens.dataSources.fObjName', 'Name')}
                      value={o.name || ''} onChange={e => setObject(i, { name: e.target.value })}
                      sx={{ minWidth: 130, flex: 1 }} placeholder='Issues' />
                    <TextField size='small' label={t('admin.screens.dataSources.fObjPath', 'Path')}
                      value={o.path || ''} onChange={e => setObject(i, { path: e.target.value })}
                      sx={{ minWidth: 180, flex: 1.4 }} placeholder='/rest/api/3/search' />
                    <TextField size='small' label={t('admin.screens.dataSources.fObjDataPath', 'Records path')}
                      value={o.dataPath || ''} onChange={e => setObject(i, { dataPath: e.target.value })}
                      sx={{ minWidth: 110, flex: 0.8 }} placeholder='issues' />
                    <IconButton size='small' onClick={() => removeObject(i)}>
                      <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                    </IconButton>
                  </Box>
                ))}

                {/* Pagination (applies to all endpoints) */}
                <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
                  <FormControl size='small' sx={{ minWidth: 150 }}>
                    <InputLabel>{t('admin.screens.dataSources.fPagStyle', 'Pagination')}</InputLabel>
                    <Select value={form.pagination.style} label={t('admin.screens.dataSources.fPagStyle', 'Pagination')}
                      onChange={setPag('style')}>
                      {PAGINATION_STYLES.map(s => <MenuItem key={s} value={s} sx={{ fontSize: 13 }}>{s}</MenuItem>)}
                    </Select>
                  </FormControl>
                  {(form.pagination.style === 'PAGE' || form.pagination.style === 'OFFSET') && (
                    <>
                      {/* Two keys, not one with a conditional fallback: a single
                          key can only carry one translation, so once it exists in
                          the catalog the ternary is dead and OFFSET mode would be
                          mislabelled "Page param". */}
                      <TextField size='small' label={form.pagination.style === 'OFFSET'
                        ? t('admin.screens.dataSources.fPagOffsetParam', 'Offset param')
                        : t('admin.screens.dataSources.fPagPageParam', 'Page param')}
                        value={form.pagination.pageParam} onChange={setPag('pageParam')}
                        sx={{ width: 140 }} placeholder={form.pagination.style === 'OFFSET' ? 'startAt' : 'page'} />
                      <TextField size='small' label={t('admin.screens.dataSources.fPagSizeParam', 'Size param')}
                        value={form.pagination.sizeParam} onChange={setPag('sizeParam')}
                        sx={{ width: 130 }} placeholder='maxResults' />
                      <TextField size='small' type='number' label={t('admin.screens.dataSources.fPagPageSize', 'Page size')}
                        value={form.pagination.pageSize} onChange={setPag('pageSize')} sx={{ width: 110 }} />
                      <TextField size='small' label={t('admin.screens.dataSources.fPagMore', 'More-flag path')}
                        value={form.pagination.moreFlagPath} onChange={setPag('moreFlagPath')}
                        sx={{ width: 150 }} placeholder='(optional)' />
                    </>
                  )}
                  {form.pagination.style === 'CURSOR' && (
                    <>
                      <TextField size='small' label={t('admin.screens.dataSources.fPagCursorParam', 'Cursor param')}
                        value={form.pagination.cursorParam} onChange={setPag('cursorParam')} sx={{ width: 150 }} />
                      <TextField size='small' label={t('admin.screens.dataSources.fPagCursorPath', 'Next-cursor path')}
                        value={form.pagination.cursorPath} onChange={setPag('cursorPath')} sx={{ width: 170 }} />
                      <TextField size='small' label={t('admin.screens.dataSources.fPagSizeParam', 'Size param')}
                        value={form.pagination.sizeParam} onChange={setPag('sizeParam')} sx={{ width: 130 }} />
                      <TextField size='small' type='number' label={t('admin.screens.dataSources.fPagPageSize', 'Page size')}
                        value={form.pagination.pageSize} onChange={setPag('pageSize')} sx={{ width: 110 }} />
                    </>
                  )}
                </Box>
              </Box>
            )}

            <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
              <TextField size='small' type='number' label={t('admin.screens.dataSources.fPool', 'Max pool size')}
                value={form.maxPoolSize} onChange={set('maxPoolSize')} sx={{ width: 140 }}
                inputProps={{ min: 1, max: 50 }} />
              <FormControlLabel
                control={<Switch size='small' checked={form.enabled}
                  onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />}
                label={<Typography fontSize={13}>{t('admin.common.enabled', 'Enabled')}</Typography>} />
            </Box>

            {testResult && (
              <Alert severity={testResult.ok ? 'success' : 'error'}>
                {testResult.ok
                  ? t('admin.screens.dataSources.testResultOk', 'Connection OK — {{ms}} ms{{detail}}',
                      { ms: testResult.latencyMs ?? '—', detail: testResult.detail ? ` · ${testResult.detail}` : '' })
                  : (testResult.message || t('admin.screens.dataSources.errTest', 'Connection test failed'))}
                {!testResult.ok && testResult.detail && (
                  <Typography fontSize={11} color='inherit' mt={0.5}>{testResult.detail}</Typography>
                )}
              </Alert>
            )}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={testInDialog} disabled={testing || saving}
            startIcon={testing ? <CircularProgress size={14} /> : <BoltIcon />}>
            {t('admin.screens.dataSources.test', 'Test connection')}
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button size='small' onClick={() => setDialogOpen(false)} disabled={saving}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' onClick={save}
            disabled={saving || !form.name.trim() || (isJdbc ? !form.jdbcUrl.trim() : !form.baseUrl.trim())}>
            {saving ? <CircularProgress size={14} /> : t('admin.common.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={!!deleteTarget} onClose={() => !deleting && setDeleteTarget(null)} maxWidth='xs' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.dataSources.deleteTitle', 'Delete data source?')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={13}>
            {t('admin.screens.dataSources.deleteBody',
              '"{{name}}" will be removed. Mappings and jobs that use it will stop working.',
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

      {/* Schema browser */}
      <Dialog open={!!browse} onClose={() => setBrowse(null)} maxWidth='md' fullWidth>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
          {t('admin.screens.dataSources.browseTitle', 'Schema — {{name}}', { name: browse?.name })}
          <Box sx={{ flex: 1 }} />
          {snapshot && (
            <>
              {snapshot.databaseProduct && <Chip size='small' variant='outlined' label={snapshot.databaseProduct} sx={{ fontSize: 10 }} />}
              <Chip size='small' variant='outlined'
                label={snapshot.fromCache
                  ? t('admin.screens.dataSources.cached', 'cached {{at}}', { at: fmt(snapshot.capturedAt) })
                  : t('admin.screens.dataSources.live', 'live')}
                sx={{ fontSize: 10 }} />
            </>
          )}
          <Tooltip title={t('admin.screens.dataSources.refreshSnapshot', 'Re-read schema from source')}>
            <span>
              <IconButton size='small' onClick={() => loadSnapshot(browse, true)} disabled={snapLoading}>
                <RefreshIcon fontSize='small' />
              </IconButton>
            </span>
          </Tooltip>
        </DialogTitle>
        <DialogContent>
          {snapErr && <Alert severity='error' sx={{ mb: 2 }}>{snapErr}</Alert>}
          {(snapshot?.warnings || []).length > 0 && (
            <Alert severity='warning' sx={{ mb: 2 }}>
              {snapshot.warnings.map((w, i) => <div key={i}>{w}</div>)}
            </Alert>
          )}
          {snapLoading ? <CircularProgress size={22} sx={{ my: 3 }} /> : snapshot && (
            <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
              <Paper variant='outlined' sx={{ width: 300, maxHeight: 420, overflow: 'auto', flexShrink: 0 }}>
                <Table size='small' stickyHeader>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('admin.screens.dataSources.colTable', 'Table')}</TableCell>
                      <TableCell align='right'>{t('admin.screens.dataSources.colRows', 'Rows')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(snapshot.tables || []).map(tb => {
                      const full = tb.schema ? `${tb.schema}.${tb.name}` : tb.name;
                      const sel = selectedTable && selectedTable.name === tb.name && selectedTable.schema === tb.schema;
                      return (
                        <TableRow key={full} hover selected={sel}
                          onClick={() => setSelectedTable(tb)} sx={{ cursor: 'pointer' }}>
                          <TableCell>
                            <Typography fontSize={12} fontWeight={sel ? 700 : 400}>{full}</Typography>
                            <Typography fontSize={10} color='#7D92C4'>{tb.type}</Typography>
                          </TableCell>
                          <TableCell align='right'>
                            <Typography fontSize={12} color='#C3D3F2'>{tb.rowCount ?? '—'}</Typography>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </Paper>

              <Box sx={{ flex: 1, minWidth: 0 }}>
                {!selectedTable ? (
                  <Typography fontSize={12} color='#7D92C4' mt={2}>
                    {t('admin.screens.dataSources.pickTable', 'Select a table to see its columns.')}
                  </Typography>
                ) : (
                  <>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                      <Typography fontWeight={700} fontSize={13}>
                        {selectedTable.schema ? `${selectedTable.schema}.${selectedTable.name}` : selectedTable.name}
                      </Typography>
                      <Box sx={{ flex: 1 }} />
                      <Button size='small' onClick={() => refreshTable(selectedTable)}
                        disabled={tableRefreshing}
                        startIcon={tableRefreshing ? <CircularProgress size={12} /> : <RefreshIcon sx={{ fontSize: 14 }} />}>
                        {t('admin.screens.dataSources.refreshTable', 'Refresh table')}
                      </Button>
                    </Box>
                    <Paper variant='outlined' sx={{ maxHeight: 380, overflow: 'auto' }}>
                      <Table size='small' stickyHeader>
                        <TableHead>
                          <TableRow>
                            <TableCell>{t('admin.screens.dataSources.colColumn', 'Column')}</TableCell>
                            <TableCell>{t('admin.screens.dataSources.colType', 'Type')}</TableCell>
                            <TableCell>{t('admin.screens.dataSources.colNullable', 'Nullable')}</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {(selectedTable.columns || []).map(c => (
                            <TableRow key={c.name}>
                              <TableCell>
                                <Typography fontSize={12} sx={{ fontFamily: 'monospace' }}>
                                  {c.name}
                                  {c.primaryKey && <Chip size='small' label='PK'
                                    sx={{ fontSize: 9, height: 16, ml: 0.5, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF', fontWeight: 700 }} />}
                                  {c.autoIncrement && <Chip size='small' variant='outlined' label='auto'
                                    sx={{ fontSize: 9, height: 16, ml: 0.5 }} />}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography fontSize={12} color='#C3D3F2'>
                                  {c.typeName}{c.size ? `(${c.size})` : ''}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography fontSize={12} color={c.nullable ? '#7D92C4' : '#C3D3F2'}>
                                  {c.nullable
                                    ? t('admin.screens.dataSources.nullable', 'yes')
                                    : t('admin.screens.dataSources.notNullable', 'no')}
                                </Typography>
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </Paper>
                    {(selectedTable.foreignKeys || []).length > 0 && (
                      <Typography fontSize={11} color='#C3D3F2' mt={1}>
                        {t('admin.screens.dataSources.fks', 'Foreign keys:')}{' '}
                        {selectedTable.foreignKeys.map(fk => `${fk.column} → ${fk.refTable}.${fk.refColumn}`).join(', ')}
                      </Typography>
                    )}
                  </>
                )}
              </Box>
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setBrowse(null)}>{t('admin.common.close', 'Close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
