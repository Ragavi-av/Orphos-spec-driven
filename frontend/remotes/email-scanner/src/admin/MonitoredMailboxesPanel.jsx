import { useState } from 'react';
import {
  Box, Typography, Button, Alert, TextField, Chip, Switch, FormControlLabel,
  IconButton, Tooltip, Select, MenuItem, FormControl, InputLabel, Checkbox,
  Table, TableHead, TableRow, TableCell, TableBody,
} from '@mui/material';
import AddIcon       from '@mui/icons-material/Add';
import DeleteIcon    from '@mui/icons-material/Delete';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import axios from 'axios';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { useToast } from 'eip-shell-context/ToastProvider';
import { errMsg } from './mailMonitorUtils';

// Monitored mailboxes, optionally scoped to a single Mail Connection (connectionId).
// Add form + table. Every row shows which connection it's assigned to and lets
// the admin move it — this is how a mailbox that ended up under the wrong
// connection (e.g. a Gmail address parked under a Microsoft 365 connection)
// gets corrected, rather than staying silently mis-scanned.
export default function MonitoredMailboxesPanel({ connectionId, mailboxes, isImap, users, connections,
                                                  onChanged, onScanStarted, allowAdd = true }) {
  const globalToast = useToast();
  const [toast, setToast] = useState(null);

  const [newAddress, setNewAddress] = useState('');
  const [newName, setNewName]       = useState('');
  const [newWindow, setNewWindow]   = useState('');
  const [newAppPassword, setNewAppPassword] = useState('');
  const [newScanMail, setNewScanMail] = useState(true);
  const [newScanCal, setNewScanCal]   = useState(true);
  const [newOwnerSub, setNewOwnerSub] = useState('');   // '' = tenant-wide

  // SCIM 'id' is the Keycloak user UUID == the JWT 'sub' the backend filters on.
  const userEmail = (u) => u?.emails?.[0]?.value || u?.userName || '';
  const userLabel = (u) => u?.displayName || u?.name?.formatted || userEmail(u) || u?.id;
  const ownerName = (sub) => {
    if (!sub) return 'Tenant-wide';
    const u = users.find(x => x.id === sub);
    return u ? userLabel(u) : sub;
  };

  // Picking a user pre-fills the mailbox address / name with theirs (still
  // editable) — the common case is monitoring a user's own mailbox.
  const onOwnerChange = (sub) => {
    setNewOwnerSub(sub);
    const u = users.find(x => x.id === sub);
    if (u) {
      if (!newAddress.trim()) setNewAddress(userEmail(u));
      if (!newName.trim())    setNewName(userLabel(u));
    }
  };

  const addMailbox = async () => {
    if (!newAddress.trim()) return;
    try {
      const owner = users.find(u => u.id === newOwnerSub);
      const body = {
        address: newAddress.trim(),
        displayName: newName.trim(),
        scanWindowDays: newWindow ? Number(newWindow) : undefined,
        scanMail: newScanMail,
        scanCalendar: isImap ? false : newScanCal,
        ownerSub: newOwnerSub,
        ownerEmail: owner ? userEmail(owner) : '',
        mailConnectionId: connectionId,
      };
      if (isImap && newAppPassword.trim()) body.appPassword = newAppPassword.trim();
      await axios.post('/api/mail-insight/admin/mailboxes', body);
      setNewAddress(''); setNewName(''); setNewWindow(''); setNewAppPassword(''); setNewOwnerSub('');
      await onChanged();
      setToast({ sev: 'success', msg: 'Mailbox added.' });
      globalToast.success('Mailbox added successfully.');
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Add failed') });
    }
  };

  const removeMailbox = async (id) => {
    try {
      await axios.delete(`/api/mail-insight/admin/mailboxes/${id}`);
      await onChanged();
      globalToast.success('Mailbox removed successfully.');
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Remove failed') });
    }
  };

  const reassignMailbox = async (id, newConnectionId) => {
    try {
      await axios.patch(`/api/mail-insight/admin/mailboxes/${id}`, { mailConnectionId: newConnectionId });
      await onChanged();
      globalToast.success('Mailbox reassigned successfully.');
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Reassign failed') });
    }
  };

  const setMailboxScanEnabled = async (id, enabled) => {
    try {
      await axios.patch(`/api/mail-insight/admin/mailboxes/${id}`, { enabled });
      await onChanged();
      globalToast.success(enabled ? 'Scan enabled for mailbox.' : 'Scan disabled for mailbox.');
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Update failed') });
    }
  };

  const runMailboxScan = async (mb) => {
    try {
      const r = await axios.post(`/api/mail-insight/admin/mailboxes/${mb.id}/scan`);
      const msg = r.data?.message || `Scan of ${mb.address} started in the background.`;
      if (onScanStarted) onScanStarted(msg); else globalToast.success(msg);
    } catch (e) {
      setToast({ sev: 'error', msg: errMsg(e, 'Scan failed to start') });
    }
  };

  const connectionName = (id) => {
    const c = (connections || []).find(x => x.id === id);
    return c ? c.name : null;
  };

  return (
    <Box>
      <Typography fontSize={11} color='#7D92C4' mb={1.5}>
        Assign each mailbox to a <b>user</b> — the inferred Tasks for Action and Notifications are
        shown only to that user. Leave the user as <i>Tenant-wide</i> to share with everyone.
      </Typography>

      {toast && (
        <Alert severity={toast.sev} sx={{ mb: 1.5, fontSize: 12 }} onClose={() => setToast(null)}>
          {toast.msg}
        </Alert>
      )}

      <CollapsibleSection title='Monitored mailboxes' count={mailboxes.length} detailsSx={{ p: 0 }}>
      {mailboxes.length === 0 ? (
        <Typography fontSize={12} color='#7D92C4' sx={{ p: 2 }}>No mailboxes configured yet.</Typography>
      ) : (
        <Table size='small'>
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Address</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Connection</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>User</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Window</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Sources</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }} align='center'>Scan</TableCell>
              <TableCell sx={{ fontSize: 11, fontWeight: 700 }}>Last scan</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {mailboxes.map(mb => (
              <TableRow key={mb.id}>
                <TableCell sx={{ fontSize: 12 }}>
                  {mb.address}{mb.displayName ? ` · ${mb.displayName}` : ''}
                  {isImap && !mb.secretConfigured && (
                    <Chip label='no app password' size='small'
                      sx={{ fontSize: 9, ml: 0.5, bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81' }} />
                  )}
                </TableCell>
                <TableCell>
                  <FormControl size='small' sx={{ minWidth: 150 }}>
                    <Select value={mb.mailConnectionId && connectionName(mb.mailConnectionId) ? mb.mailConnectionId : ''}
                      displayEmpty onChange={e => reassignMailbox(mb.id, e.target.value)} sx={{ fontSize: 11 }}>
                      <MenuItem value='' sx={{ fontSize: 11 }}><em>Unassigned</em></MenuItem>
                      {(connections || []).map(c => (
                        <MenuItem key={c.id} value={c.id} sx={{ fontSize: 11 }}>{c.name}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </TableCell>
                <TableCell>
                  {mb.ownerSub
                    ? <Chip label={ownerName(mb.ownerSub)} size='small'
                        sx={{ fontSize: 9, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
                    : <Typography fontSize={11} color='#7D92C4'>Tenant-wide</Typography>}
                </TableCell>
                <TableCell sx={{ fontSize: 12 }}>{mb.scanWindowDays > 0 ? `${mb.scanWindowDays}d` : 'default'}</TableCell>
                <TableCell sx={{ fontSize: 11 }}>
                  {mb.scanMail !== false && <Chip label='Mail' size='small' sx={{ fontSize: 9, mr: 0.5 }} />}
                  {mb.scanCalendar !== false && <Chip label='Calendar' size='small' sx={{ fontSize: 9 }} />}
                </TableCell>
                <TableCell align='center'>
                  <Tooltip title={mb.enabled === false
                    ? 'Scanning disabled — tick to include this mailbox in scans'
                    : 'Scanning enabled — untick to skip this mailbox in scans'}>
                    <Checkbox size='small' sx={{ p: 0.25 }}
                      checked={mb.enabled !== false}
                      onChange={e => setMailboxScanEnabled(mb.id, e.target.checked)} />
                  </Tooltip>
                </TableCell>
                <TableCell sx={{ fontSize: 11, color: mb.lastScanStatus === 'FAILED' ? '#FF6B81' : '#C3D3F2' }}>
                  {mb.lastScanStatus
                    ? <Tooltip title={mb.lastError || ''} arrow>
                        <span style={{ cursor: mb.lastError ? 'help' : 'default' }}>{mb.lastScanStatus}</span>
                      </Tooltip>
                    : '—'}
                </TableCell>
                <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                  <Tooltip title='Run scan for this mailbox now'>
                    <IconButton size='small' onClick={() => runMailboxScan(mb)}
                      sx={{ color: '#3DDC97', '&:hover': { bgcolor: 'rgba(61, 220, 151, 0.14)' } }}>
                      <PlayArrowIcon sx={{ fontSize: 18 }} />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title='Remove'>
                    <IconButton size='small' onClick={() => removeMailbox(mb.id)}>
                      <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                    </IconButton>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      </CollapsibleSection>

      {/* Add form sits below the list so existing mailboxes stay in view. */}
      {allowAdd && (
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center', mt: 1.5 }}>
          <FormControl size='small' sx={{ minWidth: 190 }}>
            <InputLabel sx={{ fontSize: 12 }}>User</InputLabel>
            <Select label='User' value={newOwnerSub} onChange={e => onOwnerChange(e.target.value)}
              sx={{ fontSize: 12 }}>
              <MenuItem value='' sx={{ fontSize: 12 }}><em>Tenant-wide (all users)</em></MenuItem>
              {users.map(u => (
                <MenuItem key={u.id} value={u.id} sx={{ fontSize: 12 }}>{userLabel(u)}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField size='small' label='Mailbox address' value={newAddress}
            onChange={e => setNewAddress(e.target.value)} placeholder='exec@company.com'
            sx={{ flex: 1, minWidth: 200, '& input': { fontSize: 12 } }} />
          <TextField size='small' label='Display name' value={newName}
            onChange={e => setNewName(e.target.value)}
            sx={{ minWidth: 140, '& input': { fontSize: 12 } }} />
          {isImap && (
            <TextField size='small' type='password' label='App password' value={newAppPassword}
              onChange={e => setNewAppPassword(e.target.value)}
              sx={{ minWidth: 150, '& input': { fontSize: 12 } }} />
          )}
          <TextField size='small' type='number' label='Window (days)' value={newWindow}
            onChange={e => setNewWindow(e.target.value)} placeholder='default'
            sx={{ width: 110, '& input': { fontSize: 12 } }} />
          <FormControlLabel control={<Switch size='small' checked={newScanMail}
            onChange={e => setNewScanMail(e.target.checked)} />}
            label={<Typography fontSize={11}>Mail</Typography>} />
          {!isImap && (
            <FormControlLabel control={<Switch size='small' checked={newScanCal}
              onChange={e => setNewScanCal(e.target.checked)} />}
              label={<Typography fontSize={11}>Calendar</Typography>} />
          )}
          <Button variant='outlined' size='small' startIcon={<AddIcon sx={{ fontSize: 16 }} />}
            onClick={addMailbox} disabled={!newAddress.trim() || (isImap && !newAppPassword.trim())}
            sx={{ textTransform: 'none', fontSize: 12 }}>Add</Button>
        </Box>
      )}
    </Box>
  );
}
