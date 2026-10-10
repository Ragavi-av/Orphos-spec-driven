import { useEffect, useState, useCallback } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Select, MenuItem, Alert, Switch,
  CircularProgress, Divider, Table, TableHead, TableRow, TableCell, TableBody, Chip,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { useToast } from 'eip-shell-context/ToastProvider';
import { formatDateTime } from 'eip-shell-context/datetime';

/**
 * Section C - email admin UI.
 * SMTP config, templates, delivery test, analytics, backups.
 */
export default function EmailConfigTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [config, setConfig]       = useState({
    name: 'default', host: '', port: 587, fromAddress: '', fromName: '',
    username: '', password: '', security: 'STARTTLS', enabled: true,
  });
  const [templates, setTemplates] = useState([]);
  const [activeTpl, setActiveTpl] = useState({
    code: '', name: '', subject: '', bodyHtml: '', bodyText: '',
    requiredVariables: '',
  });
  const [analytics, setAnalytics] = useState(null);
  const [backups, setBackups]     = useState([]);
  const [loading, setLoading]     = useState(true);
  const [err, setErr]             = useState(null);
  const [test, setTest]           = useState({ to: '', templateCode: '', variables: '{}' });
  const [testResult, setTestResult] = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const [a, t, an, b] = await Promise.all([
        axios.get('/api/admin/email/config/active'),
        axios.get('/api/admin/email/templates'),
        axios.get('/api/admin/email/analytics'),
        axios.get('/api/admin/email/backups'),
      ]);
      if (a.data) setConfig({ ...a.data, password: '' });
      setTemplates(t.data); setAnalytics(an.data); setBackups(b.data);
    } catch (e) { setErr(e?.response?.data?.message || 'Failed to load'); }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const saveConfig = async () => {
    setErr(null);
    try {
      // Don't transmit empty passwords - server preserves existing secret ref.
      const payload = { ...config };
      if (!payload.password) delete payload.password;
      await axios.put('/api/admin/email/config', payload);
      load();
      toast.success('Email configuration saved successfully.');
    } catch (e) { setErr(e?.response?.data?.message || 'Save failed'); }
  };

  const saveTemplate = async () => {
    setErr(null);
    try {
      await axios.put('/api/admin/email/templates', {
        ...activeTpl,
        requiredVariables: activeTpl.requiredVariables
          .split(',').map(s => s.trim()).filter(Boolean),
      });
      load();
      toast.success('Email template saved successfully.');
    } catch (e) { setErr(e?.response?.data?.message || 'Save failed'); }
  };

  const runTest = async () => {
    setErr(null); setTestResult(null);
    let vars = {};
    try { vars = JSON.parse(test.variables || '{}'); }
    catch { setErr('Variables must be valid JSON'); return; }
    try {
      const r = await axios.post('/api/admin/email/test',
        { to: test.to, templateCode: test.templateCode, variables: vars });
      setTestResult(r.data);
      if (r.data?.delivered) toast.success('Test email sent successfully.');
    } catch (e) {
      setTestResult({ delivered: false, message: e?.response?.data?.message || 'send failed' });
    }
  };

  const takeBackup = async () => {
    const label = prompt('Backup label?', `backup-${new Date().toISOString().slice(0,10)}`);
    if (!label) return;
    try {
      await axios.post('/api/admin/email/backups', { label });
      load();
      toast.success('Backup created successfully.');
    } catch (e) { setErr(e?.response?.data?.message || 'Backup failed'); }
  };

  const restore = async id => {
    if (!confirm('Restore this backup? Templates will be upserted, SMTP password is not restored.')) return;
    try {
      await axios.post(`/api/admin/email/backups/${id}/restore`);
      load();
      toast.success('Backup restored successfully.');
    } catch (e) { setErr(e?.response?.data?.message || 'Restore failed'); }
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.emailConfig.scopeResource', 'email configuration')} />
      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.emailConfig.smtp')}</Typography>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
          <TextField size='small' label={t('admin.screens.emailConfig.fConfigName')} value={config.name}
            onChange={e => setConfig({ ...config, name: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fHost')} value={config.host}
            onChange={e => setConfig({ ...config, host: e.target.value })} />
          <TextField size='small' type='number' label={t('admin.screens.emailConfig.fPort')} value={config.port}
            onChange={e => setConfig({ ...config, port: +e.target.value })} />
          <Select size='small' value={config.security}
            onChange={e => setConfig({ ...config, security: e.target.value })}>
            <MenuItem value='STARTTLS'>STARTTLS</MenuItem>
            <MenuItem value='SSL'>SSL</MenuItem>
            <MenuItem value='NONE'>NONE</MenuItem>
          </Select>
          <TextField size='small' label={t('admin.screens.emailConfig.fFromAddress')} value={config.fromAddress}
            onChange={e => setConfig({ ...config, fromAddress: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fFromName')} value={config.fromName}
            onChange={e => setConfig({ ...config, fromName: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fUsername')} value={config.username}
            onChange={e => setConfig({ ...config, username: e.target.value })} />
          <TextField size='small' type='password' label={t('admin.screens.emailConfig.fPassword')}
            value={config.password}
            onChange={e => setConfig({ ...config, password: e.target.value })} />
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }}>
          <Switch size='small' checked={config.enabled}
            onChange={e => setConfig({ ...config, enabled: e.target.checked })} />
          <Typography fontSize={12}>{t('admin.screens.emailConfig.enabledHint')}</Typography>
        </Box>
        <Box mt={1}><Button size='small' variant='contained' onClick={saveConfig}>{t('admin.screens.emailConfig.saveConfig')}</Button></Box>
      </Paper>

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.emailConfig.templates')}</Typography>
      <Paper variant='outlined' sx={{ mb: 2, overflow: 'hidden' }}>
        <Table size='small'>
          <TableHead><TableRow sx={{ bgcolor: '#0F2450' }}>
            {['code', 'name', 'subject', 'blank'].map(h =>
              <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12 }}>
                {h === 'blank' ? '' : t(`admin.screens.emailConfig.col.${h}`)}
              </TableCell>)}
          </TableRow></TableHead>
          <TableBody>
            {templates.length === 0 ? (
              <TableRow><TableCell colSpan={4} sx={{ textAlign: 'center', color: '#7D92C4' }}>
                {t('admin.screens.emailConfig.noTemplates')}
              </TableCell></TableRow>
            // The map parameter was named `t`, which shadowed the translation
            // function — renamed so this row's cells can call t() too.
            ) : templates.map(tpl => (
              <TableRow key={tpl.id}>
                <TableCell sx={{ fontSize: 12, fontFamily: 'monospace' }}>{tpl.code}</TableCell>
                <TableCell sx={{ fontSize: 13 }}>{tpl.name}</TableCell>
                <TableCell sx={{ fontSize: 12 }}>{tpl.subject}</TableCell>
                <TableCell><Button size='small' onClick={() => setActiveTpl({
                  ...tpl, requiredVariables: (tpl.requiredVariables || []).join(',') })}>{t('admin.common.edit')}</Button></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Typography fontSize={13} fontWeight={700} mb={1}>{t('admin.screens.emailConfig.templateEditor')}</Typography>
        <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
          <TextField size='small' label={t('admin.screens.emailConfig.col.code')} value={activeTpl.code}
            onChange={e => setActiveTpl({ ...activeTpl, code: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.col.name')} value={activeTpl.name}
            onChange={e => setActiveTpl({ ...activeTpl, name: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.col.subject')} value={activeTpl.subject}
            onChange={e => setActiveTpl({ ...activeTpl, subject: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fRequiredVars')}
            value={activeTpl.requiredVariables}
            onChange={e => setActiveTpl({ ...activeTpl, requiredVariables: e.target.value })} />
        </Box>
        <TextField multiline minRows={4} fullWidth label={t('admin.screens.emailConfig.fBodyHtml')}
          sx={{ mt: 1, fontFamily: 'monospace' }}
          value={activeTpl.bodyHtml}
          onChange={e => setActiveTpl({ ...activeTpl, bodyHtml: e.target.value })} />
        <TextField multiline minRows={3} fullWidth label={t('admin.screens.emailConfig.fBodyText')}
          sx={{ mt: 1, fontFamily: 'monospace' }}
          value={activeTpl.bodyText}
          onChange={e => setActiveTpl({ ...activeTpl, bodyText: e.target.value })} />
        <Box mt={1}><Button size='small' variant='contained' onClick={saveTemplate}>{t('admin.screens.emailConfig.saveTemplate')}</Button></Box>
      </Paper>

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.emailConfig.deliveryTest')}</Typography>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
          <TextField size='small' label={t('admin.screens.emailConfig.fRecipient')} value={test.to}
            onChange={e => setTest({ ...test, to: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fTemplateCode')} value={test.templateCode}
            onChange={e => setTest({ ...test, templateCode: e.target.value })} />
          <TextField size='small' label={t('admin.screens.emailConfig.fVariables')} value={test.variables}
            onChange={e => setTest({ ...test, variables: e.target.value })}
            sx={{ minWidth: 240 }} />
          <Button size='small' variant='contained' onClick={runTest}>{t('admin.screens.emailConfig.sendTest')}</Button>
        </Box>
        {testResult && (
          <Alert severity={testResult.delivered ? 'success' : 'error'}>
            {testResult.delivered
              ? t('admin.screens.emailConfig.delivered')
              : t('admin.screens.emailConfig.testFailed', { message: testResult.message })}
          </Alert>
        )}
      </Paper>

      <Divider sx={{ my: 2 }} />

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.emailConfig.analytics')}</Typography>
      {analytics && (
        <Box sx={{ display: 'flex', gap: 2, mb: 2, flexWrap: 'wrap' }}>
          <Stat label={t('admin.screens.emailConfig.sent')}      value={analytics.sent24h} />
          <Stat label={t('admin.screens.emailConfig.delivered')} value={analytics.delivered24h} />
          <Stat label={t('admin.screens.emailConfig.bounced')}   value={analytics.bounced24h} />
          <Stat label={t('admin.screens.emailConfig.opened')}    value={analytics.opened24h} />
        </Box>
      )}

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.emailConfig.backups')}</Typography>
      <Paper variant='outlined' sx={{ mb: 2 }}>
        <Box sx={{ p: 1.5, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.emailConfig.backupHint')}
          </Typography>
          <Button size='small' variant='outlined' onClick={takeBackup}>{t('admin.screens.emailConfig.takeBackup')}</Button>
        </Box>
        {backups.map(b => (
          <Box key={b.id} sx={{ px: 1.5, py: 1, borderTop: '1px solid #243A72',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Box>
              <Typography fontSize={13}>{b.label}</Typography>
              <Typography fontSize={11} color='#7D92C4'>{formatDateTime(b.createdAt)}</Typography>
            </Box>
            <Button size='small' onClick={() => restore(b.id)}>{t('admin.screens.emailConfig.restore')}</Button>
          </Box>
        ))}
      </Paper>
    </Box>
  );
}

function Stat({ label, value }) {
  return (
    <Paper variant='outlined' sx={{ p: 1.5, minWidth: 110 }}>
      <Typography fontSize={11} color='#C3D3F2'>{label}</Typography>
      <Typography fontWeight={700} fontSize={18}>{value}</Typography>
    </Paper>
  );
}
