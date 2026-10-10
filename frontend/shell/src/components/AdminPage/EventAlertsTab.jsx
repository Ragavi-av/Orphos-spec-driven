import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Alert, CircularProgress, Chip, Switch,
  Table, TableHead, TableRow, TableCell, TableBody, IconButton, Tooltip,
  Dialog, DialogTitle, DialogContent, DialogActions,
  FormControl, InputLabel, Select, MenuItem,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import RefreshIcon from '@mui/icons-material/Refresh';
import SendIcon from '@mui/icons-material/Send';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

/**
 * Communications → Event Alerts.
 *
 * Rules map platform events (admin user granted, document ingestion failed,
 * service down, …) to an email audience: platform admins, tenant admins, all
 * users, or a specific role. Delivery uses the tenant's SMTP configuration
 * from the Email tab; message wording is editable there as ordinary email
 * templates (event-* codes).
 */

const AUDIENCES = [
  { value: 'PLATFORM_ADMIN', label: 'Platform admins' },
  { value: 'TENANT_ADMIN',   label: 'Tenant admins' },
  { value: 'ALL_USERS',      label: 'All users' },
  { value: 'ROLE',           label: 'Specific role' },
];
const audienceLabel = v => AUDIENCES.find(a => a.value === v)?.label ?? v;

const SEVERITY_COLORS = {
  CRITICAL: { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
  WARNING:  { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  INFO:     { bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF' },
};

const STATUS_COLORS = {
  SENT:          { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
  PARTIAL:       { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  FAILED:        { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
  NO_RULES:      { bg: '#243A72', color: '#C3D3F2' },
  NO_RECIPIENTS: { bg: '#243A72', color: '#C3D3F2' },
  RECEIVED:      { bg: '#243A72', color: '#C3D3F2' },
};

const EMPTY_FORM = {
  id: null, eventType: '', audience: 'TENANT_ADMIN', roleName: '',
  templateCode: '', extraRecipients: '', enabled: true,
};

export default function EventAlertsTab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [types, setTypes]     = useState([]);
  const [rules, setRules]     = useState([]);
  const [events, setEvents]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm]             = useState(EMPTY_FORM);
  const [saving, setSaving]         = useState(false);
  const [testing, setTesting]       = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const [ty, ru, ev] = await Promise.all([
        axios.get('/api/admin/events/types'),
        axios.get('/api/admin/events/rules'),
        axios.get('/api/admin/events/log?limit=50'),
      ]);
      setTypes(ty.data);
      setRules(ru.data);
      setEvents(ev.data);
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.eventAlerts.errLoad', 'Failed to load event alert configuration')));
    }
    setLoading(false);
  }, [t]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => { setForm(EMPTY_FORM); setDialogOpen(true); };
  const openEdit = (r) => {
    setForm({
      id: r.id, eventType: r.eventType, audience: r.audience,
      roleName: r.roleName || '', templateCode: r.templateCode || '',
      extraRecipients: (r.extraRecipients || []).join(', '),
      enabled: r.enabled,
    });
    setDialogOpen(true);
  };

  const saveRule = async (override) => {
    const f = { ...form, ...(override || {}) };
    if (!f.eventType) { toast.error?.('Pick an event type'); return; }
    setSaving(true);
    try {
      await axios.put(`/api/admin/events/rules${f.id ? `?id=${encodeURIComponent(f.id)}` : ''}`, {
        eventType: f.eventType,
        audience: f.audience,
        roleName: f.audience === 'ROLE' ? f.roleName : '',
        templateCode: f.templateCode,
        extraRecipients: f.extraRecipients
          .split(',').map(s => s.trim()).filter(Boolean),
        enabled: f.enabled,
      });
      setDialogOpen(false);
      toast.success?.(t('admin.screens.eventAlerts.saved', 'Notification rule saved.'));
      await load();
    } catch (e) {
      toast.error?.(apiErrorMessage(e, t('admin.screens.eventAlerts.errSave', 'Failed to save rule')));
    }
    setSaving(false);
  };

  const toggleRule = async (r, enabled) => {
    try {
      await axios.put(`/api/admin/events/rules?id=${encodeURIComponent(r.id)}`, {
        eventType: r.eventType, audience: r.audience, roleName: r.roleName,
        templateCode: r.templateCode, extraRecipients: r.extraRecipients, enabled,
      });
      setRules(prev => prev.map(x => x.id === r.id ? { ...x, enabled } : x));
    } catch (e) {
      toast.error?.(apiErrorMessage(e, t('admin.screens.eventAlerts.errSave', 'Failed to save rule')));
    }
  };

  const deleteRule = async (r) => {
    try {
      await axios.delete(`/api/admin/events/rules/${encodeURIComponent(r.id)}`);
      setRules(prev => prev.filter(x => x.id !== r.id));
      toast.success?.(t('admin.screens.eventAlerts.deleted', 'Notification rule deleted.'));
    } catch (e) {
      toast.error?.(apiErrorMessage(e, t('admin.screens.eventAlerts.errDelete', 'Failed to delete rule')));
    }
  };

  const sendTest = async () => {
    setTesting(true);
    try {
      await axios.post('/api/admin/events/test', {});
      toast.success?.(t('admin.screens.eventAlerts.testSent',
        'Test event dispatched — check the event log below and your inbox.'));
      await load();
    } catch (e) {
      toast.error?.(apiErrorMessage(e, t('admin.screens.eventAlerts.errTest', 'Test event failed')));
    }
    setTesting(false);
  };

  if (loading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress size={28} /></Box>;
  }

  return (
    <Box>
      <TenantScopeBanner />

      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', mb: 2, gap: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
            {t('admin.screens.eventAlerts.title', 'Event Alerts')}
          </Typography>
          <Typography fontSize={12.5} color='#C3D3F2'>
            {t('admin.screens.eventAlerts.subtitle',
              'Email the right audience when something needs attention — an admin user is added, a document fails to ingest, a service goes down. Delivery uses the SMTP settings from the Email tab.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, flexShrink: 0 }}>
          <Tooltip title={t('admin.screens.eventAlerts.refreshTip', 'Reload rules and event log')}>
            <IconButton size='small' onClick={load}><RefreshIcon fontSize='small' /></IconButton>
          </Tooltip>
          <Button size='small' variant='outlined' startIcon={<SendIcon />} disabled={testing}
            onClick={sendTest} sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.eventAlerts.test', 'Send test event')}
          </Button>
          <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openCreate}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.eventAlerts.addRule', 'Add rule')}
          </Button>
        </Box>
      </Box>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {/* Rules */}
      <Box sx={{ mb: 3 }}>
      <CollapsibleSection title={t('admin.screens.eventAlerts.rulesTitle', 'Notification rules')}
        count={rules.length} detailsSx={{ p: 0 }}>
      <Paper elevation={0} sx={{ border: '1px solid #243A72', borderRadius: 2, overflow: 'hidden' }}>
        <Table size='small'>
          <TableHead>
            <TableRow sx={{ bgcolor: '#0F2450' }}>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colEvent', 'Event')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colAudience', 'Notify')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colTemplate', 'Template')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colExtra', 'Extra recipients')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }} align='center'>{t('admin.screens.eventAlerts.colEnabled', 'Enabled')}</TableCell>
              <TableCell align='right' />
            </TableRow>
          </TableHead>
          <TableBody>
            {rules.length === 0 && (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography fontSize={12.5} color='#7D92C4' sx={{ py: 1.5, textAlign: 'center' }}>
                    {t('admin.screens.eventAlerts.noRules',
                      'No notification rules yet. Add one to start receiving event emails.')}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {rules.map(r => {
              const typeInfo = types.find(x => x.eventType === r.eventType);
              return (
                <TableRow key={r.id} hover>
                  <TableCell>
                    <Typography fontSize={12.5} fontWeight={600}>{typeInfo?.label ?? r.eventType}</Typography>
                    <Typography fontSize={11} color='#7D92C4'>{r.eventType}</Typography>
                  </TableCell>
                  <TableCell>
                    <Chip size='small' label={
                      r.audience === 'ROLE' ? `${audienceLabel(r.audience)}: ${r.roleName}` : audienceLabel(r.audience)
                    } sx={{ fontSize: 11, height: 22 }} />
                  </TableCell>
                  <TableCell>
                    <Typography fontSize={12} color='#C3D3F2'>
                      {r.templateCode || (typeInfo?.defaultTemplateCode ?? 'event-generic')}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography fontSize={12} color='#C3D3F2'>
                      {(r.extraRecipients || []).join(', ') || '—'}
                    </Typography>
                  </TableCell>
                  <TableCell align='center'>
                    <Switch size='small' checked={r.enabled} onChange={(_, v) => toggleRule(r, v)} />
                  </TableCell>
                  <TableCell align='right'>
                    <IconButton size='small' onClick={() => openEdit(r)}><EditIcon sx={{ fontSize: 16 }} /></IconButton>
                    <IconButton size='small' onClick={() => deleteRule(r)}><DeleteIcon sx={{ fontSize: 16 }} /></IconButton>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Paper>
      </CollapsibleSection>
      </Box>

      {/* Event log */}
      <CollapsibleSection title={t('admin.screens.eventAlerts.logTitle', 'Recent events')}
        count={events.length} detailsSx={{ p: 0 }}>
      <Paper elevation={0} sx={{ border: '1px solid #243A72', borderRadius: 2, overflow: 'hidden' }}>
        <Table size='small'>
          <TableHead>
            <TableRow sx={{ bgcolor: '#0F2450' }}>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colTime', 'Time')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colEvent', 'Event')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colSeverity', 'Severity')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colSubject', 'Subject')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }} align='center'>{t('admin.screens.eventAlerts.colSent', 'Emails')}</TableCell>
              <TableCell sx={{ fontWeight: 700, fontSize: 12 }}>{t('admin.screens.eventAlerts.colStatus', 'Status')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {events.length === 0 && (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography fontSize={12.5} color='#7D92C4' sx={{ py: 1.5, textAlign: 'center' }}>
                    {t('admin.screens.eventAlerts.noEvents', 'No events received yet.')}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {events.map(e => {
              const sev = SEVERITY_COLORS[e.severity] ?? SEVERITY_COLORS.INFO;
              const st  = STATUS_COLORS[e.dispatchStatus] ?? STATUS_COLORS.RECEIVED;
              return (
                <TableRow key={e.id} hover>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Typography fontSize={11.5} color='#C3D3F2'>
                      {e.createdAt ? new Date(e.createdAt).toLocaleString() : ''}
                    </Typography>
                  </TableCell>
                  <TableCell><Typography fontSize={12} fontWeight={600}>{e.eventType}</Typography></TableCell>
                  <TableCell>
                    <Chip size='small' label={e.severity}
                      sx={{ fontSize: 10.5, height: 20, bgcolor: sev.bg, color: sev.color, fontWeight: 700 }} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={e.message || ''}>
                      <Typography fontSize={12} color='#C3D3F2' sx={{
                        maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {e.subject}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell align='center'>
                    <Typography fontSize={12}>{e.recipientCount}</Typography>
                  </TableCell>
                  <TableCell>
                    <Chip size='small' label={e.dispatchStatus}
                      sx={{ fontSize: 10.5, height: 20, bgcolor: st.bg, color: st.color, fontWeight: 700 }} />
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Paper>
      </CollapsibleSection>

      {/* Rule editor dialog */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {form.id
            ? t('admin.screens.eventAlerts.editRule', 'Edit notification rule')
            : t('admin.screens.eventAlerts.newRule', 'New notification rule')}
        </DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '12px !important' }}>
          <FormControl size='small' fullWidth>
            <InputLabel>{t('admin.screens.eventAlerts.fldEvent', 'Event type')}</InputLabel>
            <Select value={form.eventType} label={t('admin.screens.eventAlerts.fldEvent', 'Event type')}
              onChange={e => setForm(f => ({ ...f, eventType: e.target.value }))}>
              {types.map(ty => (
                <MenuItem key={ty.eventType} value={ty.eventType}>
                  {ty.label} ({ty.eventType})
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size='small' fullWidth>
            <InputLabel>{t('admin.screens.eventAlerts.fldAudience', 'Notify')}</InputLabel>
            <Select value={form.audience} label={t('admin.screens.eventAlerts.fldAudience', 'Notify')}
              onChange={e => setForm(f => ({ ...f, audience: e.target.value }))}>
              {AUDIENCES.map(a => <MenuItem key={a.value} value={a.value}>{a.label}</MenuItem>)}
            </Select>
          </FormControl>

          {form.audience === 'ROLE' && (
            <TextField size='small' fullWidth
              label={t('admin.screens.eventAlerts.fldRole', 'Role name')}
              placeholder='e.g. executive'
              value={form.roleName}
              onChange={e => setForm(f => ({ ...f, roleName: e.target.value }))} />
          )}

          <TextField size='small' fullWidth
            label={t('admin.screens.eventAlerts.fldTemplate', 'Email template code (optional)')}
            helperText={t('admin.screens.eventAlerts.fldTemplateHelp',
              'Leave blank to use the built-in template for this event type. Templates are editable in the Email tab.')}
            value={form.templateCode}
            onChange={e => setForm(f => ({ ...f, templateCode: e.target.value }))} />

          <TextField size='small' fullWidth
            label={t('admin.screens.eventAlerts.fldExtra', 'Extra recipients (comma-separated, optional)')}
            placeholder='ops@example.com, oncall@example.com'
            value={form.extraRecipients}
            onChange={e => setForm(f => ({ ...f, extraRecipients: e.target.value }))} />

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Switch size='small' checked={form.enabled}
              onChange={(_, v) => setForm(f => ({ ...f, enabled: v }))} />
            <Typography fontSize={13}>{t('admin.screens.eventAlerts.fldEnabled', 'Enabled')}</Typography>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setDialogOpen(false)} sx={{ textTransform: 'none' }}>
            {t('common.cancel', 'Cancel')}
          </Button>
          <Button size='small' variant='contained' disabled={saving} onClick={() => saveRule()}
            sx={{ textTransform: 'none' }}>
            {t('common.save', 'Save')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
