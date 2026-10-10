import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Button, Switch, CircularProgress,
  Alert, Chip, Divider, Tooltip,
} from '@mui/material';
import RefreshIcon   from '@mui/icons-material/Refresh';
import axios from 'axios';
import { refreshVoiceStatus } from 'eip-shell-context/voice';

/**
 * Admin → Voice — the platform-admin control surface for the voice
 * ("VoiceEnabled") functionality.
 *
 * <p>The per-tenant toggle is the only switch. The Whisper speech model and the
 * voice service are shared by every tenant, and platform-core's
 * {@code VoiceModuleReconciler} runs them exactly while at least one active
 * tenant has voice on — at launch, after a restart, and within a few seconds of
 * a toggle here. When the last tenant is switched off they are stopped again, so
 * the ~3 GB model costs nothing while nobody can use it.</p>
 *
 * <p>This tab therefore only reports the module's state; it never starts or
 * stops containers itself. After a toggle it asks for an immediate reconcile
 * pass ({@code /api/admin/voice/module/reconcile}) instead of waiting for the
 * next scheduled one.</p>
 */

const VOICE_CONTAINERS = ['eip-whisper', 'eip-voice'];

// Whisper loads (and on first run downloads) its model before it answers, so a
// freshly started module is "starting" for a minute or two. Re-read on this
// cadence until it settles, so the tab never needs a manual Refresh.
const SETTLE_POLL_MS = 5000;

const TONES = {
  green: { bg: 'rgba(61,220,151,0.16)',  fg: '#3DDC97', bd: 'rgba(61,220,151,0.42)' },
  amber: { bg: 'rgba(255,193,7,0.14)',   fg: '#FFC107', bd: 'rgba(255,193,7,0.42)' },
  grey:  { bg: 'rgba(125,146,196,0.16)', fg: '#7D92C4', bd: 'rgba(125,146,196,0.42)' },
};

export default function VoiceTab() {
  const [services, setServices]   = useState(null);   // rows from /api/admin/services
  const [module, setModule]       = useState(null);   // /api/admin/voice/module
  const [tenants, setTenants]     = useState([]);
  const [enabledIds, setEnabledIds] = useState([]);   // tenant ids voice is on for
  const [loading, setLoading]     = useState(true);
  const [busy, setBusy]           = useState(null);   // tenant id in flight
  const [error, setError]         = useState(null);

  // ── Load ───────────────────────────────────────────────────────────────────
  const load = useCallback(async () => {
    setError(null);
    try {
      const [svcRes, moduleRes, tenantRes, gateRes] = await Promise.allSettled([
        axios.get('/api/admin/services'),
        axios.get('/api/admin/voice/module'),
        axios.get('/api/admin/tenants'),
        axios.get('/api/platform/features/voice/tenants'),
      ]);

      if (svcRes.status === 'fulfilled') {
        setServices((svcRes.value.data || []).filter(s => VOICE_CONTAINERS.includes(s.name)));
      } else {
        // An empty array (rather than null) means "looked, found nothing" — the
        // render below turns that into the "not prepared" guidance, which is the
        // accurate message when the launcher never created the containers.
        setServices([]);
      }
      if (moduleRes.status === 'fulfilled') {
        setModule(moduleRes.value.data || null);
      }
      if (tenantRes.status === 'fulfilled') {
        // Same filter the Capability Catalog applies — a deleted tenant must not
        // appear as something voice can be granted to.
        setTenants((tenantRes.value.data || [])
          .filter(t => (t.status || '').toUpperCase() !== 'DELETED'));
      }
      if (gateRes.status === 'fulfilled') {
        setEnabledIds(Array.isArray(gateRes.value.data) ? gateRes.value.data : []);
      }
    } catch (e) {
      setError('Could not load the voice configuration.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // ── Per-tenant gate ────────────────────────────────────────────────────────
  const toggleTenant = async (tenantId, next) => {
    setBusy(tenantId); setError(null);
    try {
      await axios.put(`/api/platform/features/voice/tenants/${tenantId}/enabled`, { enabled: next });
      setEnabledIds(prev => next ? [...new Set([...prev, tenantId])] : prev.filter(id => id !== tenantId));
      // Apply the toggle to the module now rather than on the next scheduled
      // pass. Best-effort: if this fails the scheduled pass still catches up.
      try {
        const res = await axios.post('/api/admin/voice/module/reconcile');
        setModule(res.data || null);
      } catch { /* scheduled reconcile will apply it */ }
      await load();
      refreshVoiceStatus();
    } catch (e) {
      setError(e.response?.data?.error || 'Could not change that tenant.');
    } finally {
      setBusy(null);
    }
  };

  const row      = n => services?.find(s => s.name === n);
  const status   = n => row(n)?.realStatus;
  const running  = n => ['healthy', 'running'].includes(status(n));
  const starting = n => ['starting', 'restarting'].includes(status(n))
    // Whisper has no port open while its model loads, so the probe reads
    // "unreachable" even though Docker has it running.
    || (status(n) === 'unreachable' && row(n)?.state === 'running');
  const prepared   = (services?.length || 0) > 0;
  const allRunning = VOICE_CONTAINERS.every(running);
  const wanted     = module ? module.desired === 'running' : enabledIds.length > 0;
  const settling   = prepared && (wanted ? !allRunning : VOICE_CONTAINERS.some(running));

  // Keep re-reading while the module is on its way up (or down).
  useEffect(() => {
    if (loading || !settling) return undefined;
    const t = setTimeout(load, SETTLE_POLL_MS);
    return () => clearTimeout(t);
  }, [loading, settling, services, load]);

  if (loading) {
    return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;
  }

  const chipFor = name => {
    if (starting(name)) return { label: 'starting', tone: 'amber' };
    return { label: status(name) || 'not created', tone: running(name) ? 'green' : 'grey' };
  };

  const autoManaged = module?.autoManaged !== false;
  const anyStarting = VOICE_CONTAINERS.some(starting);

  return (
    <Box sx={{ maxWidth: 900 }}>
      <Typography fontSize={15} fontWeight={700} color="#EAF0FF" mb={0.5}>Voice</Typography>
      <Typography fontSize={12} color="#7D92C4" mb={2.5}>
        Speech-to-text for Ask Orphos and for audio files in document ingestion.
        Runs entirely on this deployment — no audio leaves it.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2, fontSize: 12 }} onClose={() => setError(null)}>{error}</Alert>}

      {/* ── 1. The module (status only — its lifecycle follows the toggles below) ── */}
      <Box sx={{ border: '1px solid #2E4A8C', borderRadius: '10px', bgcolor: '#0F2450', p: 2, mb: 2.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, mb: 1.5 }}>
          <Box>
            <Typography fontSize={13} fontWeight={660} color="#EAF0FF">Speech recognition module</Typography>
            <Typography fontSize={11} color="#7D92C4">
              {autoManaged
                ? 'Starts automatically while at least one tenant below has voice on, and stops when none does. Whisper claims about 3 GB while running.'
                : 'Automatic start is turned off on this deployment (eip.voice.autostart.enabled=false). Whisper claims about 3 GB while running.'}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', gap: 1, flexShrink: 0 }}>
            <Button size="small" variant="outlined" startIcon={<RefreshIcon sx={{ fontSize: 15 }} />}
              onClick={load} disabled={!!busy}
              sx={{ textTransform: 'none', fontSize: 11.5 }}>Refresh</Button>
          </Box>
        </Box>

        {!prepared ? (
          <Alert severity="info" sx={{ fontSize: 11.5 }}>
            The voice containers have not been created on this host yet, so they cannot run.
            Set <code>EIP_VOICE_ENABLED=true</code> in <code>.env</code> and re-run the launcher, or run{' '}
            <code>./startmodules.sh voice</code> once. After that they start by themselves whenever a
            tenant below has voice on.
          </Alert>
        ) : (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
            {VOICE_CONTAINERS.map(name => {
              const { label, tone } = chipFor(name);
              const c = TONES[tone];
              return (
                <Box key={name} sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Typography fontSize={11.5} color="#C3D3F2" sx={{ minWidth: 150, fontFamily: 'monospace' }}>
                    {name}
                  </Typography>
                  <Chip size="small" label={label}
                    sx={{ height: 20, fontSize: 10, fontWeight: 600,
                          bgcolor: c.bg, color: c.fg, border: `1px solid ${c.bd}` }} />
                  {starting(name) && <CircularProgress size={12} />}
                </Box>
              );
            })}
          </Box>
        )}
      </Box>

      {/* ── 2. Per-tenant enablement ──────────────────────────────────────── */}
      <Box sx={{ border: '1px solid #2E4A8C', borderRadius: '10px', bgcolor: '#0F2450', p: 2 }}>
        <Typography fontSize={13} fontWeight={660} color="#EAF0FF">Enabled for</Typography>
        <Typography fontSize={11} color="#7D92C4" mb={1.5}>
          Voice is off for every tenant until switched on here. Switching a tenant on also starts the
          module above if it is not already running; only tenants that are on see dictation.
        </Typography>

        {prepared && enabledIds.length > 0 && !allRunning && (
          anyStarting || (autoManaged && wanted) ? (
            <Alert severity="info" sx={{ mb: 1.5, fontSize: 11.5 }}>
              The module is starting for {enabledIds.length} enabled tenant{enabledIds.length === 1 ? '' : 's'}.
              Whisper takes a minute or two to load its model (longer the first time, while it downloads),
              and dictation becomes available as soon as it is up.
            </Alert>
          ) : (
            <Alert severity="warning" sx={{ mb: 1.5, fontSize: 11.5 }}>
              {enabledIds.length} tenant{enabledIds.length === 1 ? ' is' : 's are'} enabled but the module is not
              running, so dictation is unavailable to them right now.
            </Alert>
          )
        )}

        <Divider sx={{ borderColor: '#243A72', mb: 1 }} />

        {tenants.length === 0 ? (
          <Typography fontSize={11.5} color="#7D92C4" sx={{ py: 1 }}>No tenants to show.</Typography>
        ) : (
          <Box sx={{ display: 'flex', flexDirection: 'column' }}>
            {tenants.map(t => {
              const id = t.id;
              const on = enabledIds.includes(id);
              return (
                <Box key={id} sx={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  gap: 2, py: 0.75, borderBottom: '1px solid #1B2C5A',
                }}>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography fontSize={12.5} color="#EAF0FF" noWrap>{t.displayName || t.domain || id}</Typography>
                    <Typography fontSize={10} color="#7D92C4" noWrap>{t.domain || id}</Typography>
                  </Box>
                  <Tooltip title={on ? 'Voice is on for this tenant' : 'Voice is off for this tenant'}>
                    <span>
                      <Switch size="small" checked={on} disabled={busy === id}
                        onChange={e => toggleTenant(id, e.target.checked)} />
                    </span>
                  </Tooltip>
                </Box>
              );
            })}
          </Box>
        )}
      </Box>
    </Box>
  );
}
