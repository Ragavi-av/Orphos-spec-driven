import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Alert, CircularProgress,
  Switch, FormControlLabel, Checkbox, Divider, InputAdornment,
} from '@mui/material';
import SaveIcon from '@mui/icons-material/Save';
import RefreshIcon from '@mui/icons-material/Refresh';
import axios from 'eip-shell-context/axiosAuth';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

/**
 * Platform → Mobile Access.
 *
 * Manages whether Orphos is reachable from the native mobile app for the acting
 * tenant, plus the defaults the app applies: MFA / biometric app-lock, which alert
 * severities surface, the task-count ranges that colour the Busy status dot, and the
 * data-refresh interval. Persisted per tenant via the mobile BFF (/api/mobile/config),
 * which the app reads on launch.
 */

// Effective defaults mirror MobileConfigService.defaults() so the form is populated
// even before the first save.
const DEFAULTS = {
  access:      { enabled: true },
  security:    { mfaEnabled: true, biometricEnabled: true },
  alerts:      { includeCritical: true, includeHigh: false },
  statusThresholds: { red: 30, blue: 20, green: 10 },
  refreshMinutes: 5,
};

export default function MobileAccessTab({ tenantId }) {
  const toast = useToast();
  const [cfg, setCfg]       = useState(DEFAULTS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [err, setErr]         = useState(null);

  const hdr = { headers: { 'X-Tenant-Id': tenantId } };

  const load = useCallback(async () => {
    if (!tenantId) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try {
      const { data } = await axios.get('/api/mobile/config', hdr);
      setCfg({
        access:   { enabled: data?.access?.enabled ?? true },
        security: {
          mfaEnabled:       data?.security?.mfaEnabled ?? true,
          biometricEnabled: data?.security?.biometricEnabled ?? true,
        },
        alerts: {
          includeCritical: true, // Critical always alerts
          includeHigh:     data?.alerts?.includeHigh ?? false,
        },
        statusThresholds: {
          red:   data?.statusThresholds?.red   ?? 30,
          blue:  data?.statusThresholds?.blue  ?? 20,
          green: data?.statusThresholds?.green ?? 10,
        },
        refreshMinutes: data?.refreshMinutes ?? 5,
      });
    } catch (e) {
      setErr(apiErrorMessage(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    try {
      // Partial edits merge over defaults server-side; we send the full known set.
      await axios.put('/api/mobile/config', {
        access:   { enabled: cfg.access.enabled },
        security: { mfaEnabled: cfg.security.mfaEnabled, biometricEnabled: cfg.security.biometricEnabled },
        alerts:   { includeCritical: true, includeHigh: cfg.alerts.includeHigh },
        statusThresholds: {
          red:   Number(cfg.statusThresholds.red)   || 30,
          blue:  Number(cfg.statusThresholds.blue)  || 20,
          green: Number(cfg.statusThresholds.green) || 10,
        },
        refreshMinutes: Number(cfg.refreshMinutes) || 5,
      }, hdr);
      toast.success('Mobile access settings saved.');
    } catch (e) {
      toast.error(apiErrorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  // Platform admin must pick a tenant (config is per-tenant).
  if (!tenantId) return <TenantScopeBanner />;
  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  const card = { p: 3, mb: 2.5, bgcolor: '#0F1A33', border: '1px solid #1E2C52', borderRadius: 2 };
  const label = { color: '#C3D3F2', fontSize: 14 };
  const num = (v, on) => (
    <TextField type="number" size="small" value={v}
      onChange={e => on(e.target.value)}
      sx={{ width: 120, '& input': { color: '#FFFFFF' } }}
      InputProps={{ endAdornment: <InputAdornment position="end" sx={{ '& p': { color: '#8CA0C6' } }}>tasks</InputAdornment> }} />
  );

  return (
    <Box sx={{ maxWidth: 760 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography sx={{ flex: 1, color: '#FFFFFF', fontWeight: 700, fontSize: 18 }}>
          Mobile Access
        </Typography>
        <Button startIcon={<RefreshIcon />} onClick={load} sx={{ color: '#8DBCFF' }}>Reload</Button>
      </Box>
      <Typography sx={{ color: '#8CA0C6', mb: 2, fontSize: 13 }}>
        Configure how this tenant connects through the native Orphos mobile app. The app
        applies these settings on sign-in.
      </Typography>

      {err && <Alert severity="error" sx={{ mb: 2 }}>{err}</Alert>}

      {/* Access */}
      <Paper elevation={0} sx={card}>
        <FormControlLabel
          control={<Switch checked={cfg.access.enabled}
            onChange={e => setCfg(c => ({ ...c, access: { enabled: e.target.checked } }))} />}
          label={<Typography sx={{ color: '#FFFFFF', fontWeight: 600 }}>Enable mobile access</Typography>} />
        <Typography sx={{ color: '#8CA0C6', fontSize: 12, ml: 6 }}>
          When off, the mobile app cannot sign in to this tenant.
        </Typography>
      </Paper>

      {/* Security */}
      <Paper elevation={0} sx={card}>
        <Typography sx={{ color: '#FFFFFF', fontWeight: 700, mb: 1 }}>Security</Typography>
        <FormControlLabel
          control={<Switch checked={cfg.security.mfaEnabled}
            onChange={e => setCfg(c => ({ ...c, security: { ...c.security, mfaEnabled: e.target.checked } }))} />}
          label={<Typography sx={label}>Multi-factor authentication (MFA)</Typography>} />
        <br />
        <FormControlLabel
          control={<Switch checked={cfg.security.biometricEnabled}
            onChange={e => setCfg(c => ({ ...c, security: { ...c.security, biometricEnabled: e.target.checked } }))} />}
          label={<Typography sx={label}>Biometric / PIN app lock</Typography>} />
      </Paper>

      {/* Alerts */}
      <Paper elevation={0} sx={card}>
        <Typography sx={{ color: '#FFFFFF', fontWeight: 700, mb: 0.5 }}>Alerts</Typography>
        <Typography sx={{ color: '#8CA0C6', fontSize: 12, mb: 1 }}>
          Which notification severities surface as mobile alerts.
        </Typography>
        <FormControlLabel
          control={<Checkbox checked disabled />}
          label={<Typography sx={label}>Critical <span style={{ color: '#8CA0C6' }}>(always on)</span></Typography>} />
        <br />
        <FormControlLabel
          control={<Checkbox checked={cfg.alerts.includeHigh}
            onChange={e => setCfg(c => ({ ...c, alerts: { ...c.alerts, includeHigh: e.target.checked } }))} />}
          label={<Typography sx={label}>High priority</Typography>} />
      </Paper>

      {/* Status thresholds */}
      <Paper elevation={0} sx={card}>
        <Typography sx={{ color: '#FFFFFF', fontWeight: 700, mb: 0.5 }}>Busy status (task count)</Typography>
        <Typography sx={{ color: '#8CA0C6', fontSize: 12, mb: 2 }}>
          Open-task ranges that colour the executive status dot.
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: '#FF6B81' }} />
            <Typography sx={{ ...label, flex: 1 }}>Red (Busy) — tasks at or above</Typography>
            {num(cfg.statusThresholds.red, v => setCfg(c => ({ ...c, statusThresholds: { ...c.statusThresholds, red: v } })))}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: '#5B9DFF' }} />
            <Typography sx={{ ...label, flex: 1 }}>Blue (Moderate) — tasks at or above</Typography>
            {num(cfg.statusThresholds.blue, v => setCfg(c => ({ ...c, statusThresholds: { ...c.statusThresholds, blue: v } })))}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: '#3DDC97' }} />
            <Typography sx={{ ...label, flex: 1 }}>Green (Available) — tasks at or below</Typography>
            {num(cfg.statusThresholds.green, v => setCfg(c => ({ ...c, statusThresholds: { ...c.statusThresholds, green: v } })))}
          </Box>
        </Box>
      </Paper>

      {/* Refresh */}
      <Paper elevation={0} sx={card}>
        <Typography sx={{ color: '#FFFFFF', fontWeight: 700, mb: 1 }}>Data refresh</Typography>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <Typography sx={{ ...label, flex: 1 }}>Refresh frequency</Typography>
          <TextField type="number" size="small" value={cfg.refreshMinutes}
            onChange={e => setCfg(c => ({ ...c, refreshMinutes: e.target.value }))}
            sx={{ width: 140, '& input': { color: '#FFFFFF' } }}
            InputProps={{ endAdornment: <InputAdornment position="end" sx={{ '& p': { color: '#8CA0C6' } }}>minutes</InputAdornment> }} />
        </Box>
      </Paper>

      <Divider sx={{ my: 2, borderColor: '#1E2C52' }} />
      <Button variant="contained" startIcon={<SaveIcon />} disabled={saving}
        onClick={save} sx={{ bgcolor: '#3B6FF5', '&:hover': { bgcolor: '#2F5AD6' } }}>
        {saving ? 'Saving…' : 'Save settings'}
      </Button>
    </Box>
  );
}
