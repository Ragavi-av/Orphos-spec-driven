import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Table, TableHead, TableRow, TableCell, TableBody,
  Switch, IconButton, TextField, Button, Alert, CircularProgress, Chip,
  Dialog, DialogTitle, DialogContent, DialogActions, Tooltip, Checkbox,
  InputAdornment, Select, MenuItem, Autocomplete,
} from '@mui/material';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import EditIcon          from '@mui/icons-material/Edit';
import DeleteIcon        from '@mui/icons-material/DeleteOutline';
import RefreshIcon       from '@mui/icons-material/Refresh';
import CheckCircleIcon   from '@mui/icons-material/CheckCircle';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import VisibilityIcon    from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { useToast } from 'eip-shell-context/ToastProvider';

const MODEL_HINTS = {
  gemma:  { placeholder: 'gemma-3-27b-it',   helper: 'Examples: gemma-3-27b-it, gemini-2.0-flash, gemini-1.5-pro' },
  claude: { placeholder: 'claude-opus-4-7',  helper: 'Examples: claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5-20251001' },
};

// How often the provider list re-syncs with the backend, which itself probes
// the Ollama runtime — so build/config changes surface without a manual refresh.
const AUTO_REFRESH_MS = 30000;

// Why a provider is (un)available — keyed by the backend's availabilityStatus.
const AVAILABILITY_HINTS = {
  AVAILABLE:        ['availHintAvailable',   'Ready to serve requests.'],
  NOT_IN_BUILD:     ['availHintNotInBuild',  'Not part of this deployment build (OLLAMA_MODEL_MODE / OLLAMA_EXTRA_MODELS).'],
  MODEL_NOT_LOADED: ['availHintNotLoaded',   'Included in this build, but the model is not loaded in the AI runtime yet.'],
  DISABLED:         ['availHintDisabled',    'Not enabled for this tenant.'],
  KEY_MISSING:      ['availHintKeyMissing',  'Enabled, but the tenant API key is missing.'],
};

/**
 * "Default AI by Function" — assigns a provider (and optionally a model) to
 * each platform AI usage type (Co-pilot, Document Search, Email Classification,
 * …). The selectable provider list is dynamic: in-house models the build
 * actually serves plus external providers the tenant has enabled above.
 * Unassigned functions fall back to the tenant default provider.
 */
function UsageAssignmentsSection() {
  const { t } = useTranslation();
  const toast = useToast();
  const [types, setTypes]         = useState([]);
  const [providers, setProviders] = useState([]);
  const [loading, setLoading]     = useState(true);
  const [err, setErr]             = useState(null);
  const [savingId, setSavingId]   = useState(null);

  const load = useCallback(async (opts = {}) => {
    const silent = opts.silent === true;
    if (!silent) { setLoading(true); setErr(null); }
    try {
      const r = await axios.get('/api/nl-query/admin/tenant/ai-usage');
      setTypes(Array.isArray(r.data?.usageTypes) ? r.data.usageTypes : []);
      setProviders(Array.isArray(r.data?.providers) ? r.data.providers : []);
    } catch (e) {
      if (!silent) {
        setErr(e?.response?.data?.error
          || e?.response?.data?.message
          || t('admin.screens.aiUsage.errLoad', 'Could not load AI function assignments.'));
      }
    }
    if (!silent) setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // The selectable provider list and stale flags are computed live server-side
  // (build config + AI runtime probe) — re-sync in the background so they track
  // build/config changes without a manual refresh.
  useEffect(() => {
    const id = setInterval(() => {
      if (!savingId) load({ silent: true });
    }, AUTO_REFRESH_MS);
    return () => clearInterval(id);
  }, [load, savingId]);

  const save = async (typeId, providerId, model) => {
    setSavingId(typeId); setErr(null);
    try {
      await axios.put(`/api/nl-query/admin/tenant/ai-usage/${typeId}`, {
        providerId,
        model: model && model.trim() ? model.trim() : null,
      });
      await load();
      toast.success(t('admin.screens.aiUsage.saveSuccess', 'Assignment saved.'));
    } catch (e) {
      setErr(e?.response?.data?.error
        || e?.response?.data?.message
        || t('admin.screens.aiUsage.errSave', 'Save failed.'));
    }
    setSavingId(null);
  };

  const reset = async (typeId) => {
    setSavingId(typeId); setErr(null);
    try {
      await axios.delete(`/api/nl-query/admin/tenant/ai-usage/${typeId}`);
      await load();
      toast.success(t('admin.screens.aiUsage.resetSuccess', 'Reset to tenant default.'));
    } catch (e) {
      setErr(e?.response?.data?.error
        || e?.response?.data?.message
        || t('admin.screens.aiUsage.errReset', 'Reset failed.'));
    }
    setSavingId(null);
  };

  const providerById = (id) => providers.find(p => p.id === id);

  return (
    <Box mt={4}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
            {t('admin.screens.aiUsage.title', 'Default AI by Function')}
          </Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.aiUsage.intro',
              'Pin a provider — and optionally a model — to each AI function. Functions without an assignment use the tenant default provider above.')}
          </Typography>
        </Box>
        <Button size='small' startIcon={<RefreshIcon />} onClick={load}
          sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.aiUsage.refresh', 'Refresh')}</Button>
      </Box>

      {err && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiUsage.colFunction', 'Function')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2', width: 260 }}>{t('admin.screens.aiUsage.colProvider', 'Assigned AI')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2', width: 260 }}>{t('admin.screens.aiUsage.colModel', 'Model')}</TableCell>
                <TableCell align='right' sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2', width: 70 }}>{t('admin.screens.aiUsage.colActions', 'Actions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {types.map(row => {
                const assigned = providerById(row.providerId);
                const busy = savingId === row.id;
                return (
                  <TableRow key={row.id} hover>
                    <TableCell sx={{ fontSize: 13 }}>
                      <Box sx={{ fontWeight: 600, color: '#FFFFFF' }}>{row.label}</Box>
                      <Box sx={{ fontSize: 11, color: '#7D92C4' }}>{row.description}</Box>
                      {row.stale && (
                        <Chip size='small'
                          label={t('admin.screens.aiUsage.stale', 'Assigned AI unavailable — using tenant default')}
                          sx={{ mt: 0.5, fontSize: 10, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
                      )}
                    </TableCell>
                    <TableCell>
                      <Select size='small' fullWidth displayEmpty disabled={busy}
                        value={assigned ? row.providerId : ''}
                        onChange={e => {
                          const v = e.target.value;
                          if (v === '') reset(row.id);
                          else save(row.id, v, null);
                        }}
                        sx={{ fontSize: 12, height: 34 }}>
                        <MenuItem value='' sx={{ fontSize: 12, color: '#7D92C4' }}>
                          {t('admin.screens.aiUsage.tenantDefault', 'Tenant default')}
                        </MenuItem>
                        {providers.map(p => (
                          <MenuItem key={p.id} value={p.id} sx={{ fontSize: 12 }}>{p.label}</MenuItem>
                        ))}
                      </Select>
                    </TableCell>
                    <TableCell>
                      <Autocomplete size='small' freeSolo disabled={!assigned || busy}
                        options={assigned?.models || []}
                        value={row.model || ''}
                        onChange={(e, v) => save(row.id, row.providerId, v || '')}
                        onBlur={e => {
                          const v = e.target.value ?? '';
                          if (assigned && v !== (row.model || '')) save(row.id, row.providerId, v);
                        }}
                        renderInput={params => (
                          <TextField {...params}
                            placeholder={assigned
                              ? t('admin.screens.aiUsage.modelDefault', 'Provider default')
                              : '—'}
                            sx={{ '& input': { fontSize: 12 } }} />
                        )} />
                    </TableCell>
                    <TableCell align='right'>
                      {busy ? <CircularProgress size={16} /> : (
                        <Tooltip title={t('admin.screens.aiUsage.resetTip', 'Reset to tenant default')}>
                          <span>
                            <IconButton size='small' disabled={!assigned}
                              onClick={() => reset(row.id)} sx={{ color: '#C3D3F2' }}>
                              <RestartAltIcon fontSize='small' />
                            </IconButton>
                          </span>
                        </Tooltip>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Paper>
      )}
    </Box>
  );
}

/**
 * Admin-only tab for enabling AI providers, supplying tenant-wide API keys
 * and choosing a default. End users see the resulting list in the AI Co-pilot
 * dropdown; they can still override with a personal key from their own profile.
 */
export default function AIProvidersTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [rows, setRows]               = useState([]);
  const [loading, setLoading]         = useState(true);
  const [err, setErr]                 = useState(null);
  const [backendUp, setBackendUp]     = useState(true);
  const [savingDefault, setSavingDefault] = useState(null);

  const [dialogOpen, setDialogOpen]   = useState(false);
  const [editing, setEditing]         = useState(null); // provider row
  const [draftEnabled, setDraftEnabled] = useState(false);
  const [draftIsDefault, setDraftIsDefault] = useState(false);
  const [draftModel, setDraftModel]   = useState('');
  const [draftKey, setDraftKey]       = useState('');
  const [showKey, setShowKey]         = useState(false);
  const [clearKey, setClearKey]       = useState(false);
  const [saving, setSaving]           = useState(false);
  const [draftErr, setDraftErr]       = useState(null);

  const load = useCallback(async (opts = {}) => {
    const silent = opts.silent === true;
    if (!silent) { setLoading(true); setErr(null); }
    try {
      const r = await axios.get('/api/nl-query/admin/tenant/providers');
      setRows(Array.isArray(r.data?.providers) ? r.data.providers : []);
      setBackendUp(r.data?.backendConfigured !== false);
    } catch (e) {
      if (!silent) {
        setErr(e?.response?.data?.error
          || e?.response?.data?.message
          || t('admin.screens.aiProviders.errLoad', 'Could not load tenant AI providers.'));
      }
    }
    if (!silent) setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // Availability is live server-side (the backend probes the AI runtime), so
  // re-sync in the background to reflect build/config changes in near real time.
  useEffect(() => {
    const id = setInterval(() => {
      if (!dialogOpen && !savingDefault) load({ silent: true });
    }, AUTO_REFRESH_MS);
    return () => clearInterval(id);
  }, [load, dialogOpen, savingDefault]);

  const openEditor = (row) => {
    setEditing(row);
    setDraftEnabled(!!row.enabled);
    setDraftIsDefault(!!row.isDefault);
    setDraftModel(row.model || '');
    setDraftKey('');
    setShowKey(false);
    setClearKey(false);
    setDraftErr(null);
    setDialogOpen(true);
  };

  const closeEditor = () => {
    if (saving) return;
    setDialogOpen(false);
    setEditing(null);
  };

  const save = async () => {
    if (!editing) return;
    setDraftErr(null); setSaving(true);
    try {
      // null   → preserve existing tenant key
      // ''     → clear stored key
      // value  → set/replace key
      const apiKey = editing.internal
        ? undefined                        // omit field entirely for in-house
        : clearKey ? ''
        : (draftKey.trim() ? draftKey.trim() : null);

      const payload = {
        enabled:   draftEnabled,
        isDefault: draftEnabled && draftIsDefault,
        model:     draftModel.trim() || null,
      };
      if (apiKey !== undefined) payload.apiKey = apiKey;

      await axios.put(`/api/nl-query/admin/tenant/providers/${editing.id}`, payload);
      const savedLabel = editing.label;
      setDialogOpen(false);
      setEditing(null);
      await load();
      toast.success(t('admin.screens.aiProviders.saveSuccess', '"{{label}}" saved successfully.', { label: savedLabel }));
    } catch (e) {
      setDraftErr(e?.response?.data?.error
        || e?.response?.data?.message
        || t('admin.screens.aiProviders.errSave', 'Save failed.'));
    }
    setSaving(false);
  };

  const reset = async (row) => {
    if (!window.confirm(t('admin.screens.aiProviders.confirmReset', 'Clear tenant configuration for "{{label}}"? End users will no longer see this provider.', { label: row.label }))) return;
    try {
      await axios.delete(`/api/nl-query/admin/tenant/providers/${row.id}`);
      await load();
      toast.success(t('admin.screens.aiProviders.resetSuccess', 'Tenant configuration for "{{label}}" cleared.', { label: row.label }));
    } catch (e) {
      setErr(e?.response?.data?.error
        || e?.response?.data?.message
        || t('admin.screens.aiProviders.errReset', 'Reset failed.'));
    }
  };

  // Toggle the tenant default from the row's checkbox without opening the
  // editor. Checking requires an available provider (the backend rejects
  // otherwise); unchecking leaves the tenant on the server default.
  const toggleDefault = async (row) => {
    if (savingDefault) return;
    if (!row.isDefault && !row.available) return;
    setSavingDefault(row.id);
    try {
      await axios.put(`/api/nl-query/admin/tenant/providers/${row.id}`, {
        enabled:   row.enabled,
        isDefault: !row.isDefault,
        model:     row.model || null,
        // apiKey omitted ⇒ preserve existing
      });
      await load({ silent: true });
      toast.success(row.isDefault
        ? t('admin.screens.aiProviders.unsetDefaultSuccess', '"{{label}}" is no longer the tenant default.', { label: row.label })
        : t('admin.screens.aiProviders.setDefaultSuccess', '"{{label}}" set as the tenant default.', { label: row.label }));
    } catch (e) {
      setErr(e?.response?.data?.error
        || e?.response?.data?.message
        || t('admin.screens.aiProviders.errSetDefault', 'Could not change the default.'));
    }
    setSavingDefault(null);
  };

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.aiProviders.scopeResource', 'AI providers')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.aiProviders.title', 'AI Providers')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.aiProviders.intro',
              'Enable providers for this tenant, store the API key once, pick a default. End users can still save a personal key to override the tenant key.')}
          </Typography>
        </Box>
        <Button size='small' startIcon={<RefreshIcon />} onClick={load}
          sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.aiProviders.refresh', 'Refresh')}</Button>
      </Box>

      {!backendUp && (
        <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }}>
          {t('admin.screens.aiProviders.backendDownPrefix', 'OpenBao is not configured — tenant keys cannot be persisted. Set')}{' '}
          <code>OPENBAO_TOKEN</code> {t('admin.screens.aiProviders.backendDownSuffix', 'on the nl-query-service.')}
        </Alert>
      )}
      {err && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <CollapsibleSection title={t('admin.screens.aiProviders.sectionTitle', 'Providers')}
          count={rows.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colProvider', 'Provider')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colType', 'Type')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colAvailable', 'Available')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colDefault', 'Default')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colModel', 'Model')}</TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colApiKey', 'API key')}</TableCell>
                <TableCell align='right' sx={{ fontSize: 12, fontWeight: 700, color: '#C3D3F2' }}>{t('admin.screens.aiProviders.colActions', 'Actions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    {t('admin.screens.aiProviders.noProviders', 'No providers registered.')}
                  </TableCell>
                </TableRow>
              ) : rows.map(row => (
                <TableRow key={row.id} hover>
                  <TableCell sx={{ fontSize: 13 }}>
                    <Box sx={{ fontWeight: 600, color: '#FFFFFF' }}>{row.label}</Box>
                    <Box sx={{ fontSize: 11, color: '#7D92C4' }}>{row.description}</Box>
                  </TableCell>
                  <TableCell>
                    <Chip size='small'
                      label={row.internal ? t('admin.screens.aiProviders.typeInHouse', 'In-house') : t('admin.screens.aiProviders.typeExternal', 'External')}
                      sx={{ fontSize: 10, fontWeight: 700,
                        bgcolor: row.internal ? 'rgba(91, 157, 255, 0.14)' : 'rgba(91, 157, 255, 0.14)',
                        color:   row.internal ? '#8DBCFF' : '#8DBCFF' }} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={(() => {
                      const [key, fallback] = AVAILABILITY_HINTS[row.availabilityStatus] || AVAILABILITY_HINTS.AVAILABLE;
                      return t(`admin.screens.aiProviders.${key}`, fallback);
                    })()}>
                      <Chip size='small'
                        label={row.available ? t('admin.screens.aiProviders.yes', 'Yes') : t('admin.screens.aiProviders.no', 'No')}
                        icon={row.available
                          ? <CheckCircleIcon sx={{ fontSize: 12 }} />
                          : <RadioButtonUncheckedIcon sx={{ fontSize: 12 }} />}
                        sx={{ fontSize: 10, fontWeight: 700,
                          bgcolor: row.available ? 'rgba(61, 220, 151, 0.14)' : 'rgba(255, 0, 0, 0.14)',
                          color:   row.available ? '#3DDC97' : '#FF6B81' }} />
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Tooltip title={row.isDefault
                      ? t('admin.screens.aiProviders.unsetDefaultTip', 'Unset tenant default')
                      : row.available
                        ? t('admin.screens.aiProviders.setAsDefaultTip', 'Set as tenant default')
                        : t('admin.screens.aiProviders.notAvailableTip', 'Not available — cannot be the default')}>
                      <span>
                        {savingDefault === row.id
                          ? <CircularProgress size={14} />
                          : <Checkbox size='small' checked={!!row.isDefault}
                              disabled={!row.isDefault && !row.available}
                              onChange={() => toggleDefault(row)} />}
                      </span>
                    </Tooltip>
                  </TableCell>
                  {/* The model that would actually be called, not just the tenant
                      override. row.model is the override (unset on most rows);
                      row.resolvedModel is what the provider resolves to, which for
                      in-house rows is the operator's OLLAMA_CHAT_MODEL and is
                      otherwise invisible in this screen. */}
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2', fontFamily: 'monospace' }}>
                    {row.resolvedModel ? (
                      <>
                        {row.resolvedModel}
                        <Box sx={{ fontSize: 10, fontFamily: 'system-ui', color: '#7D92C4' }}>
                          {row.model
                            ? t('admin.screens.aiProviders.modelPinned', 'tenant override')
                            : t('admin.screens.aiProviders.modelDefault', 'default')}
                        </Box>
                      </>
                    ) : (
                      <span style={{ color: '#7D92C4' }}>{t('admin.screens.aiProviders.modelDefault', 'default')}</span>
                    )}
                  </TableCell>
                  <TableCell>
                    {row.internal ? (
                      <Typography fontSize={11} color='#7D92C4'>{t('admin.screens.aiProviders.keyNa', 'n/a')}</Typography>
                    ) : row.keyConfigured ? (
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                        <CheckCircleIcon sx={{ fontSize: 13, color: '#3DDC97' }} />
                        <Typography fontSize={11} color='#3DDC97' fontWeight={600}>{t('admin.screens.aiProviders.keyConfigured', 'Configured')}</Typography>
                      </Box>
                    ) : (
                      <Typography fontSize={11} color='#FF6B81'>{t('admin.screens.aiProviders.keyMissing', 'Missing')}</Typography>
                    )}
                  </TableCell>
                  <TableCell align='right'>
                    <Tooltip title={t('admin.screens.aiProviders.editTip', 'Edit')}>
                      <span>
                        <IconButton size='small' onClick={() => openEditor(row)}
                          disabled={row.internal}>
                          <EditIcon fontSize='small' />
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.aiProviders.clearTip', 'Clear tenant config')}>
                      <span>
                        <IconButton size='small' onClick={() => reset(row)}
                          disabled={row.internal || (!row.enabled && !row.keyConfigured)}
                          sx={{ color: '#FF6B81' }}>
                          <DeleteIcon fontSize='small' />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1.5}>
        {t('admin.screens.aiProviders.storageNotePrefix', 'Keys are stored in OpenBao under')}{' '}
        <code>tenants/&#x7b;tenantId&#x7d;/providers/&#x7b;providerId&#x7d;</code>{' '}
        {t('admin.screens.aiProviders.storageNoteSuffix', 'and never returned over HTTP — only a "configured" flag.')}{' '}
        {t('admin.screens.aiProviders.liveNote', 'Availability is checked live against the deployment build and the AI runtime, and refreshes automatically every 30 seconds.')}
      </Typography>

      <UsageAssignmentsSection />

      {/* ── Edit dialog ──────────────────────────────────────────────────── */}
      <Dialog open={dialogOpen} onClose={closeEditor} fullWidth maxWidth='sm'>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.aiProviders.dialogTitle', 'Configure {{provider}}', { provider: editing?.label || t('admin.screens.aiProviders.providerFallback', 'provider') })}
        </DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            <Typography fontSize={12} color='#C3D3F2'>
              {editing?.description}
            </Typography>

            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Switch checked={draftEnabled} onChange={e => {
                const v = e.target.checked;
                setDraftEnabled(v);
                if (!v) setDraftIsDefault(false);
              }} />
              <Typography fontSize={13}>{t('admin.screens.aiProviders.enabledForUsers', 'Enabled for end users in this tenant')}</Typography>
            </Box>

            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Switch checked={draftIsDefault} disabled={!draftEnabled}
                onChange={e => setDraftIsDefault(e.target.checked)} />
              <Typography fontSize={13}>{t('admin.screens.aiProviders.useAsDefault', 'Use as default selection')}</Typography>
            </Box>

            <TextField
              label={t('admin.screens.aiProviders.defaultModelLabel', 'Default model (optional)')}
              size='small'
              value={draftModel}
              onChange={e => setDraftModel(e.target.value)}
              placeholder={MODEL_HINTS[editing?.id]?.placeholder || ''}
              helperText={MODEL_HINTS[editing?.id]?.helper || t('admin.screens.aiProviders.modelHelperDefault', 'Leave blank to use provider default')}
              fullWidth
            />

            <TextField
              label={editing?.keyConfigured ? t('admin.screens.aiProviders.replaceKeyLabel', 'Replace API key (leave blank to keep)') : t('admin.screens.aiProviders.apiKeyLabel', 'API key')}
              type={showKey ? 'text' : 'password'}
              value={draftKey}
              onChange={e => { setDraftKey(e.target.value); if (e.target.value) setClearKey(false); }}
              fullWidth size='small'
              disabled={clearKey}
              InputProps={{
                endAdornment: (
                  <InputAdornment position='end'>
                    <IconButton size='small' onClick={() => setShowKey(s => !s)}>
                      {showKey ? <VisibilityOffIcon fontSize='small' />
                               : <VisibilityIcon    fontSize='small' />}
                    </IconButton>
                  </InputAdornment>
                ),
              }}
            />

            {editing?.keyConfigured && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Switch checked={clearKey} onChange={e => {
                  setClearKey(e.target.checked);
                  if (e.target.checked) setDraftKey('');
                }} />
                <Typography fontSize={12} color='#FF6B81'>
                  {t('admin.screens.aiProviders.clearKeyNote', 'Clear the saved tenant key (end users with personal keys still work)')}
                </Typography>
              </Box>
            )}

            {draftErr && <Alert severity='error' sx={{ fontSize: 12 }}>{draftErr}</Alert>}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={closeEditor} size='small' disabled={saving}>{t('admin.screens.aiProviders.cancel', 'Cancel')}</Button>
          <Button onClick={save} variant='contained' size='small' disabled={saving}>
            {saving ? t('admin.screens.aiProviders.saving', 'Saving…') : t('admin.screens.aiProviders.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
