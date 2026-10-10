import { useEffect, useState, useCallback } from 'react';
import {
  Box, Paper, Typography, Button, CircularProgress, TextField, Chip, Tooltip,
} from '@mui/material';
import InboxIcon       from '@mui/icons-material/Inbox';
import RefreshIcon     from '@mui/icons-material/Refresh';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { useToast } from 'eip-shell-context/ToastProvider';

// Self-service control: lets the signed-in user monitor their OWN SSO mailbox.
// For Microsoft 365 it works straight from the SSO identity (no extra login);
// for IMAP providers (Google/Zoho) it prompts for an app password.
// Calls onChanged() after a scan so the parent tab can refresh its list.
export default function MyMailboxBanner({ onChanged }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [me, setMe]         = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy]     = useState(false);
  const [appPassword, setAppPassword] = useState('');
  const [error, setError]   = useState(null);

  const load = useCallback(async () => {
    try {
      const r = await axios.get('/api/mail-insight/me');
      setMe(r.data);
    } catch {
      setMe(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const enable = async () => {
    setBusy(true); setError(null);
    try {
      const body = {};
      if (me?.requiresAppPassword) {
        if (!appPassword.trim()) { setError(t('mailbox.appPasswordRequired', 'App password is required for this provider.')); setBusy(false); return; }
        body.appPassword = appPassword.trim();
      }
      await axios.post('/api/mail-insight/me/enable', body);
      toast.success(t('mailbox.enabled', 'Mailbox monitoring enabled successfully.'));
      setAppPassword('');
      await scan();          // first scan right after enabling
      await load();
    } catch (e) {
      setError(e?.response?.data?.error || t('mailbox.enableFailed', 'Could not enable monitoring.'));
    } finally {
      setBusy(false);
    }
  };

  const scan = async () => {
    setBusy(true); setError(null);
    try {
      await axios.post('/api/mail-insight/me/scan');
      await load();
      if (onChanged) onChanged();
    } catch (e) {
      setError(e?.response?.data?.error || t('mailbox.scanFailed', 'Scan failed.'));
    } finally {
      setBusy(false);
    }
  };

  if (loading || !me || !me.email) return null;

  // Connection not set up at all → tell the user to ask an admin (no noise if monitored).
  const connectionReady = me.connectionEnabled;

  return (
    <Paper variant='outlined' sx={{ p: 1.5, mb: 2, borderRadius: 2, bgcolor: '#1A2247' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <InboxIcon sx={{ fontSize: 18, color: '#00B0F0' }} />
        <Box sx={{ flex: 1, minWidth: 200 }}>
          {me.monitored ? (
            <Typography fontSize={12.5} color='#FFFFFF'>
              {t('mailbox.monitoring', 'Monitoring your mailbox')} <b>{me.address || me.email}</b>
              {' '}{me.supportsCalendar ? t('mailbox.mailCalendar', '(mail + calendar)') : t('mailbox.mailOnly', '(mail)')}
              {/* Scan status is a backend enum (SUCCESS / FAILED) that was rendered
                  raw, so it stayed English on a Japanese board. The set is finite,
                  so it maps to catalog keys; an unrecognised value falls back to the
                  raw code rather than rendering blank. */}
              {me.lastScanStatus && (
                <Chip size='small' label={t(`mailbox.scanStatus.${me.lastScanStatus}`, me.lastScanStatus)}
                  icon={me.lastScanStatus === 'SUCCESS' ? <CheckCircleIcon sx={{ fontSize: 12 }} /> : undefined}
                  sx={{ ml: 1, fontSize: 9,
                        bgcolor: me.lastScanStatus === 'FAILED' ? 'rgba(255, 0, 0, 0.14)' : 'rgba(0, 176, 80, 0.14)',
                        color: me.lastScanStatus === 'FAILED' ? '#FF6B6B' : '#00B050' }} />
              )}
            </Typography>
          ) : (
            <Typography fontSize={12.5} color='#FFFFFF'>
              {t('mailbox.inviteBefore', 'Monitor your own mailbox')} <b>{me.email}</b> {t('mailbox.inviteAfter', 'to surface your tasks, action items and alerts')}
              {me.supportsCalendar ? t('mailbox.plusCalendar', ', plus calendar events') : ''}.
            </Typography>
          )}
          {error && <Typography fontSize={11} color='#FF6B6B' mt={0.5}>{error}</Typography>}
          {!connectionReady && !me.monitored && (
            <Typography fontSize={11} color='#E8A33D' mt={0.5}>
              {t('mailbox.notConfigured', 'Your organization’s mail connection isn’t set up yet — ask an admin to configure it under Administration → Mailbox Monitor.')}
            </Typography>
          )}
        </Box>

        {/* IMAP providers need an app password to read the mailbox */}
        {!me.monitored && me.requiresAppPassword && connectionReady && (
          <TextField size='small' type='password' label={t('mailbox.appPassword', 'App password')} value={appPassword}
            onChange={e => setAppPassword(e.target.value)}
            sx={{ width: 170, '& input': { fontSize: 12 } }} />
        )}

        <Box sx={{ display: 'flex', gap: 1 }}>
          {me.monitored ? (
            // Scanning runs automatically on a schedule; this is an on-demand
            // "fetch new mail + reload" for when the user wants it immediately.
            <Tooltip title={t('mailbox.fetchNow', 'Fetch new mail now and reload')}>
              <Button size='small' variant='outlined' disabled={busy}
                startIcon={busy ? <CircularProgress size={13} color='inherit' /> : <RefreshIcon sx={{ fontSize: 16 }} />}
                onClick={scan} sx={{ textTransform: 'none', fontSize: 12 }}>
                {t('mailbox.refresh', 'Refresh')}
              </Button>
            </Tooltip>
          ) : (
            <Tooltip title={connectionReady ? '' : t('mailbox.notConfiguredShort', 'Mail connection not configured yet')}>
              <span>
                <Button size='small' variant='contained' disabled={busy || !connectionReady}
                  onClick={enable} sx={{ textTransform: 'none', fontSize: 12 }}>
                  {busy ? <CircularProgress size={14} color='inherit' /> : t('mailbox.monitorMine', 'Monitor my mailbox')}
                </Button>
              </span>
            </Tooltip>
          )}
        </Box>
      </Box>
    </Paper>
  );
}
