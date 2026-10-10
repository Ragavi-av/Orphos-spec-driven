import { useState, useCallback, useEffect } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  TextField, Chip, Switch, FormControlLabel, IconButton, Divider, Tooltip,
  Select, MenuItem, FormControl, InputLabel,
} from '@mui/material';
import SaveIcon          from '@mui/icons-material/Save';
import DeleteIcon        from '@mui/icons-material/Delete';
import PlayArrowIcon     from '@mui/icons-material/PlayArrow';
import CheckCircleIcon   from '@mui/icons-material/CheckCircle';
import ExpandMoreIcon    from '@mui/icons-material/ExpandMore';
import ExpandLessIcon    from '@mui/icons-material/ExpandLess';
import axios from 'axios';
import { useToast } from 'eip-shell-context/ToastProvider';
import ScheduleEditor, { defaultSchedule } from 'eip-shell-context/ScheduleEditor';
import { PROVIDERS, providerDef, errMsg } from './mailMonitorUtils';
import MonitoredMailboxesPanel from './MonitoredMailboxesPanel';

// One Mail Connection, shown as an expandable card. Collapsed it's just a
// summary row; expanding it reveals the connection's own credential fields
// AND its own Monitored Mailboxes — the connection↔mailbox relationship is
// structural (mailboxes only ever render nested under their owning connection).
export default function MailConnectionCard({ connection, isNew, users, connections, onCreated, onUpdated,
                                             onDeleted, onCancelCreate, onMailboxesChanged, onScanStarted }) {
  const globalToast = useToast();
  const [expanded, setExpanded] = useState(isNew);
  const [toast, setToast]       = useState(null);
  const [saving, setSaving]     = useState(false);
  const [deleting, setDeleting] = useState(false);

  const [mailboxes, setMailboxes]     = useState([]);
  const [mailboxCount, setMailboxCount] = useState(connection?.mailboxCount ?? 0);
  const [loadingMailboxes, setLoadingMailboxes] = useState(false);
  const [loadedOnce, setLoadedOnce]   = useState(false);

  const [name, setName]               = useState(connection?.name ?? '');
  const [provider, setProvider]       = useState(connection?.provider ?? 'MS_GRAPH');
  const [directoryId, setDirectoryId] = useState(connection?.directoryId ?? '');
  const [clientId, setClientId]       = useState(connection?.clientId ?? '');
  const [clientSecret, setClientSecret] = useState('');
  const [graphBaseUrl, setGraphBaseUrl] = useState(connection?.graphBaseUrl || 'https://graph.microsoft.com/v1.0');
  const [imapHost, setImapHost]       = useState(connection?.imapHost ?? '');
  const [imapPort, setImapPort]       = useState(connection?.imapPort ?? 993);
  const [imapSsl, setImapSsl]         = useState(connection?.imapSsl !== false);
  const [windowDays, setWindowDays]   = useState(connection?.defaultScanWindowDays ?? 7);
  const [enabled, setEnabled]         = useState(!!connection?.enabled);
  // null = continuous (scan every scheduler sweep, the legacy behavior);
  // an object = custom ScheduleSpec edited via the shared ScheduleEditor.
  const [schedule, setSchedule]       = useState(connection?.schedule ?? null);

  const isImap = providerDef(provider).imap;

  const loadMailboxes = useCallback(async () => {
    if (!connection?.id) return;
    setLoadingMailboxes(true);
    try {
      const r = await axios.get(`/api/mail-insight/admin/connections/${connection.id}`);
      setMailboxes(r.data.mailboxes || []);
      setMailboxCount(r.data.mailboxCount ?? (r.data.mailboxes || []).length);
      if (onMailboxesChanged) onMailboxesChanged();
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Failed to load mailboxes') });
    } finally {
      setLoadingMailboxes(false);
      setLoadedOnce(true);
    }
  }, [connection?.id, onMailboxesChanged]);

  useEffect(() => {
    if (expanded && !isNew && !loadedOnce) loadMailboxes();
  }, [expanded, isNew, loadedOnce, loadMailboxes]);

  // Switching provider seeds IMAP host/port defaults when empty.
  const onProviderChange = (v) => {
    setProvider(v);
    const def = providerDef(v);
    if (def.imap) {
      if (def.host && (!imapHost || PROVIDERS.some(p => p.host === imapHost))) setImapHost(def.host);
      if (def.port) setImapPort(def.port);
    }
  };

  const toggleExpand = () => setExpanded(x => !x);

  const save = async () => {
    setSaving(true);
    try {
      const body = {
        name: name.trim(),
        provider,
        defaultScanWindowDays: Number(windowDays) || 7,
        enabled,
        schedule,
      };
      if (isImap) {
        body.imapHost = imapHost.trim();
        body.imapPort = Number(imapPort) || 993;
        body.imapSsl = imapSsl;
      } else {
        body.directoryId = directoryId.trim();
        body.clientId = clientId.trim();
        body.graphBaseUrl = graphBaseUrl.trim();
        if (clientSecret.trim()) body.clientSecret = clientSecret.trim();
      }
      const res = isNew
        ? await axios.post('/api/mail-insight/admin/connections', body)
        : await axios.put(`/api/mail-insight/admin/connections/${connection.id}`, body);

      setClientSecret('');
      setMailboxes(res.data.mailboxes || []);
      setMailboxCount(res.data.mailboxCount ?? 0);
      setLoadedOnce(true);
      setExpanded(true);
      globalToast.success(isNew ? 'Mail connection created successfully.' : 'Mail connection saved successfully.');
      if (isNew) onCreated(res.data); else onUpdated(res.data);
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Save failed') });
    } finally {
      setSaving(false);
    }
  };

  // Connection-level manual scan: runs immediately for this connection's
  // mailboxes that have scanning enabled (schedule is ignored — explicit action).
  const runConnectionScan = async (e) => {
    e.stopPropagation();
    try {
      const r = await axios.post(`/api/mail-insight/admin/connections/${connection.id}/scan`);
      const msg = r.data?.message || 'Scan started in the background.';
      if (onScanStarted) onScanStarted(msg); else globalToast.success(msg);
    } catch (err) {
      setToast({ sev: 'error', msg: errMsg(err, 'Scan failed to start') });
      setExpanded(true);
    }
  };

  const remove = async (e) => {
    e.stopPropagation();
    if (!window.confirm(`Delete the connection "${connection.name || connection.provider}"? This cannot be undone.`)) return;
    setDeleting(true);
    try {
      await axios.delete(`/api/mail-insight/admin/connections/${connection.id}`);
      globalToast.success('Mail connection deleted.');
      onDeleted(connection.id);
    } catch (err) {
      setToast({ sev: 'error', msg: errMsg(err, 'Delete failed') });
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Paper variant='outlined' sx={{ mb: 1.5, borderRadius: 2, overflow: 'hidden' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, p: 1.5, cursor: 'pointer' }} onClick={toggleExpand}>
        <IconButton size='small' onClick={(e) => { e.stopPropagation(); toggleExpand(); }}>
          {expanded ? <ExpandLessIcon sx={{ fontSize: 18 }} /> : <ExpandMoreIcon sx={{ fontSize: 18 }} />}
        </IconButton>
        <Typography fontWeight={700} fontSize={13} color='#FFFFFF' sx={{ flex: 1 }}>
          {isNew ? 'New connection' : (connection.name || '(unnamed connection)')}
        </Typography>
        {!isNew && (
          <Chip size='small' label={providerDef(connection.provider).label} sx={{ fontSize: 10 }} />
        )}
        {!isNew && (
          connection.enabled
            ? <Chip size='small' label='Enabled' sx={{ fontSize: 10, bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97' }} />
            : <Chip size='small' label='Disabled' sx={{ fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />
        )}
        {!isNew && !providerDef(connection.provider).imap && connection.secretConfigured && (
          <Chip size='small' icon={<CheckCircleIcon sx={{ fontSize: 13 }} />} label='Secret stored'
            sx={{ fontSize: 10, bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97' }} />
        )}
        {!isNew && (
          <Chip size='small' label={`${mailboxCount} mailbox${mailboxCount === 1 ? '' : 'es'}`}
            sx={{ fontSize: 10, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
        )}
        {!isNew && (
          <Tooltip title={connection.enabled
            ? 'Run a scan of this connection now (enabled mailboxes only)'
            : 'Enable scheduled scanning and save before running a scan'}>
            <span>
              <Button size='small' variant='outlined' startIcon={<PlayArrowIcon sx={{ fontSize: 15 }} />}
                onClick={runConnectionScan} disabled={!connection.enabled}
                sx={{ textTransform: 'none', fontSize: 11, py: 0.25 }}>
                Run scan
              </Button>
            </span>
          </Tooltip>
        )}
        {!isNew && (
          <Tooltip title='Delete connection'>
            <span>
              <IconButton size='small' onClick={remove} disabled={deleting}>
                <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
              </IconButton>
            </span>
          </Tooltip>
        )}
        {isNew && (
          <Button size='small' onClick={(e) => { e.stopPropagation(); onCancelCreate(); }}
            sx={{ textTransform: 'none', fontSize: 12 }}>Cancel</Button>
        )}
      </Box>

      {expanded && (
        <Box sx={{ px: 2, pb: 2 }}>
          <Divider sx={{ mb: 2 }} />

          {toast && (
            <Alert severity={toast.sev} sx={{ mb: 2, fontSize: 12 }} onClose={() => setToast(null)}>
              {toast.msg}
            </Alert>
          )}

          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2 }}>
            <TextField size='small' label='Connection name' value={name} onChange={e => setName(e.target.value)}
              placeholder='e.g. Sales Mailbox — Microsoft 365'
              sx={{ gridColumn: '1 / -1', '& input': { fontSize: 12 } }} />

            <FormControl size='small'>
              <InputLabel sx={{ fontSize: 12 }}>Provider</InputLabel>
              <Select label='Provider' value={provider} onChange={e => onProviderChange(e.target.value)}
                sx={{ fontSize: 12 }}>
                {PROVIDERS.map(p => (
                  <MenuItem key={p.value} value={p.value} sx={{ fontSize: 12 }}>{p.label}</MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField size='small' type='number' label='Default scan window (days)' value={windowDays}
              onChange={e => setWindowDays(e.target.value)} inputProps={{ min: 1, max: 90 }}
              sx={{ '& input': { fontSize: 12 } }} />

            {!isImap ? (
              <>
                <TextField size='small' label='Directory (tenant) ID' value={directoryId}
                  onChange={e => setDirectoryId(e.target.value)}
                  placeholder='00000000-0000-0000-0000-000000000000'
                  sx={{ '& input': { fontSize: 12 } }} />
                <TextField size='small' label='Application (client) ID' value={clientId}
                  onChange={e => setClientId(e.target.value)} sx={{ '& input': { fontSize: 12 } }} />
                <TextField size='small' label='Client secret' type='password' value={clientSecret}
                  onChange={e => setClientSecret(e.target.value)}
                  placeholder={connection?.secretConfigured ? '•••••• (leave blank to keep)' : 'paste secret value'}
                  sx={{ '& input': { fontSize: 12 } }} />
                <TextField size='small' label='Graph base URL' value={graphBaseUrl}
                  onChange={e => setGraphBaseUrl(e.target.value)} sx={{ '& input': { fontSize: 12 } }} />
              </>
            ) : (
              <>
                <TextField size='small' label='IMAP host' value={imapHost}
                  onChange={e => setImapHost(e.target.value)} placeholder='imap.example.com'
                  sx={{ '& input': { fontSize: 12 } }} />
                <TextField size='small' type='number' label='IMAP port' value={imapPort}
                  onChange={e => setImapPort(e.target.value)} sx={{ '& input': { fontSize: 12 } }} />
                <FormControlLabel
                  control={<Switch checked={imapSsl} onChange={e => setImapSsl(e.target.checked)} />}
                  label={<Typography fontSize={12}>Use SSL/TLS (imaps)</Typography>} />
              </>
            )}

            <FormControlLabel
              control={<Switch checked={enabled} onChange={e => setEnabled(e.target.checked)} />}
              label={<Typography fontSize={12}>Enable scheduled scanning</Typography>} />

            {enabled && (
              <FormControl size='small' sx={{ minWidth: 200 }}>
                <InputLabel sx={{ fontSize: 12 }}>Scan cadence</InputLabel>
                <Select label='Scan cadence' value={schedule ? 'custom' : 'continuous'} sx={{ fontSize: 12 }}
                  onChange={e => setSchedule(e.target.value === 'custom' ? (schedule || defaultSchedule()) : null)}>
                  <MenuItem value='continuous' sx={{ fontSize: 12 }}>Continuous (every sweep, ~15 min)</MenuItem>
                  <MenuItem value='custom' sx={{ fontSize: 12 }}>Custom schedule…</MenuItem>
                </Select>
              </FormControl>
            )}
          </Box>

          {enabled && schedule && (
            <Box sx={{ mt: 1.5, p: 1.5, border: '1px solid #243A72', borderRadius: 1, bgcolor: '#152652' }}>
              <ScheduleEditor value={schedule} onChange={setSchedule} />
            </Box>
          )}

          <Box sx={{ mt: 2 }}>
            <Button variant='contained' size='small' startIcon={<SaveIcon sx={{ fontSize: 16 }} />}
              disabled={saving || !name.trim()} onClick={save} sx={{ textTransform: 'none', fontSize: 12 }}>
              {saving ? <CircularProgress size={14} color='inherit' /> : 'Save connection'}
            </Button>
          </Box>

          <Typography fontSize={11} color='#7D92C4' mt={1.5}>
            {!isImap
              ? <>Requires an Azure app registration with application permissions <b>Mail.Read</b> and <b>Calendars.Read</b> (admin-consented). The secret is stored in Infisical — only a reference is kept in the database.</>
              : <>Each mailbox authenticates over IMAP with its own <b>app password</b> (set per mailbox below). For Gmail/Zoho, generate an app password in the account's security settings. IMAP covers mail only — calendar is available on Microsoft 365.</>}
          </Typography>

          {!isNew && (
            <>
              <Divider sx={{ my: 2 }} />
              {loadingMailboxes ? (
                <Box sx={{ py: 2, textAlign: 'center' }}><CircularProgress size={18} /></Box>
              ) : (
                <MonitoredMailboxesPanel connectionId={connection.id} mailboxes={mailboxes} isImap={isImap}
                  users={users} connections={connections} onChanged={loadMailboxes}
                  onScanStarted={onScanStarted} />
              )}
            </>
          )}
        </Box>
      )}
    </Paper>
  );
}
