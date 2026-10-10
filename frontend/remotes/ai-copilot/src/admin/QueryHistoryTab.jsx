import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Chip, Button, Alert, CircularProgress,
  Table, TableHead, TableRow, TableCell, TableBody,
  TextField, Tooltip, Select, MenuItem,
} from '@mui/material';
import RefreshIcon    from '@mui/icons-material/Refresh';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon       from '@mui/icons-material/Error';
import axios from 'axios';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { modelFamily, isLocalProvider, modelBadgeTooltip } from '../modelFamily';

const LIMIT_OPTIONS = [10, 30, 50, 100];

function relativeTime(tsRaw, t) {
  if (!tsRaw) return '—';
  const ts = typeof tsRaw === 'number' ? tsRaw : Date.parse(tsRaw);
  if (isNaN(ts)) return String(tsRaw);
  const diffMs = Date.now() - ts;
  const diffS  = Math.floor(diffMs / 1000);
  if (diffS < 60)   return t('admin.screens.queryHistory.secondsAgo', '{{count}}s ago', { count: diffS });
  if (diffS < 3600) return t('admin.screens.queryHistory.minutesAgo', '{{count}}m ago', { count: Math.floor(diffS / 60) });
  if (diffS < 86400) return t('admin.screens.queryHistory.hoursAgo', '{{count}}h ago', { count: Math.floor(diffS / 3600) });
  return new Date(ts).toLocaleString();
}

function formatBytes(chars, t) {
  if (chars == null) return '—';
  if (chars < 1000) return t('admin.screens.queryHistory.chars', '{{count}} ch', { count: chars });
  return t('admin.screens.queryHistory.kiloChars', '{{count}}k ch', { count: (chars / 1000).toFixed(1) });
}

function latencyColor(ms) {
  if (ms == null) return '#C3D3F2';
  if (ms < 500)  return '#3DDC97';
  if (ms < 2000) return '#FFB84D';
  return '#FF6B81';
}

export default function QueryHistoryTab() {
  const { t } = useTranslation();
  const [rows,    setRows]    = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState(null);
  const [limit,   setLimit]   = useState(30);
  const [userId,  setUserId]  = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const params = { limit };
      if (userId.trim()) params.userId = userId.trim();
      const r = await axios.get('/api/admin/query-history', { params });
      setRows(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not load query history.');
    }
    setLoading(false);
  }, [limit, userId]);

  useEffect(() => { load(); }, [load]);

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.queryHistory.scopeResource', 'query history')} />

      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', mb: 2.5, gap: 2, flexWrap: 'wrap' }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Copilot Query History</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            AI usage records from the NL Query service — model, latency, and size metrics.
            Query text is never stored.
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            size='small' placeholder='Filter by user ID…'
            value={userId} onChange={e => setUserId(e.target.value)}
            sx={{ width: 200, '& input': { fontSize: 12 } }}
          />
          <Select size='small' value={limit} onChange={e => setLimit(e.target.value)}
            sx={{ fontSize: 12, height: 32, minWidth: 80 }}>
            {LIMIT_OPTIONS.map(n => (
              <MenuItem key={n} value={n} sx={{ fontSize: 12 }}>Last {n}</MenuItem>
            ))}
          </Select>
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
      ) : (
        <CollapsibleSection title={t('admin.screens.queryHistory.sectionTitle', 'Query history')}
          count={rows.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                {['When', 'User', 'Provider / Model', 'Prompt', 'Response', 'Latency', 'Status'].map(h => (
                  <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                    borderBottom: '2px solid #243A72', py: 1.5, whiteSpace: 'nowrap' }}>
                    {h}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    No query history found. Copilot usage will appear here after the first query.
                  </TableCell>
                </TableRow>
              ) : rows.map((row, i) => (
                <TableRow key={i}
                  sx={{ bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                    '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' } }}>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2', whiteSpace: 'nowrap' }}>
                    {relativeTime(row.timestamp, t)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 11, color: '#C3D3F2',
                    maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    <Tooltip title={row.userSub || '—'}>
                      <span>{row.userDisplay || row.userSub || '—'}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    {/* Badge is derived from the recorded model, not from providerId:
                        the routing ids `gemma` / `inhouse-hi` are historical and
                        serve Gemini and Qwen models respectively. The id itself stays
                        available in the tooltip. */}
                    <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', alignItems: 'center' }}>
                      {(row.modelName || row.providerId) && (
                        <Tooltip title={modelBadgeTooltip(row.modelName, row.providerId)}>
                          <Chip label={modelFamily(row.modelName, row.providerId)} size='small'
                            sx={{ fontSize: 10, fontWeight: 700,
                              bgcolor: isLocalProvider(row.providerId)
                                ? 'rgba(61, 220, 151, 0.14)' : 'rgba(91, 157, 255, 0.14)',
                              color: isLocalProvider(row.providerId) ? '#3DDC97' : '#8DBCFF' }} />
                        </Tooltip>
                      )}
                      {row.modelName && (
                        <Typography fontSize={11} color='#C3D3F2' fontFamily='monospace'>
                          {row.modelName}
                        </Typography>
                      )}
                    </Box>
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                    {formatBytes(row.promptChars, t)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                    {formatBytes(row.responseChars, t)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, fontWeight: 600,
                    color: latencyColor(row.latencyMs) }}>
                    {row.latencyMs != null ? `${row.latencyMs} ms` : '—'}
                  </TableCell>
                  <TableCell>
                    {row.success === true || row.success === 'true' ? (
                      <CheckCircleIcon sx={{ fontSize: 16, color: '#3DDC97' }} />
                    ) : row.success === false || row.success === 'false' ? (
                      <Tooltip title={row.errorClass || 'error'}>
                        <ErrorIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                      </Tooltip>
                    ) : (
                      <Typography fontSize={12} color='#7D92C4'>—</Typography>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1.5}>
        Showing up to {limit} records. Prompt and response content is never logged — only size and timing metadata.
      </Typography>
    </Box>
  );
}
