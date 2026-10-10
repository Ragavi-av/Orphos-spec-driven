import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Switch, Chip, CircularProgress,
  Alert, Tooltip, Divider,
} from '@mui/material';
import ExtensionIcon    from '@mui/icons-material/Extension';
import LockIcon         from '@mui/icons-material/Lock';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';

const PLUGIN_DESCRIPTIONS = {
  'ai-copilot':    'AI Co-pilot side panel, briefing viewer, and query history tab.',
  'email-scanner': 'Tasks & Approvals and Notifications inferred from monitored mailboxes.',
  'doc-ingest':    'Document ingestion pipeline and semantic search over uploaded files.',
  'rag-search':    'RAG widget board, NL-Query chat, and SPARQL explorer.',
  'decision-log':  'Executive decision log with status tracking.',
  'widgets':       'Dashboard KPI widget board — tenant-default and per-user layout.',
};

export default function PluginsTab({ tenantId }) {
  const [plugins, setPlugins] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);
  const [saving, setSaving]   = useState({});  // pluginId → bool

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/platform/plugins', {
        headers: { 'X-Tenant-Id': tenantId },
      });
      // Fetch per-tenant active list to overlay enabled state
      const active = await axios.get('/api/platform/plugins/active', {
        headers: { 'X-Tenant-Id': tenantId },
      });
      const activeIds = new Set(active.data.map(p => p.pluginId));
      setPlugins(
        r.data.map(p => ({ ...p, enabled: activeIds.has(p.pluginId) }))
      );
    } catch (e) {
      setErr(e?.response?.data?.error || 'Failed to load plugins.');
    }
    setLoading(false);
  }, [tenantId]);

  useEffect(() => { load(); }, [load]);

  const toggle = async (pluginId, currentlyEnabled) => {
    setSaving(s => ({ ...s, [pluginId]: true }));
    setErr(null);
    try {
      await axios.put(
        `/api/platform/plugins/${pluginId}/tenants/${tenantId}/enabled`,
        { enabled: !currentlyEnabled },
        { headers: { 'X-Tenant-Id': tenantId } }
      );
      setPlugins(ps =>
        ps.map(p => p.pluginId === pluginId ? { ...p, enabled: !currentlyEnabled } : p)
      );
    } catch (e) {
      setErr(e?.response?.data?.error || `Failed to update plugin "${pluginId}".`);
    }
    setSaving(s => ({ ...s, [pluginId]: false }));
  };

  if (!tenantId) {
    return (
      <Alert severity="info" sx={{ fontSize: 13 }}>
        Select a tenant to manage its plugins.
      </Alert>
    );
  }

  if (loading) {
    return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;
  }

  return (
    <Box>
      <Typography fontSize={13} color="#C3D3F2" mb={2}>
        Enable or disable product plugins for this tenant. Changes take effect immediately —
        the shell refreshes its plugin list on next login or page reload.
      </Typography>

      {err && <Alert severity="error" sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      <CollapsibleSection title='Plugins' count={plugins.length}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        {plugins.map((plugin, idx) => (
          <Box key={plugin.pluginId}>
            {idx > 0 && <Divider sx={{ mb: 1.5 }} />}
            <Paper
              elevation={0}
              sx={{
                p: 2,
                border: '1px solid',
                borderColor: plugin.enabled ? 'rgba(91, 157, 255, 0.42)' : '#243A72',
                borderRadius: 2,
                bgcolor: plugin.enabled ? 'rgba(91, 157, 255, 0.14)' : '#152652',
                transition: 'all 0.15s',
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2 }}>
                <Box sx={{ flex: 1 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                    <ExtensionIcon sx={{ fontSize: 16, color: plugin.enabled ? '#8DBCFF' : '#7D92C4' }} />
                    <Typography fontWeight={700} fontSize={14} color="#FFFFFF">
                      {plugin.displayName}
                    </Typography>
                    {plugin.builtIn && (
                      <Tooltip title="Core platform component — always enabled">
                        <Chip
                          icon={<LockIcon sx={{ fontSize: 12 }} />}
                          label="Built-in"
                          size="small"
                          sx={{ fontSize: 10, height: 20, bgcolor: '#0F2450', color: '#C3D3F2' }}
                        />
                      </Tooltip>
                    )}
                    <Chip
                      label={`v${plugin.version || '1.0.0'}`}
                      size="small"
                      sx={{ fontSize: 10, height: 20, bgcolor: '#0F2450', color: '#7D92C4' }}
                    />
                  </Box>
                  <Typography fontSize={12} color="#C3D3F2">
                    {PLUGIN_DESCRIPTIONS[plugin.pluginId] || plugin.displayName}
                  </Typography>
                  {plugin.tabs?.length > 0 && (
                    <Box sx={{ display: 'flex', gap: 0.5, mt: 1, flexWrap: 'wrap' }}>
                      {plugin.tabs.map(tab => (
                        <Chip
                          key={tab.key}
                          label={tab.key}
                          size="small"
                          sx={{ fontSize: 10, height: 18, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }}
                        />
                      ))}
                      {plugin.hasSidePanel && (
                        <Chip
                          label="side panel"
                          size="small"
                          sx={{ fontSize: 10, height: 18, bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97' }}
                        />
                      )}
                    </Box>
                  )}
                </Box>

                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, pt: 0.25 }}>
                  {saving[plugin.pluginId] && <CircularProgress size={16} />}
                  <Tooltip title={plugin.builtIn ? 'Core plugins cannot be disabled' : (plugin.enabled ? 'Disable for this tenant' : 'Enable for this tenant')}>
                    <span>
                      <Switch
                        checked={plugin.enabled}
                        disabled={plugin.builtIn || saving[plugin.pluginId]}
                        onChange={() => toggle(plugin.pluginId, plugin.enabled)}
                        size="small"
                        sx={{
                          '& .MuiSwitch-thumb': { width: 14, height: 14 },
                          '& .MuiSwitch-track': { borderRadius: 7 },
                        }}
                      />
                    </span>
                  </Tooltip>
                </Box>
              </Box>
            </Paper>
          </Box>
        ))}
      </Box>
      </CollapsibleSection>
    </Box>
  );
}
