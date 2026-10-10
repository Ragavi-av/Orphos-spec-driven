import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Box, Typography, Paper, Chip, Button, Alert, CircularProgress, Tooltip, Collapse,
  Switch, Select, MenuItem, FormControl,
} from '@mui/material';
import RefreshIcon      from '@mui/icons-material/Refresh';
import CircleIcon       from '@mui/icons-material/Circle';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ExpandMoreIcon   from '@mui/icons-material/ExpandMore';
import UnfoldMoreIcon   from '@mui/icons-material/UnfoldMore';
import UnfoldLessIcon   from '@mui/icons-material/UnfoldLess';
import axios from 'axios';

/**
 * Platform Tiers — an expand/collapse tree of the whole four-tier mesh
 * (design §16: substrate → control plane → shared bases → plugins).
 *
 * Composed live from two endpoints:
 *   • GET /api/admin/services      — every container + Docker state (platform-core),
 *     mapped by its functional category into the Substrate and Control-Plane tiers.
 *   • GET /api/platform/capabilities — the registry view (platform-registry):
 *     Tier-2 shared bases, and the Tier-3 hot-pluggable agents / MCP servers / plugins.
 *
 * Platform-admin, cross-tenant. Reachability probing lives on the Services tab;
 * this tree uses Docker state (probe=false) for a fast structural overview.
 */

// Functional categories (from AdminServicesController) → which tier they belong to.
const SUBSTRATE_CATS = ['Data Stores', 'AI / ML', 'Auth & Secrets', 'Observability'];
const CONTROL_CATS   = ['Platform & Gateway', 'Application Services', 'Intelligence & Processing'];

// ── status → tone ───────────────────────────────────────────────────────────
const TONE = {
  up:   { dot: '#3DDC97', text: '#3DDC97' },
  warn: { dot: '#FFB84D', text: '#FFB84D' },
  down: { dot: '#FF6B81', text: '#FF6B81' },
  idle: { dot: '#7D92C4', text: '#7D92C4' },
};

function dockerTone(realStatus) {
  if (['healthy', 'running', 'starting'].includes(realStatus)) return 'up';
  if (['unhealthy', 'unreachable', 'restarting', 'paused'].includes(realStatus)) return 'warn';
  if (['stopped', 'created'].includes(realStatus)) return 'down';
  return 'idle';
}
function registryTone(status) {
  const s = (status || '').toUpperCase();
  if (s === 'UP') return 'up';
  if (s === 'DOWN') return 'down';
  return 'idle';
}

function friendlyName(name) {
  return name.replace(/^eip-/, '').replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

// ── tree building ───────────────────────────────────────────────────────────
// A node is either a group {id,label,desc?,children:[]} or a leaf
// {id,label,mono?,statusLabel?,tone,chips?}.

function leafFromDocker(svc) {
  return {
    id: `svc:${svc.name}`,
    label: friendlyName(svc.name),
    mono: svc.name,
    statusLabel: svc.realStatus,
    tone: dockerTone(svc.realStatus),
  };
}

function dockerTierChildren(services, categories) {
  return categories
    .map(cat => {
      const items = services.filter(s => s.category === cat).map(leafFromDocker);
      return items.length ? { id: `cat:${cat}`, label: cat, children: items } : null;
    })
    .filter(Boolean);
}

// leaves that carry a real status (everything but 'idle') count toward the roll-up.
function rollup(node) {
  if (!node.children) {
    const statused = node.tone && node.tone !== 'idle';
    return { up: node.tone === 'up' ? 1 : 0, total: statused ? 1 : 0, leaves: 1 };
  }
  return node.children.reduce((acc, c) => {
    const r = rollup(c);
    return { up: acc.up + r.up, total: acc.total + r.total, leaves: acc.leaves + r.leaves };
  }, { up: 0, total: 0, leaves: 0 });
}

function allGroupIds(node, out = []) {
  if (node.children) {
    out.push(node.id);
    node.children.forEach(c => allGroupIds(c, out));
  }
  return out;
}

export default function PlatformBasesTab() {
  const [services, setServices] = useState([]);
  const [caps, setCaps]         = useState(null);
  const [tenants, setTenants]   = useState([]);
  const [selectedTenant, setSelectedTenant] = useState('');
  const [saving, setSaving]     = useState(null); // `${capType}:${capId}:${tenantId}` in-flight
  const [loading, setLoading]   = useState(true);
  const [error, setError]       = useState(null);
  const [expanded, setExpanded] = useState(() => new Set());
  const initedRef = useRef(false);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    const [svc, cap, ten] = await Promise.allSettled([
      axios.get('/api/admin/services?probe=false'),
      axios.get('/api/platform/capabilities'),
      axios.get('/api/admin/tenants'),
    ]);
    if (svc.status === 'fulfilled') setServices(Array.isArray(svc.value.data) ? svc.value.data : []);
    if (cap.status === 'fulfilled') setCaps(cap.value.data || {});
    if (ten.status === 'fulfilled') {
      setTenants((ten.value.data || []).filter(t => (t.status || '').toUpperCase() !== 'DELETED'));
    }
    if (svc.status === 'rejected' && cap.status === 'rejected') {
      setError('Could not load the platform tier map (services + registry both unavailable).');
    } else if (svc.status === 'rejected') {
      setError('Substrate/Control-Plane view unavailable (could not reach the Docker service list).');
    } else if (cap.status === 'rejected') {
      setError('Bases/Plugins view unavailable (could not reach the platform registry).');
    }
    setLoading(false);
  }, []);

  // Per-tenant enable/disable for a Tier-3 agent or MCP server. Optimistically
  // updates the capability's enabledTenants so the switch flips immediately.
  const toggleTier3 = useCallback(async (capType, capId, tenantId, currentlyEnabled) => {
    if (!tenantId) return;
    const path = capType === 'agent' ? 'agents' : 'mcp-servers';
    setSaving(`${capType}:${capId}:${tenantId}`);
    try {
      await axios.put(`/api/platform/${path}/${capId}/tenants/${tenantId}/enabled`, { enabled: !currentlyEnabled });
      setCaps(prev => {
        const listKey = capType === 'agent' ? 'agents' : 'mcpServers';
        const idField = capType === 'agent' ? 'agentId' : 'serverId';
        return {
          ...prev,
          [listKey]: (prev[listKey] || []).map(row => {
            if (row.info[idField] !== capId) return row;
            const set = new Set(row.enabledTenants || []);
            if (currentlyEnabled) set.delete(tenantId); else set.add(tenantId);
            return { ...row, enabledTenants: [...set] };
          }),
        };
      });
    } catch (e) {
      setError(e?.response?.data?.error || e?.response?.data?.message || e.message || 'Failed to update the assignment.');
    } finally {
      setSaving(null);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Build the four-tier tree from whatever loaded.
  const tree = useMemo(() => {
    const c = caps || {};
    const bases   = (c.services || []);
    const agents  = (c.agents || []);
    const mcp     = (c.mcpServers || []);
    const plugins = (c.plugins || []);

    const nodes = [];

    nodes.push({
      id: 'tier:substrate',
      label: 'Tier 0 · Substrate',
      desc: 'Foundation the mesh runs on — data stores, AI runtimes, auth, observability.',
      children: dockerTierChildren(services, SUBSTRATE_CATS),
    });

    nodes.push({
      id: 'tier:control',
      label: 'Tier 1 · Control Plane',
      desc: 'Core platform and domain services that make up the always-on control plane.',
      children: dockerTierChildren(services, CONTROL_CATS),
    });

    nodes.push({
      id: 'tier:bases',
      label: 'Tier 2 · Shared Bases',
      desc: 'Shared capabilities agents and plugins depend on (e.g. the AI gateway). 60-second heartbeat.',
      children: bases.map(b => ({
        id: `base:${b.serviceId}`,
        label: b.displayName || b.serviceId,
        mono: b.serviceId,
        statusLabel: (b.status || 'UNKNOWN'),
        tone: registryTone(b.status),
        chips: b.capabilities || [],
      })),
    });

    nodes.push({
      id: 'tier:plugins',
      label: 'Tier 3 · Plugins & Agents',
      desc: 'Hot-pluggable and independently deployable — zero or more may be present (invariant I5).',
      children: [
        {
          id: 'grp:agents', label: 'Agents',
          children: agents.map(a => ({
            id: `agent:${a.info.agentId}`,
            label: a.info.displayName || a.info.agentId,
            mono: a.info.agentId,
            statusLabel: a.info.status || 'UNKNOWN',
            tone: registryTone(a.info.status),
            chips: [`${(a.enabledTenants || []).length} tenant(s)`],
            capType: 'agent',
            capId: a.info.agentId,
            enabledTenants: a.enabledTenants || [],
          })),
        },
        {
          id: 'grp:mcp', label: 'MCP Servers',
          children: mcp.map(s => ({
            id: `mcp:${s.info.serverId}`,
            label: s.info.serverId,
            mono: s.info.mcpEndpoint || s.info.serverId,
            statusLabel: s.info.status || 'UNKNOWN',
            tone: registryTone(s.info.status),
            chips: [`${(s.info.tools || []).length} tool(s)`, `${(s.enabledTenants || []).length} tenant(s)`],
            capType: 'mcp',
            capId: s.info.serverId,
            enabledTenants: s.enabledTenants || [],
          })),
        },
        {
          id: 'grp:plugins', label: 'Plugins (frontend remotes)',
          children: plugins.map(p => ({
            id: `plugin:${p.pluginId}`,
            label: p.displayName || p.pluginId,
            mono: p.pluginId,
            statusLabel: p.builtIn ? 'built-in' : 'add-on',
            tone: 'idle',
          })),
        },
      ].filter(g => g.children.length),
    });

    return nodes;
  }, [services, caps]);

  // Default-expand the four tier nodes once, on first successful load. Guarded by
  // a ref so an explicit "Collapse all" (which empties the set) is not undone.
  useEffect(() => {
    if (!loading && !initedRef.current && tree.length) {
      initedRef.current = true;
      setExpanded(new Set(tree.map(n => n.id)));
    }
  }, [loading, tree]);

  const toggle = (id) => setExpanded(prev => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  const expandAll   = () => setExpanded(new Set(tree.flatMap(n => allGroupIds(n))));
  const collapseAll = () => setExpanded(new Set());

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2.5, gap: 2, flexWrap: 'wrap' }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Platform Tiers</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            The four-tier mesh — substrate → control plane → shared bases → plugins. Status is Docker
            state for the lower tiers and the registry heartbeat for bases, agents and MCP servers.
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Button size='small' startIcon={<UnfoldMoreIcon />} onClick={expandAll}
            sx={{ textTransform: 'none', fontSize: 12 }}>Expand all</Button>
          <Button size='small' startIcon={<UnfoldLessIcon />} onClick={collapseAll}
            sx={{ textTransform: 'none', fontSize: 12 }}>Collapse all</Button>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>Refresh</Button>
        </Box>
      </Box>

      {/* Tier-3 access control: pick a tenant to enable/disable its agents & MCP servers inline. */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2, flexWrap: 'wrap',
        bgcolor: '#0F2450', border: '1px solid #243A72', borderRadius: 2, px: 1.5, py: 1 }}>
        <Typography fontSize={12} fontWeight={600} color='#C3D3F2'>Tier-3 access for tenant:</Typography>
        <FormControl size='small' sx={{ minWidth: 220 }}>
          <Select
            displayEmpty
            value={selectedTenant}
            onChange={(e) => setSelectedTenant(e.target.value)}
            sx={{ fontSize: 13, bgcolor: '#152652' }}>
            <MenuItem value=''><em>None (view only)</em></MenuItem>
            {tenants.map(t => (
              <MenuItem key={t.id} value={t.id} sx={{ fontSize: 13 }}>
                {t.displayName || t.domain || t.id}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
        <Typography fontSize={11.5} color='#7D92C4'>
          {selectedTenant
            ? 'Toggle each agent / MCP server on or off for this tenant. Plugins are managed in the Plugins tab.'
            : 'Select a tenant to reveal per-tenant enable switches on Tier-3 agents & MCP servers.'}
        </Typography>
      </Box>

      {error && (
        <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>{error}</Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <Paper variant='outlined' sx={{ borderRadius: 2, p: 1 }}>
          {tree.map(node => (
            <TreeNode key={node.id} node={node} depth={0} expanded={expanded} onToggle={toggle}
              selectedTenant={selectedTenant} saving={saving} onTier3Toggle={toggleTier3} />
          ))}
        </Paper>
      )}
    </Box>
  );
}

function StatusChip({ label, tone }) {
  const t = TONE[tone] || TONE.idle;
  return (
    <Chip size='small'
      icon={<CircleIcon sx={{ fontSize: 9, color: `${t.dot} !important` }} />}
      label={label}
      sx={{ height: 20, fontSize: 11, fontWeight: 600, color: t.text, bgcolor: 'transparent',
        border: `1px solid ${t.dot}6b` }} />
  );
}

function RollupChip({ node }) {
  const { up, total, leaves } = rollup(node);
  if (total === 0) {
    return <Chip size='small' label={`${leaves}`} sx={{ height: 18, fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />;
  }
  const allUp = up === total;
  return (
    <Chip size='small' label={`${up}/${total} up`}
      sx={{ height: 18, fontSize: 10, fontWeight: 700,
        bgcolor: allUp ? 'rgba(61, 220, 151, 0.14)' : 'rgba(255, 0, 0, 0.14)', color: allUp ? '#3DDC97' : '#FF6B81' }} />
  );
}

function TreeNode({ node, depth, expanded, onToggle, selectedTenant, saving, onTier3Toggle }) {
  const isGroup = !!node.children;
  const isOpen  = expanded.has(node.id);
  const pad     = 0.5 + depth * 2;

  if (!isGroup) {
    const canToggle = !!node.capType && !!selectedTenant;
    const on   = canToggle && (node.enabledTenants || []).includes(selectedTenant);
    const busy = saving === `${node.capType}:${node.capId}:${selectedTenant}`;
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, pl: pad + 2.5, pr: 1, py: 0.6,
        borderRadius: 1, '&:hover': { bgcolor: '#0F2450' } }}>
        <CircleIcon sx={{ fontSize: 8, color: (TONE[node.tone] || TONE.idle).dot }} />
        <Typography fontSize={13} color='#FFFFFF' sx={{ fontWeight: 500 }}>{node.label}</Typography>
        {node.mono && (
          <Typography fontSize={11} color='#7D92C4' sx={{ fontFamily: 'monospace' }} noWrap>{node.mono}</Typography>
        )}
        <Box sx={{ flex: 1 }} />
        {(node.chips || []).map(c => (
          <Chip key={c} size='small' label={c} sx={{ height: 18, fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />
        ))}
        {node.statusLabel && <StatusChip label={node.statusLabel} tone={node.tone} />}
        {canToggle && (
          busy
            ? <CircularProgress size={15} sx={{ mx: 0.75 }} />
            : <Tooltip title={on ? 'Enabled for this tenant — click to disable' : 'Disabled for this tenant — click to enable'}>
                <Switch size='small' checked={on}
                  onChange={() => onTier3Toggle(node.capType, node.capId, selectedTenant, on)} />
              </Tooltip>
        )}
      </Box>
    );
  }

  const empty = node.children.length === 0;
  return (
    <Box>
      <Box
        onClick={() => !empty && onToggle(node.id)}
        sx={{ display: 'flex', alignItems: 'center', gap: 0.5, pl: pad, pr: 1, py: 0.75,
          borderRadius: 1, cursor: empty ? 'default' : 'pointer',
          '&:hover': { bgcolor: empty ? 'transparent' : '#0F2450' } }}>
        {empty
          ? <Box sx={{ width: 22 }} />
          : (isOpen ? <ExpandMoreIcon sx={{ fontSize: 20, color: '#C3D3F2' }} />
                    : <ChevronRightIcon sx={{ fontSize: 20, color: '#C3D3F2' }} />)}
        <Typography sx={{ fontWeight: depth === 0 ? 700 : 600, fontSize: depth === 0 ? 13.5 : 12.5,
          color: depth === 0 ? '#FFFFFF' : '#FFFFFF' }}>
          {node.label}
        </Typography>
        <RollupChip node={node} />
        {node.desc && depth === 0 && (
          <Typography fontSize={11} color='#7D92C4' sx={{ ml: 1, display: { xs: 'none', md: 'block' } }} noWrap>
            {node.desc}
          </Typography>
        )}
      </Box>
      {!empty && (
        <Collapse in={isOpen} unmountOnExit>
          <Box>
            {node.children.map(child => (
              <TreeNode key={child.id} node={child} depth={depth + 1} expanded={expanded} onToggle={onToggle}
                selectedTenant={selectedTenant} saving={saving} onTier3Toggle={onTier3Toggle} />
            ))}
          </Box>
        </Collapse>
      )}
    </Box>
  );
}
