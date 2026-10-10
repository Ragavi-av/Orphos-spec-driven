import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Chip, CircularProgress, Alert, Button, Divider,
} from '@mui/material';
import CheckIcon  from '@mui/icons-material/Check';
import CloseIcon  from '@mui/icons-material/Close';
import RefreshIcon from '@mui/icons-material/Refresh';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';

/**
 * The single approval inbox (TO-BE design §5.2, §8). Lists the PENDING actions ACT_WITH_APPROVAL
 * agents have proposed and lets an admin approve (execute the write tool) or reject. Delegated
 * through the orchestrator ({@code /api/orchestrator/approvals}), which fronts the runtime approval
 * API. Tenant-scoped.
 */
export default function AgentApprovalsTab({ tenantId }) {
  const [actions, setActions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);
  const [note, setNote]       = useState(null);
  const [busy, setBusy]       = useState({});

  const hdr = { headers: { 'X-Tenant-Id': tenantId } };

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/orchestrator/approvals', hdr);
      setActions(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setErr(e?.response?.data?.error || 'Could not load the approval inbox.');
    }
    setLoading(false);
  }, [tenantId]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);

  const act = async (actionId, verb) => {
    setBusy(b => ({ ...b, [actionId]: true })); setErr(null); setNote(null);
    try {
      const r = await axios.post(`/api/orchestrator/approvals/${actionId}/${verb}`, {}, hdr);
      setNote(verb === 'approve'
        ? `Action approved and executed${r?.data?.result ? `: ${String(r.data.result).slice(0, 200)}` : '.'}`
        : 'Action rejected.');
      await load();
    } catch (e) {
      setErr(e?.response?.data?.error || `Failed to ${verb} the action.`);
    }
    setBusy(b => ({ ...b, [actionId]: false }));
  };

  if (!tenantId) {
    return <Alert severity="info" sx={{ fontSize: 13 }}>Select a tenant to view its approval inbox.</Alert>;
  }
  if (loading) {
    return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2, gap: 2, flexWrap: 'wrap' }}>
        <Typography fontSize={13} color="#C3D3F2">
          Write actions proposed by agents awaiting a human decision. Approving runs the write tool;
          rejecting discards it.
        </Typography>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} sx={{ textTransform: 'none', fontSize: 12 }}>
          Refresh
        </Button>
      </Box>

      {err  && <Alert severity="error"   sx={{ mb: 2, fontSize: 13 }} onClose={() => setErr(null)}>{err}</Alert>}
      {note && <Alert severity="success" sx={{ mb: 2, fontSize: 13 }} onClose={() => setNote(null)}>{note}</Alert>}

      <CollapsibleSection title='Pending approvals' count={actions.length}>
      {actions.length === 0 ? (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center', borderRadius: 2, color: '#7D92C4', fontSize: 13 }}>
          No pending approvals. Actions proposed by ACT_WITH_APPROVAL agents appear here.
        </Paper>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          {actions.map(a => (
            <Paper key={a.actionId} elevation={0}
              sx={{ p: 2, border: '1px solid rgba(255, 184, 77, 0.45)', borderRadius: 2, bgcolor: 'rgba(255, 184, 77, 0.16)' }}>
              <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2 }}>
                <Box sx={{ flex: 1 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                    <Chip size="small" label={a.agentId}
                      sx={{ height: 20, fontSize: 10, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF', fontWeight: 700 }} />
                    <Chip size="small" label={a.toolName}
                      sx={{ height: 20, fontSize: 10, bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', fontWeight: 700, fontFamily: 'monospace' }} />
                    <Chip size="small" label={a.state || 'PENDING'}
                      sx={{ height: 20, fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2', fontWeight: 700 }} />
                  </Box>
                  <Typography fontSize={13} color="#FFFFFF" fontWeight={600}>{a.humanSummary}</Typography>
                  {a.arguments && Object.keys(a.arguments).length > 0 && (
                    <Box component="pre" sx={{
                      mt: 0.5, mb: 0, fontSize: 11, color: '#C3D3F2', fontFamily: 'monospace',
                      whiteSpace: 'pre-wrap', bgcolor: '#0F2450', p: 1, borderRadius: 1, maxHeight: 140, overflow: 'auto' }}>
                      {JSON.stringify(a.arguments, null, 2)}
                    </Box>
                  )}
                </Box>
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, pt: 0.25 }}>
                  <Button size="small" variant="contained" color="success" startIcon={<CheckIcon />}
                    disabled={!!busy[a.actionId]} onClick={() => act(a.actionId, 'approve')}
                    sx={{ textTransform: 'none', fontSize: 12 }}>
                    {busy[a.actionId] ? '…' : 'Approve'}
                  </Button>
                  <Button size="small" variant="outlined" color="error" startIcon={<CloseIcon />}
                    disabled={!!busy[a.actionId]} onClick={() => act(a.actionId, 'reject')}
                    sx={{ textTransform: 'none', fontSize: 12 }}>
                    Reject
                  </Button>
                </Box>
              </Box>
            </Paper>
          ))}
        </Box>
      )}
      </CollapsibleSection>
    </Box>
  );
}
