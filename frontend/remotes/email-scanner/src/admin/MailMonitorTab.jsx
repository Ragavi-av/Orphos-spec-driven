import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Divider, Tooltip, IconButton,
  Table, TableHead, TableRow, TableCell, TableBody,
} from '@mui/material';
import AddIcon        from '@mui/icons-material/Add';
import ScienceIcon    from '@mui/icons-material/Science';
import RefreshIcon    from '@mui/icons-material/Refresh';
import axios from 'axios';
import { useTranslation } from 'react-i18next';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { errMsg, diagnoseMailError, fmtDate } from './mailMonitorUtils';
import MailConnectionCard from './MailConnectionCard';
import MonitoredMailboxesPanel from './MonitoredMailboxesPanel';

// CXO-facing config for the mailbox/calendar monitor. A tenant may have several
// Mail Connections (Microsoft 365, Google, Zoho, generic IMAP — of the same or
// different provider); each is rendered as an expandable card that owns its own
// credential fields and its own Monitored Mailboxes, so the connection↔mailbox
// relationship is structural rather than implied by a flat shared list.
export default function MailMonitorTab() {
  const { t } = useTranslation();
  const [connections, setConnections] = useState([]);
  const [loading, setLoading]         = useState(true);
  const [addingNew, setAddingNew]     = useState(false);

  // Tenant-wide "did the last scan fail" banner — driven by the unfiltered
  // mailbox list + run history, independent of which connection card is open.
  const [allMailboxes, setAllMailboxes] = useState([]);
  const [runs, setRuns]     = useState([]);
  const [scanning, setScanning] = useState(false);
  const [toast, setToast]   = useState(null);

  // Tenant users — populate the "User" picker in each connection's mailbox form.
  const [users, setUsers] = useState([]);

  // Quiet refresh (no page-level spinner) — used after mutations so a mailbox
  // reassignment doesn't flash the whole tab back to a loading state.
  const refreshConnections = useCallback(async () => {
    try {
      const r = await axios.get('/api/mail-insight/admin/connections');
      setConnections(r.data.result || []);
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Failed to load connections') });
    }
  }, []);

  const loadConnections = useCallback(async () => {
    setLoading(true);
    await refreshConnections();
    setLoading(false);
  }, [refreshConnections]);

  const loadAllMailboxes = useCallback(async () => {
    try {
      const r = await axios.get('/api/mail-insight/admin/mailboxes');
      setAllMailboxes(r.data.result || []);
    } catch { /* surfaced elsewhere */ }
  }, []);

  // Mailboxes that don't cleanly belong to any existing connection (blank
  // mailConnectionId, or one pointing at a connection that's since been
  // deleted) — surfaced separately so they're never silently hidden or shown
  // under the wrong connection's card.
  const [unassigned, setUnassigned] = useState([]);
  const loadUnassigned = useCallback(async () => {
    try {
      const r = await axios.get('/api/mail-insight/admin/mailboxes', { params: { unassigned: true } });
      setUnassigned(r.data.result || []);
    } catch { /* surfaced elsewhere */ }
  }, []);

  const refreshCounts = useCallback(() => { refreshConnections(); loadUnassigned(); },
    [refreshConnections, loadUnassigned]);

  const loadRuns = useCallback(async () => {
    try {
      const r = await axios.get('/api/mail-insight/admin/runs', { params: { limit: 10 } });
      const rows = r.data.result || [];
      setRuns(rows);
      return rows;
    } catch { /* non-critical */ return []; }
  }, []);

  // Tenant user list (SCIM, served by admin-service). The acting admin's JWT
  // authorises it; on failure the picker degrades to "tenant-wide" only.
  const loadUsers = useCallback(async () => {
    try {
      const r = await axios.get('/api/admin/scim/v2/Users');
      setUsers(r.data.Resources ?? r.data.users ?? []);
    } catch { /* picker degrades gracefully */ }
  }, []);

  useEffect(() => { loadConnections(); loadAllMailboxes(); loadUnassigned(); loadRuns(); loadUsers(); },
    [loadConnections, loadAllMailboxes, loadUnassigned, loadRuns, loadUsers]);

  const onConnectionCreated = (created) => {
    setAddingNew(false);
    setConnections(list => [...list, created]);
  };
  const onConnectionUpdated = (updated) => {
    setConnections(list => list.map(c => c.id === updated.id ? updated : c));
  };
  const onConnectionDeleted = (id) => {
    setConnections(list => list.filter(c => c.id !== id));
  };

  // The scan itself runs in the background on the server (classification calls
  // the tenant's LLM per item, which can take minutes — far past any reverse-proxy
  // timeout if the request waited for it). The trigger returns immediately; poll
  // run history for a while so the UI reflects completion without a manual refresh.
  const POLL_INTERVAL_MS = 4000;
  const POLL_MAX_TICKS = 30; // ~2 minutes

  const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

  const pollUntilDone = async () => {
    for (let i = 0; i < POLL_MAX_TICKS; i++) {
      await sleep(POLL_INTERVAL_MS);
      const rows = await loadRuns();
      if (!rows.some(r => r.status === 'RUNNING')) break;
    }
    await loadAllMailboxes();
    await refreshConnections();
    setScanning(false);
  };

  const runScan = async (mode) => {
    setScanning(true);
    try {
      const r = await axios.post('/api/mail-insight/admin/scan', null, { params: { mode } });
      setToast({ sev: 'info', msg: r.data.message || 'Scan started in the background.' });
      pollUntilDone();
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Scan failed') });
      setScanning(false);
    }
  };

  // Connection- and mailbox-level scan buttons live inside the cards; they call
  // back here so the tab shows the toast and polls run history to completion.
  const onScanStarted = (message) => {
    setToast({ sev: 'info', msg: message || 'Scan started in the background.' });
    setScanning(true);
    pollUntilDone();
  };

  if (loading) {
    return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={22} /></Box>;
  }

  // Surface the most recent scan failure prominently with an actionable hint —
  // the per-mailbox tooltip alone is easy to miss.
  const failedSource =
    allMailboxes.find(m => m.lastScanStatus === 'FAILED' && m.lastError)
    || runs.find(r => r.status === 'FAILED' && r.error);
  const failRaw  = failedSource ? (failedSource.lastError || failedSource.error) : null;
  const failHint = diagnoseMailError(failRaw);

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.mailMonitor.scopeResource', 'mailbox monitor')} />

      <Box sx={{ mb: 2.5 }}>
        <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Mailbox &amp; Calendar Monitor</Typography>
        <Typography fontSize={12} color='#7D92C4'>
          Connect one or more mailbox providers and choose which mailboxes to scan under each. The system
          reads mail (and calendar, for Microsoft 365) over a rolling window and infers <b>Tasks for Action</b> and
          <b> Notifications</b>.
        </Typography>
      </Box>

      {toast && (
        <Alert severity={toast.sev} sx={{ mb: 2, fontSize: 12 }} onClose={() => setToast(null)}>
          {toast.msg}
        </Alert>
      )}

      {failRaw && (
        <Alert severity='warning' sx={{ mb: 2, fontSize: 12 }}>
          <Typography fontSize={12} fontWeight={700} mb={0.5}>Last scan failed</Typography>
          {failHint && <Typography fontSize={12} mb={0.5}>{failHint}</Typography>}
          <Typography fontSize={11} color='#FFB84D' sx={{ wordBreak: 'break-word' }}>
            Details: {String(failRaw).slice(0, 300)}
          </Typography>
        </Alert>
      )}

      {/* ── Mail connections ── */}
      <Box sx={{ mb: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
          <Typography fontWeight={600} fontSize={13} color='#C3D3F2' sx={{ flex: 1 }}>Mail connections</Typography>
          {!addingNew && (
            <Button variant='outlined' size='small' startIcon={<AddIcon sx={{ fontSize: 16 }} />}
              onClick={() => setAddingNew(true)} sx={{ textTransform: 'none', fontSize: 12 }}>
              Add connection
            </Button>
          )}
        </Box>

        {addingNew && (
          <MailConnectionCard isNew users={users} connections={connections}
            onCreated={onConnectionCreated} onCancelCreate={() => setAddingNew(false)} />
        )}

        {connections.length === 0 && !addingNew ? (
          <Paper variant='outlined' sx={{ p: 2, borderRadius: 2 }}>
            <Typography fontSize={12} color='#7D92C4'>
              No mail connections yet. Click <b>Add connection</b> to connect Microsoft 365, Google, Zoho, or another IMAP provider.
            </Typography>
          </Paper>
        ) : (
          connections.map(conn => (
            <MailConnectionCard key={conn.id} connection={conn} users={users} connections={connections}
              onUpdated={onConnectionUpdated} onDeleted={onConnectionDeleted} onMailboxesChanged={refreshCounts}
              onScanStarted={onScanStarted} />
          ))
        )}
      </Box>

      {/* ── Unassigned mailboxes — don't cleanly belong to any connection above ── */}
      {unassigned.length > 0 && (
        <Paper variant='outlined' sx={{ p: 2, mb: 2, borderRadius: 2, borderColor: 'rgba(255, 184, 77, 0.45)' }}>
          <Typography fontWeight={600} fontSize={13} color='#FFB84D' mb={0.5}>
            Unassigned mailboxes ({unassigned.length})
          </Typography>
          <Typography fontSize={11} color='#7D92C4' mb={1.5}>
            These mailboxes aren't linked to any connection above, so they're never scanned. Use the
            <b> Connection</b> column below to assign each one to the correct connection.
          </Typography>
          <MonitoredMailboxesPanel connectionId='' mailboxes={unassigned} isImap={false} users={users}
            connections={connections} allowAdd={false} onChanged={refreshCounts}
            onScanStarted={onScanStarted} />
        </Paper>
      )}

      {/* ── Run history ── */}
      <Paper variant='outlined' sx={{ p: 2, borderRadius: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Typography fontWeight={600} fontSize={13} color='#C3D3F2'>Run history</Typography>
          {scanning && <CircularProgress size={14} sx={{ ml: 0.5 }} />}
          <Box sx={{ flex: 1 }} />
          <Button variant='outlined' size='small' startIcon={<ScienceIcon sx={{ fontSize: 16 }} />}
            disabled={scanning} onClick={() => runScan('sample')}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            Sample scan
          </Button>
          <Tooltip title='Refresh run history'>
            <IconButton size='small' onClick={loadRuns}><RefreshIcon sx={{ fontSize: 16 }} /></IconButton>
          </Tooltip>
        </Box>
        <Typography fontSize={11} color='#7D92C4' mt={1}>
          Use <b>Run scan</b> on a connection to scan its enabled mailboxes now, or the <b>▶</b> button on an
          individual mailbox to scan just that one. <b>Sample scan</b> feeds built-in demo items through the
          full inference pipeline — handy before a connection is live.
        </Typography>

        {runs.length > 0 && (
          <>
            <Divider sx={{ my: 1.5 }} />
            <Table size='small'>
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Started</TableCell>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Connection</TableCell>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Mailbox</TableCell>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Status</TableCell>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Created</TableCell>
                  <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Updated</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {runs.map(run => (
                  <TableRow key={run.id}>
                    <TableCell sx={{ fontSize: 11 }}>{fmtDate(run.startedAt)}</TableCell>
                    <TableCell sx={{ fontSize: 11 }}>{run.connectionName || '—'}</TableCell>
                    <TableCell sx={{ fontSize: 11 }}>
                      {run.mailboxAddress || run.mailboxId || '—'}
                      {run.mailboxDisplayName ? ` · ${run.mailboxDisplayName}` : ''}
                    </TableCell>
                    <TableCell sx={{ fontSize: 11, color: run.status === 'FAILED' ? '#FF6B81' : '#3DDC97' }}>
                      <Tooltip title={run.error || ''} arrow>
                        <span style={{ cursor: run.error ? 'help' : 'default' }}>{run.status}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={{ fontSize: 11 }}>{run.itemsCreated ?? 0}</TableCell>
                    <TableCell sx={{ fontSize: 11 }}>{run.itemsUpdated ?? 0}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </Paper>
    </Box>
  );
}
