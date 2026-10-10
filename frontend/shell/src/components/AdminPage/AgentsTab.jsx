import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Switch, Chip, CircularProgress, Alert, Tooltip,
  Select, MenuItem, Divider, Button,
} from '@mui/material';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import BuildIcon    from '@mui/icons-material/Build';
import CircleIcon   from '@mui/icons-material/Circle';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import RefreshIcon  from '@mui/icons-material/Refresh';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';

/**
 * Agents & MCP tool servers (TO-BE design §6). Lists what's registered in platform-registry,
 * with per-tenant enable toggles (reusing the plugin feature-gate) and, for agents, a per-tenant
 * autonomy override (§12 Phase 4). DEGRADED agents are flagged with their missing capabilities.
 * Platform-admin, tenant-scoped.
 */

const AUTONOMY = ['SUGGEST_ONLY', 'ACT_WITH_APPROVAL', 'AUTONOMOUS'];
const AUTONOMY_STYLE = {
  SUGGEST_ONLY:     { bg: '#243A72', color: '#C3D3F2' },
  ACT_WITH_APPROVAL:{ bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  AUTONOMOUS:       { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
};
const STATUS_STYLE = {
  UP:      { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97', dot: '#3DDC97' },
  DOWN:    { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81', dot: '#FF6B81' },
  UNKNOWN: { bg: '#243A72', color: '#C3D3F2', dot: '#7D92C4' },
};
const statusStyle = s => STATUS_STYLE[s] || STATUS_STYLE.UNKNOWN;

export default function AgentsTab({ tenantId }) {
  const [agents, setAgents]   = useState([]);
  const [servers, setServers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);
  const [saving, setSaving]   = useState({});

  const hdr = { headers: { 'X-Tenant-Id': tenantId } };

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true); setErr(null);
    try {
      const [allAgents, activeAgents, allServers, activeServers] = await Promise.all([
        axios.get('/api/platform/agents', hdr),
        axios.get('/api/platform/agents/active', hdr),
        axios.get('/api/platform/mcp-servers', hdr),
        axios.get('/api/platform/mcp-servers/active', hdr),
      ]);
      const activeById = new Map((activeAgents.data || []).map(a => [a.agentId, a]));
      setAgents((allAgents.data || []).map(a => {
        const live = activeById.get(a.agentId);
        return {
          ...a,
          enabled: !!live,
          degraded: live?.degraded || false,
          missingCapabilities: live?.missingCapabilities || [],
          autonomy: (live?.manifest?.autonomy) || a.manifest?.autonomy || 'SUGGEST_ONLY',
        };
      }));
      const activeServerIds = new Set((activeServers.data || []).map(s => s.serverId));
      setServers((allServers.data || []).map(s => ({ ...s, enabled: activeServerIds.has(s.serverId) })));
    } catch (e) {
      setErr(e?.response?.data?.error || 'Could not load agents / MCP servers from the registry.');
    }
    setLoading(false);
  }, [tenantId]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);

  const toggleAgent = async (agentId, enabled) => {
    setSaving(s => ({ ...s, [agentId]: true })); setErr(null);
    try {
      await axios.put(`/api/platform/agents/${agentId}/tenants/${tenantId}/enabled`, { enabled: !enabled }, hdr);
      await load();
    } catch (e) {
      setErr(e?.response?.data?.error || `Failed to update agent "${agentId}".`);
    }
    setSaving(s => ({ ...s, [agentId]: false }));
  };

  const changeAutonomy = async (agentId, autonomy) => {
    setSaving(s => ({ ...s, [`aut-${agentId}`]: true })); setErr(null);
    try {
      await axios.put(`/api/platform/agents/${agentId}/tenants/${tenantId}/autonomy`, { autonomy }, hdr);
      setAgents(as => as.map(a => a.agentId === agentId ? { ...a, autonomy } : a));
    } catch (e) {
      setErr(e?.response?.data?.error || `Failed to set autonomy for "${agentId}".`);
    }
    setSaving(s => ({ ...s, [`aut-${agentId}`]: false }));
  };

  const toggleServer = async (serverId, enabled) => {
    setSaving(s => ({ ...s, [serverId]: true })); setErr(null);
    try {
      await axios.put(`/api/platform/mcp-servers/${serverId}/tenants/${tenantId}/enabled`, { enabled: !enabled }, hdr);
      setServers(ss => ss.map(s => s.serverId === serverId ? { ...s, enabled: !enabled } : s));
    } catch (e) {
      setErr(e?.response?.data?.error || `Failed to update server "${serverId}".`);
    }
    setSaving(s => ({ ...s, [serverId]: false }));
  };

  if (!tenantId) {
    return <Alert severity="info" sx={{ fontSize: 13 }}>Select a tenant to manage its agents and tool servers.</Alert>;
  }
  if (loading) {
    return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2, gap: 2, flexWrap: 'wrap' }}>
        <Typography fontSize={13} color="#C3D3F2">
          Enable agents and MCP tool servers for this tenant, and set each agent's autonomy. An agent
          whose required tools are not served is flagged DEGRADED and is not fired.
        </Typography>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} sx={{ textTransform: 'none', fontSize: 12 }}>
          Refresh
        </Button>
      </Box>

      {err && <Alert severity="error" sx={{ mb: 2, fontSize: 13 }} onClose={() => setErr(null)}>{err}</Alert>}

      {/* ── Agents ── */}
      <Box sx={{ mb: 3 }}>
      <CollapsibleSection title='Agents' count={agents.length}>
      {agents.length === 0 ? (
        <Paper variant="outlined" sx={{ p: 3, textAlign: 'center', borderRadius: 2, color: '#7D92C4', fontSize: 13, mb: 3 }}>
          No agents registered yet.
        </Paper>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, mb: 3 }}>
          {agents.map(a => {
            const ss = statusStyle(a.status);
            return (
              <Paper key={a.agentId} elevation={0} sx={{
                p: 2, border: '1px solid', borderColor: a.enabled ? 'rgba(91, 157, 255, 0.42)' : '#243A72',
                borderRadius: 2, bgcolor: a.enabled ? 'rgba(91, 157, 255, 0.14)' : '#152652',
              }}>
                <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2 }}>
                  <Box sx={{ flex: 1 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                      <SmartToyIcon sx={{ fontSize: 16, color: a.enabled ? '#8DBCFF' : '#7D92C4' }} />
                      <Typography fontWeight={700} fontSize={14} color="#FFFFFF">{a.displayName || a.agentId}</Typography>
                      <Box sx={{ fontSize: 11, color: '#7D92C4', fontFamily: 'monospace' }}>{a.agentId}</Box>
                      <Chip size="small"
                        icon={<CircleIcon sx={{ fontSize: 9, color: `${ss.dot} !important` }} />}
                        label={a.status || 'UNKNOWN'}
                        sx={{ height: 20, fontSize: 10, bgcolor: ss.bg, color: ss.color, fontWeight: 700 }} />
                      {a.degraded && (
                        <Tooltip title={`Missing: ${(a.missingCapabilities || []).join(', ') || 'unknown'}`}>
                          <Chip size="small" icon={<WarningAmberIcon sx={{ fontSize: 12 }} />} label="DEGRADED"
                            sx={{ height: 20, fontSize: 10, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D', fontWeight: 700 }} />
                        </Tooltip>
                      )}
                    </Box>
                    <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mb: 0.5 }}>
                      {(a.manifest?.requiredCapabilities || []).map(c => (
                        <Chip key={c} size="small" label={c}
                          sx={{ height: 18, fontSize: 10, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
                      ))}
                      {(a.manifest?.optionalCapabilities || []).map(c => (
                        <Chip key={c} size="small" label={`${c} (opt)`}
                          sx={{ height: 18, fontSize: 10, bgcolor: '#0F2450', color: '#7D92C4' }} />
                      ))}
                    </Box>
                  </Box>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, pt: 0.25 }}>
                    <Tooltip title="Per-tenant autonomy policy">
                      <span>
                        <Select size="small" value={a.autonomy}
                          disabled={!!saving[`aut-${a.agentId}`]}
                          onChange={e => changeAutonomy(a.agentId, e.target.value)}
                          sx={{ fontSize: 11, height: 28, minWidth: 160,
                            '& .MuiSelect-select': { py: 0.5 },
                            bgcolor: (AUTONOMY_STYLE[a.autonomy] || {}).bg }}>
                          {AUTONOMY.map(x => (
                            <MenuItem key={x} value={x} sx={{ fontSize: 11 }}>{x}</MenuItem>
                          ))}
                        </Select>
                      </span>
                    </Tooltip>
                    {saving[a.agentId] && <CircularProgress size={16} />}
                    <Tooltip title={a.enabled ? 'Disable for this tenant' : 'Enable for this tenant'}>
                      <span>
                        <Switch checked={a.enabled} disabled={!!saving[a.agentId]} size="small"
                          onChange={() => toggleAgent(a.agentId, a.enabled)} />
                      </span>
                    </Tooltip>
                  </Box>
                </Box>
              </Paper>
            );
          })}
        </Box>
      )}
      </CollapsibleSection>
      </Box>

      {/* ── MCP tool servers ── */}
      <CollapsibleSection title='MCP Servers' count={servers.length}>
      {servers.length === 0 ? (
        <Paper variant="outlined" sx={{ p: 3, textAlign: 'center', borderRadius: 2, color: '#7D92C4', fontSize: 13 }}>
          No MCP tool servers registered yet.
        </Paper>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          {servers.map(s => {
            const ss = statusStyle(s.status);
            return (
              <Paper key={s.serverId} elevation={0} sx={{
                p: 2, border: '1px solid', borderColor: s.enabled ? 'rgba(91, 157, 255, 0.42)' : '#243A72',
                borderRadius: 2, bgcolor: s.enabled ? 'rgba(91, 157, 255, 0.14)' : '#152652',
              }}>
                <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2 }}>
                  <Box sx={{ flex: 1 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                      <BuildIcon sx={{ fontSize: 15, color: s.enabled ? '#8DBCFF' : '#7D92C4' }} />
                      <Typography fontWeight={700} fontSize={14} color="#FFFFFF" fontFamily="monospace">{s.serverId}</Typography>
                      <Chip size="small"
                        icon={<CircleIcon sx={{ fontSize: 9, color: `${ss.dot} !important` }} />}
                        label={s.status || 'UNKNOWN'}
                        sx={{ height: 20, fontSize: 10, bgcolor: ss.bg, color: ss.color, fontWeight: 700 }} />
                    </Box>
                    <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                      {(s.tools || []).map(t => (
                        <Chip key={t.name} size="small"
                          label={t.write ? `${t.name} ✎` : t.name}
                          sx={{ height: 18, fontSize: 10,
                            bgcolor: t.write ? 'rgba(255, 0, 0, 0.14)' : '#0F2450',
                            color: t.write ? '#FF6B81' : '#C3D3F2' }} />
                      ))}
                    </Box>
                  </Box>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, pt: 0.25 }}>
                    {saving[s.serverId] && <CircularProgress size={16} />}
                    <Tooltip title={s.enabled ? 'Disable for this tenant' : 'Enable for this tenant'}>
                      <span>
                        <Switch checked={s.enabled} disabled={!!saving[s.serverId]} size="small"
                          onChange={() => toggleServer(s.serverId, s.enabled)} />
                      </span>
                    </Tooltip>
                  </Box>
                </Box>
              </Paper>
            );
          })}
        </Box>
      )}
      </CollapsibleSection>
    </Box>
  );
}
