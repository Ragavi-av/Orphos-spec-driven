import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Box, Typography, Paper, Chip, IconButton, Tooltip,
  Table, TableHead, TableRow, TableCell, TableBody,
  Button, Alert, CircularProgress, ToggleButton, ToggleButtonGroup,
} from '@mui/material';
import PlayArrowIcon  from '@mui/icons-material/PlayArrow';
import StopIcon       from '@mui/icons-material/Stop';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import PauseIcon      from '@mui/icons-material/Pause';
import PlayCircleIcon from '@mui/icons-material/PlayCircle';
import RefreshIcon    from '@mui/icons-material/Refresh';
import CircleIcon     from '@mui/icons-material/Circle';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';
import { useToast } from 'eip-shell-context/ToastProvider';

/**
 * Style for the unified realStatus computed by the backend
 * (state + Docker health + on-demand reachability probe).
 */
const STATUS_STYLE = {
  healthy:     { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97', label: 'healthy'     },
  running:     { bg: '#8DBCFF', color: '#3DDC97', label: 'running'     },
  starting:    { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D', label: 'starting'    },
  unhealthy:   { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81', label: 'unhealthy'   },
  unreachable: { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D', label: 'unreachable' },
  paused:      { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D', label: 'paused'      },
  stopped:     { bg: '#243A72', color: '#C3D3F2', label: 'stopped'     },
  created:     { bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF', label: 'created'     },
  restarting:  { bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF', label: 'restarting'  },
};

function statusStyle(s) {
  return STATUS_STYLE[s] ?? { bg: '#243A72', color: '#C3D3F2', label: s || 'unknown' };
}

/** A status counts as "up" for the group roll-up if the process is serving. */
const UP_STATUSES = new Set(['healthy', 'running', 'starting']);

/** Ordered group headings for each grouping mode. */
const FUNCTION_ORDER = [
  'Platform & Gateway', 'Application Services', 'Intelligence & Processing',
  'Data Stores', 'AI / ML', 'Auth & Secrets', 'Observability',
  'Agents', 'MCP Servers', 'Plugins',
];
const STACK_ORDER = ['Application', 'Data', 'AI/ML', 'Infrastructure'];

/** Derive a friendly service label from the container name. */
function friendlyName(name) {
  return name.replace(/^eip-/, '').replace(/-/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase());
}

/** Small coloured dot describing the active reachability probe result. */
function Reachability({ svc }) {
  if (!svc.probeable) {
    return <Typography component='span' fontSize={11} color='#7D92C4'>—</Typography>;
  }
  const map = {
    up:      { color: '#3DDC97', label: 'Reachable'     },
    down:    { color: '#FF6B81', label: 'Not reachable' },
    unknown: { color: '#7D92C4', label: 'Not probed'    },
  };
  const r = map[svc.reachable] ?? map.unknown;
  return (
    <Tooltip title={`Health probe: ${r.label}`}>
      <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
        <CircleIcon sx={{ fontSize: 9, color: r.color }} />
        <Typography component='span' fontSize={11} color='#C3D3F2'>{r.label}</Typography>
      </Box>
    </Tooltip>
  );
}

export default function ServicesTab() {
  const toast = useToast();
  const [services,  setServices]  = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState(null);
  const [pending,   setPending]   = useState({});   // { containerName: actionName }
  const [groupBy,   setGroupBy]   = useState('category');   // 'category' | 'stack'

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/admin/services');
      setServices(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.error || 'Could not load service list. Is the Docker socket mounted?');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const doAction = async (name, action) => {
    setPending(p => ({ ...p, [name]: action }));
    try {
      await axios.post(`/api/admin/services/${encodeURIComponent(name)}/${action}`);
      toast.success(`Service '${friendlyName(name)}' ${action} command sent successfully.`);
      // brief pause so Docker state has time to settle before re-polling
      await new Promise(r => setTimeout(r, 800));
      await load();
    } catch (e) {
      setError(`${action} failed for ${name}: ${e?.response?.data?.error || e.message}`);
    }
    setPending(p => { const next = { ...p }; delete next[name]; return next; });
  };

  // Split the flat list into ordered groups for the selected grouping mode.
  const groups = useMemo(() => {
    const key   = groupBy === 'stack' ? 'stack' : 'category';
    const order = groupBy === 'stack' ? STACK_ORDER : FUNCTION_ORDER;
    const byKey = new Map();
    for (const svc of services) {
      const g = svc[key] || 'Other';
      if (!byKey.has(g)) byKey.set(g, []);
      byKey.get(g).push(svc);
    }
    const names = [...byKey.keys()].sort((a, b) => {
      const ia = order.indexOf(a), ib = order.indexOf(b);
      return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib);
    });
    return names.map(name => ({ name, items: byKey.get(name) }));
  }, [services, groupBy]);

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2.5, gap: 2, flexWrap: 'wrap' }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Service Control</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            Live Docker container status for the EIP stack, grouped by {groupBy === 'stack' ? 'deployment stack' : 'function'}.
            Status reflects real health and reachability. Actions take effect immediately.
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <ToggleButtonGroup
            size='small' exclusive value={groupBy}
            onChange={(_e, v) => { if (v) setGroupBy(v); }}
            sx={{ '& .MuiToggleButton-root': { textTransform: 'none', fontSize: 12, px: 1.5, py: 0.5 } }}
          >
            <ToggleButton value='category'>By function</ToggleButton>
            <ToggleButton value='stack'>By stack</ToggleButton>
          </ToggleButtonGroup>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>Refresh</Button>
        </Box>
      </Box>

      {error && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : services.length === 0 ? (
        <Paper variant='outlined' sx={{ borderRadius: 2, p: 4, textAlign: 'center', color: '#7D92C4', fontSize: 13 }}>
          No containers found. Ensure /var/run/docker.sock is mounted.
        </Paper>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
          {groups.map(group => {
            const total = group.items.length;
            const up = group.items.filter(s => UP_STATUSES.has(s.realStatus)).length;
            const allUp = up === total;
            return (
              <CollapsibleSection key={group.name} title={group.name} count={total}
                detailsSx={{ p: 0 }}
                summary={(
                  <Chip size='small' label={`${up}/${total} up`}
                    sx={{ height: 20, fontSize: 11, fontWeight: 700,
                      bgcolor: allUp ? 'rgba(61, 220, 151, 0.14)' : 'rgba(255, 0, 0, 0.14)',
                      color:   allUp ? '#3DDC97' : '#FF6B81' }} />
                )}>
                <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
                  <Table size='small'>
                    <TableHead>
                      <TableRow sx={{ bgcolor: '#0F2450' }}>
                        {['Service', 'Container', 'Status', 'Reachability', 'Uptime', 'Actions'].map(h => (
                          <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                            borderBottom: '2px solid #243A72', py: 1.25 }}>
                            {h}
                          </TableCell>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {group.items.map((svc, i) => {
                        const ss     = statusStyle(svc.realStatus);
                        const isPending = !!pending[svc.name];
                        const isRunning = svc.state === 'running';
                        const isPaused  = svc.state === 'paused';
                        const isStopped = svc.state === 'exited' || svc.state === 'created';
                        return (
                          <TableRow key={svc.name}
                            sx={{ bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                              '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' } }}>
                            <TableCell sx={{ fontWeight: 600, fontSize: 13, color: '#FFFFFF' }}>
                              {friendlyName(svc.name)}
                            </TableCell>
                            <TableCell sx={{ fontSize: 11, color: '#C3D3F2', fontFamily: 'monospace' }}>
                              {svc.name}
                            </TableCell>
                            <TableCell>
                              <Chip size='small' label={ss.label}
                                sx={{ bgcolor: ss.bg, color: ss.color, fontWeight: 700, fontSize: 11 }} />
                            </TableCell>
                            <TableCell><Reachability svc={svc} /></TableCell>
                            <TableCell sx={{ fontSize: 11, color: '#C3D3F2', maxWidth: 200,
                              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {svc.status}
                            </TableCell>
                            <TableCell>
                              {isPending ? (
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                  <CircularProgress size={14} />
                                  <Typography fontSize={11} color='#C3D3F2'>{pending[svc.name]}…</Typography>
                                </Box>
                              ) : (
                                <Box sx={{ display: 'flex', gap: 0.25 }}>
                                  {isStopped && (
                                    <Tooltip title='Start'>
                                      <IconButton size='small' onClick={() => doAction(svc.name, 'start')}
                                        sx={{ color: '#3DDC97' }}>
                                        <PlayArrowIcon sx={{ fontSize: 17 }} />
                                      </IconButton>
                                    </Tooltip>
                                  )}
                                  {isRunning && (
                                    <Tooltip title='Pause'>
                                      <IconButton size='small' onClick={() => doAction(svc.name, 'pause')}
                                        sx={{ color: '#FFB84D' }}>
                                        <PauseIcon sx={{ fontSize: 17 }} />
                                      </IconButton>
                                    </Tooltip>
                                  )}
                                  {isPaused && (
                                    <Tooltip title='Unpause'>
                                      <IconButton size='small' onClick={() => doAction(svc.name, 'unpause')}
                                        sx={{ color: '#8DBCFF' }}>
                                        <PlayCircleIcon sx={{ fontSize: 17 }} />
                                      </IconButton>
                                    </Tooltip>
                                  )}
                                  {(isRunning || isPaused) && (
                                    <Tooltip title='Restart'>
                                      <IconButton size='small' onClick={() => doAction(svc.name, 'restart')}
                                        sx={{ color: '#8DBCFF' }}>
                                        <RestartAltIcon sx={{ fontSize: 17 }} />
                                      </IconButton>
                                    </Tooltip>
                                  )}
                                  {(isRunning || isPaused) && (
                                    <Tooltip title='Stop'>
                                      <IconButton size='small' onClick={() => doAction(svc.name, 'stop')}
                                        sx={{ color: '#FF6B81' }}>
                                        <StopIcon sx={{ fontSize: 17 }} />
                                      </IconButton>
                                    </Tooltip>
                                  )}
                                </Box>
                              )}
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </Paper>
              </CollapsibleSection>
            );
          })}
        </Box>
      )}
    </Box>
  );
}
