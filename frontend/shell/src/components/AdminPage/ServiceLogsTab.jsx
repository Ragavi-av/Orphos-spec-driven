import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Box, Typography, Paper, Chip, Button, Alert, CircularProgress,
  Select, MenuItem, TextField, ToggleButtonGroup, ToggleButton, Tooltip,
} from '@mui/material';
import RefreshIcon      from '@mui/icons-material/Refresh';
import DownloadIcon     from '@mui/icons-material/Download';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import axios from 'axios';

const ALL_SERVICES = [
  'eip-platform-core', 'eip-admin-console', 'eip-widgets', 'eip-platform-registry',
  'eip-nl-query', 'eip-okg-ingest', 'eip-okg-reasoning',
  'eip-graph-proxy', 'eip-priority-engine', 'eip-mail-insight', 'eip-briefing', 'eip-agents',
  'eip-decision-log', 'eip-notification', 'eip-doc-ingest', 'eip-docling',
  'eip-plugin-ai-copilot', 'eip-plugin-email-scanner', 'eip-plugin-doc-ingest',
  'eip-plugin-rag-search', 'eip-plugin-widgets', 'eip-plugin-decision-log',
  'eip-keycloak', 'eip-arcadedb', 'eip-fuseki', 'eip-qdrant', 'eip-kafka',
  'eip-postgres', 'eip-infisical', 'eip-ollama', 'eip-ollama-hi',
  'eip-victoria-logs', 'eip-perses', 'eip-prometheus', 'eip-shell', 'eip-traefik',
];

const LIMIT_OPTIONS = [30, 50, 100, 200];
const LEVEL_OPTIONS = [
  { value: '',      label: 'All levels' },
  { value: 'ERROR', label: 'ERROR' },
  { value: 'WARN',  label: 'WARN'  },
  { value: 'INFO',  label: 'INFO'  },
];

const LEVEL_COLOR = {
  ERROR: '#FF6B81',
  WARN:  '#FFB84D',
  INFO:  '#8DBCFF',
  DEBUG: '#8DBCFF',
};

function detectLevel(line) {
  const up = line.toUpperCase();
  if (up.includes(' ERROR') || up.includes('[ERROR]')) return 'ERROR';
  if (up.includes(' WARN')  || up.includes('[WARN]'))  return 'WARN';
  if (up.includes(' INFO')  || up.includes('[INFO]'))  return 'INFO';
  if (up.includes(' DEBUG') || up.includes('[DEBUG]')) return 'DEBUG';
  return null;
}

function friendlyName(name) {
  return name.replace(/^eip-/, '').replace(/-/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase());
}

function fmtTimestamp(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleTimeString(undefined, {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    fractionalSecondDigits: 3,
  });
}

export default function ServiceLogsTab() {
  const [logs,    setLogs]    = useState([]);
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);
  const [service, setService] = useState('');         // '' = all
  const [limit,   setLimit]   = useState(30);
  const [level,   setLevel]   = useState('');
  const [wrap,    setWrap]    = useState(false);
  const bottomRef = useRef(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const params = { limit };
      if (service)      params.service     = service;
      if (level)        params.levelFilter = level;
      const r = await axios.get('/api/admin/logs', { params });
      setLogs(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.error || 'Could not load logs. Ensure VictoriaLogs is running.');
    }
    setLoading(false);
  }, [service, limit, level]);

  useEffect(() => { load(); }, [load]);

  const download = () => {
    const text = logs.map(l =>
      `[${fmtTimestamp(l.timestamp)}] [${l.container}] ${l.line}`
    ).join('\n');
    const blob = new Blob([text], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `eip-logs-${service || 'all'}-${Date.now()}.txt`;
    a.click();
  };

  return (
    <Box>
      {/* Controls */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        mb: 2, gap: 1.5, flexWrap: 'wrap' }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Service Logs</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            Recent log lines from VictoriaLogs — newest first.
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <Select size='small' value={service} onChange={e => setService(e.target.value)}
            displayEmpty sx={{ fontSize: 12, height: 32, minWidth: 160 }}>
            <MenuItem value='' sx={{ fontSize: 12 }}>All services</MenuItem>
            {ALL_SERVICES.map(s => (
              <MenuItem key={s} value={s} sx={{ fontSize: 12 }}>{friendlyName(s)}</MenuItem>
            ))}
          </Select>
          <Select size='small' value={level} onChange={e => setLevel(e.target.value)}
            displayEmpty sx={{ fontSize: 12, height: 32, minWidth: 110 }}>
            {LEVEL_OPTIONS.map(o => (
              <MenuItem key={o.value} value={o.value} sx={{ fontSize: 12 }}>{o.label}</MenuItem>
            ))}
          </Select>
          <Select size='small' value={limit} onChange={e => setLimit(e.target.value)}
            sx={{ fontSize: 12, height: 32, minWidth: 80 }}>
            {LIMIT_OPTIONS.map(n => (
              <MenuItem key={n} value={n} sx={{ fontSize: 12 }}>Last {n}</MenuItem>
            ))}
          </Select>
          <ToggleButton value='wrap' size='small' selected={wrap}
            onChange={() => setWrap(w => !w)}
            sx={{ fontSize: 11, height: 32, px: 1.5, textTransform: 'none',
              border: '1px solid #243A72' }}>
            Wrap
          </ToggleButton>
          <Tooltip title='Download as .txt'>
            <Button size='small' variant='outlined' startIcon={<DownloadIcon />}
              onClick={download} disabled={logs.length === 0}
              sx={{ textTransform: 'none', fontSize: 12, height: 32,
                borderColor: '#243A72', color: '#C3D3F2' }}>
              Export
            </Button>
          </Tooltip>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12, height: 32 }}>
            Refresh
          </Button>
        </Box>
      </Box>

      {error && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : logs.length === 0 ? (
        <Paper variant='outlined' sx={{ p: 3, textAlign: 'center', borderRadius: 2 }}>
          <Typography fontSize={13} color='#C3D3F2'>
            No log lines found for the selected filters.
          </Typography>
        </Paper>
      ) : (
        <CollapsibleSection title='Log output' count={logs.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{
          borderRadius: 2, overflow: 'hidden',
          bgcolor: '#152652',
          maxHeight: 560, overflowY: 'auto',
        }}>
          {logs.map((entry, i) => {
            const lvl   = detectLevel(entry.line);
            const color = lvl ? LEVEL_COLOR[lvl] : '#7D92C4';
            return (
              <Box key={i} sx={{
                display: 'flex', gap: 1,
                px: 1.5, py: 0.4,
                borderBottom: i < logs.length - 1 ? '1px solid #243A72' : 'none',
                '&:hover': { bgcolor: '#152652' },
                alignItems: 'flex-start',
              }}>
                {/* Timestamp */}
                <Typography fontSize={11} fontFamily='monospace'
                  color='#C3D3F2' sx={{ flexShrink: 0, mt: '1px' }}>
                  {fmtTimestamp(entry.timestamp)}
                </Typography>
                {/* Container badge */}
                {!service && (
                  <Chip label={entry.container?.replace(/^eip-/, '') ?? ''}
                    size='small'
                    sx={{
                      fontSize: 9, height: 16, flexShrink: 0,
                      bgcolor: '#0F2450', color: '#8DBCFF',
                      '& .MuiChip-label': { px: 0.75 },
                    }} />
                )}
                {/* Log line */}
                <Typography fontSize={11} fontFamily='monospace'
                  color={color}
                  sx={{
                    flex: 1,
                    whiteSpace: wrap ? 'pre-wrap' : 'nowrap',
                    overflow: wrap ? 'visible' : 'hidden',
                    textOverflow: wrap ? 'clip' : 'ellipsis',
                    wordBreak: wrap ? 'break-all' : 'normal',
                  }}>
                  {entry.line}
                </Typography>
              </Box>
            );
          })}
          <div ref={bottomRef} />
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1}>
        {logs.length} line{logs.length !== 1 ? 's' : ''} — sourced from VictoriaLogs (last 1 hour window).
      </Typography>
    </Box>
  );
}
