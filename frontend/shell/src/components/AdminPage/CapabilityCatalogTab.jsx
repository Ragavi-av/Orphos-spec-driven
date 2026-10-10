// ── Capability Catalog (platform admin) ─────────────────────────────────────
// One place for a platform admin to see EVERY registered capability across the
// mesh — agents, MCP servers, product plugins, platform services — and to
// control, per tenant, which agents and MCP servers each client may use.
//
//   • Assignment matrix — rows = agents + MCP servers (the opt-in capability
//     types), columns = tenants. A checked cell means that tenant has been
//     granted the capability. Toggling reuses the existing per-tenant enable
//     endpoints; no new backend gate is introduced.
//   • Catalog — a read-only listing of all four capability types with live
//     status and what each one provides. Plugins are default-on (managed in the
//     Plugins tab) and platform services are shared infrastructure, so both are
//     informational here.
//
// Data: GET /api/platform/capabilities (registry, platform_admin) joined with
// GET /api/admin/tenants (platform-core) for the matrix columns.
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Box, Chip, CircularProgress, Divider, Paper, Stack, Switch, Tab, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, Tooltip, Typography,
} from '@mui/material';
import SmartToyIcon    from '@mui/icons-material/SmartToy';
import HubIcon         from '@mui/icons-material/Hub';
import ExtensionIcon   from '@mui/icons-material/Extension';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';

const STATUS_COLOR = { UP: 'success', DOWN: 'error', DEGRADED: 'warning' };

function statusChip(status) {
  const s = (status || 'UNKNOWN').toUpperCase();
  return <Chip size="small" label={s} color={STATUS_COLOR[s] || 'default'} variant="outlined" />;
}

export default function CapabilityCatalogTab() {
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState(null);
  const [saving, setSaving]   = useState(null); // `${type}:${id}:${tenantId}` in-flight
  const [view, setView]       = useState('assignments');

  const [catalog, setCatalog] = useState({ agents: [], mcpServers: [], plugins: [], services: [] });
  const [tenants, setTenants] = useState([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [cap, ten] = await Promise.all([
        axios.get('/api/platform/capabilities'),
        axios.get('/api/admin/tenants'),
      ]);
      const c = cap.data || {};
      setCatalog({
        agents:     c.agents     || [],
        mcpServers: c.mcpServers || [],
        plugins:    c.plugins    || [],
        services:   c.services   || [],
      });
      setTenants((ten.data || []).filter(t => (t.status || '').toUpperCase() !== 'DELETED'));
    } catch (e) {
      setError(e?.response?.data?.message || e.message || 'Failed to load the capability catalog.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Optimistic per-cell toggle. `type` is 'agent' | 'mcp'; `path` is the REST
  // collection segment; `key` is the state array holding the rows.
  const toggle = useCallback(async (type, path, listKey, id, tenantId, currentlyEnabled) => {
    const busy = `${type}:${id}:${tenantId}`;
    setSaving(busy);
    try {
      await axios.put(`/api/platform/${path}/${id}/tenants/${tenantId}/enabled`, { enabled: !currentlyEnabled });
      setCatalog(prev => ({
        ...prev,
        [listKey]: prev[listKey].map(row => {
          const rid = type === 'agent' ? row.info.agentId : row.info.serverId;
          if (rid !== id) return row;
          const set = new Set(row.enabledTenants || []);
          if (currentlyEnabled) set.delete(tenantId); else set.add(tenantId);
          return { ...row, enabledTenants: [...set] };
        }),
      }));
    } catch (e) {
      setError(e?.response?.data?.message || e.message || 'Failed to update the assignment.');
    } finally {
      setSaving(null);
    }
  }, []);

  const hasTenants  = tenants.length > 0;
  const matrixRows  = useMemo(() => ([
    ...catalog.agents.map(r     => ({ type: 'agent', path: 'agents',      listKey: 'agents',     id: r.info.agentId,  label: r.info.displayName || r.info.agentId, status: r.info.status, enabledTenants: r.enabledTenants || [] })),
    ...catalog.mcpServers.map(r => ({ type: 'mcp',   path: 'mcp-servers', listKey: 'mcpServers', id: r.info.serverId, label: r.info.serverId,                      status: r.info.status, enabledTenants: r.enabledTenants || [] })),
  ]), [catalog]);

  if (loading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress /></Box>;
  }

  return (
    <Box>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 0.5 }}>
        <HubIcon color="primary" />
        <Typography variant="h6" sx={{ fontWeight: 700 }}>Capability Catalog</Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Every capability registered on the platform, and which tenant each agent and MCP server is available to.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}

      <Tabs value={view} onChange={(_, v) => setView(v)} sx={{ mb: 2, minHeight: 40 }}>
        <Tab value="assignments" label="Assignments" sx={{ textTransform: 'none', fontWeight: 600, minHeight: 40 }} />
        <Tab value="catalog"     label="Catalog"     sx={{ textTransform: 'none', fontWeight: 600, minHeight: 40 }} />
      </Tabs>

      {view === 'assignments' && (
        <AssignmentMatrix
          rows={matrixRows}
          tenants={tenants}
          hasTenants={hasTenants}
          saving={saving}
          onToggle={toggle}
        />
      )}

      {view === 'catalog' && (
        <Stack spacing={3}>
          <CatalogSection
            icon={<SmartToyIcon fontSize="small" />}
            title="Agents"
            empty="No agents registered. Agents self-register on startup via the platform registry."
            rows={catalog.agents.map(r => ({
              id: r.info.agentId,
              primary: r.info.displayName || r.info.agentId,
              secondary: r.info.manifest?.description || r.info.baseUrl || '—',
              version: r.info.version,
              status: r.info.status,
              tenantCount: (r.enabledTenants || []).length,
            }))}
            gated
          />
          <CatalogSection
            icon={<HubIcon fontSize="small" />}
            title="MCP Servers"
            empty="No MCP servers registered."
            rows={catalog.mcpServers.map(r => ({
              id: r.info.serverId,
              primary: r.info.serverId,
              secondary: `${(r.info.tools || []).length} tool(s)` + (r.info.mcpEndpoint ? ` · ${r.info.mcpEndpoint}` : ''),
              status: r.info.status,
              tenantCount: (r.enabledTenants || []).length,
            }))}
            gated
          />
          <CatalogSection
            icon={<ExtensionIcon fontSize="small" />}
            title="Plugins"
            empty="No plugins registered."
            note="Plugins are enabled for all tenants by default — manage exceptions in the Plugins tab."
            rows={catalog.plugins.map(p => ({
              id: p.pluginId,
              primary: p.displayName || p.pluginId,
              secondary: (p.builtIn ? 'Built-in' : 'Add-on') + (p.remoteScope ? ` · ${p.remoteScope}` : ''),
              version: p.version,
              status: null,
            }))}
          />
          <CatalogSection
            icon={<AccountTreeIcon fontSize="small" />}
            title="Platform Services"
            empty="No platform services registered."
            note="Shared infrastructure — available to the whole mesh, not gated per tenant."
            rows={catalog.services.map(s => ({
              id: s.serviceId,
              primary: s.displayName || s.serviceId,
              secondary: (s.tier ? `Tier: ${s.tier}` : '') + ((s.capabilities || []).length ? ` · ${s.capabilities.join(', ')}` : ''),
              version: s.version,
              status: s.status,
            }))}
          />
        </Stack>
      )}
    </Box>
  );
}

function AssignmentMatrix({ rows, tenants, hasTenants, saving, onToggle }) {
  if (!hasTenants) {
    return <Alert severity="info">No tenants to assign capabilities to yet. Create a tenant first.</Alert>;
  }
  if (rows.length === 0) {
    return <Alert severity="info">No agents or MCP servers are registered yet. They appear here once they self-register on startup.</Alert>;
  }
  return (
    <CollapsibleSection title="Assignments" count={rows.length} detailsSx={{ p: 0 }}>
    <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 560 }}>
      <Table size="small" stickyHeader sx={{ '& td, & th': { whiteSpace: 'nowrap' } }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 700, position: 'sticky', left: 0, zIndex: 3, bgcolor: 'background.paper' }}>
              Capability
            </TableCell>
            <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
            {tenants.map(t => (
              <TableCell key={t.id} align="center" sx={{ fontWeight: 700 }}>
                <Tooltip title={t.domain || t.id}><span>{t.displayName || t.domain || t.id}</span></Tooltip>
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map(row => {
            const enabled = new Set(row.enabledTenants);
            return (
              <TableRow key={`${row.type}:${row.id}`} hover>
                <TableCell sx={{ position: 'sticky', left: 0, zIndex: 1, bgcolor: 'background.paper' }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    {row.type === 'agent' ? <SmartToyIcon fontSize="inherit" /> : <HubIcon fontSize="inherit" />}
                    <Box>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{row.label}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {row.type === 'agent' ? 'Agent' : 'MCP server'} · {row.id}
                      </Typography>
                    </Box>
                  </Stack>
                </TableCell>
                <TableCell>{statusChip(row.status)}</TableCell>
                {tenants.map(t => {
                  const busy = saving === `${row.type}:${row.id}:${t.id}`;
                  const on = enabled.has(t.id);
                  return (
                    <TableCell key={t.id} align="center">
                      {busy
                        ? <CircularProgress size={16} />
                        : <Switch size="small" checked={on}
                            onChange={() => onToggle(row.type, row.path, row.listKey, row.id, t.id, on)} />}
                    </TableCell>
                  );
                })}
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </TableContainer>
    </CollapsibleSection>
  );
}

function CatalogSection({ icon, title, rows, empty, note, gated }) {
  return (
    <CollapsibleSection title={title} icon={icon} count={rows.length}>
      {note && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>{note}</Typography>}
      {rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>{empty}</Typography>
      ) : (
        <Paper variant="outlined">
          {rows.map((r, i) => (
            <React.Fragment key={r.id}>
              {i > 0 && <Divider />}
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, px: 2, py: 1.25 }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }} noWrap>
                    {r.primary}{r.version ? <Typography component="span" variant="caption" color="text.secondary"> · v{r.version}</Typography> : null}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block' }}>{r.secondary}</Typography>
                </Box>
                {gated && (
                  <Chip size="small" variant="outlined"
                    label={`${r.tenantCount || 0} tenant${r.tenantCount === 1 ? '' : 's'}`} />
                )}
                {r.status != null && statusChip(r.status)}
              </Box>
            </React.Fragment>
          ))}
        </Paper>
      )}
    </CollapsibleSection>
  );
}
