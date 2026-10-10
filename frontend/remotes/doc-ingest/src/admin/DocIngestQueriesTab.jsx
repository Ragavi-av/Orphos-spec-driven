import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  Table, TableHead, TableRow, TableCell, TableBody,
  TextField, Select, MenuItem, Tooltip, Chip,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';

const LIMIT_OPTIONS = [20, 50, 100];

function relTime(ts) {
  if (!ts) return '—';
  const d = Date.now() - new Date(ts).getTime();
  if (d < 60000)   return `${Math.floor(d/1000)}s ago`;
  if (d < 3600000) return `${Math.floor(d/60000)}m ago`;
  if (d < 86400000) return `${Math.floor(d/3600000)}h ago`;
  return new Date(ts).toLocaleString();
}

export default function DocIngestQueriesTab() {
  const { t } = useTranslation();
  const [rows,    setRows]    = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState(null);
  const [limit,   setLimit]   = useState(20);
  const [filter,  setFilter]  = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/doc-ingest/admin/search/history', { params: { limit } });
      setRows(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.message || t('admin.screens.docQueries.loadError', 'Could not load search history.'));
    }
    setLoading(false);
  }, [limit]);

  useEffect(() => { load(); }, [load]);

  const filtered = filter.trim()
    ? rows.filter(r => r.query?.toLowerCase().includes(filter.toLowerCase())
        || r.userSub?.toLowerCase().includes(filter.toLowerCase()))
    : rows;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.docQueries.scopeResource', 'document search history')} />

      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', mb: 2.5, gap: 2, flexWrap: 'wrap' }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.docQueries.title', 'Document Search History')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.docQueries.subtitle', 'Queries made against ingested documents — shows usage patterns and popular searches.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
          <TextField size='small' placeholder={t('admin.screens.docQueries.filterPlaceholder', 'Filter by query or user…')}
            value={filter} onChange={e => setFilter(e.target.value)}
            sx={{ width: 220, '& input': { fontSize: 12 } }} />
          <Select size='small' value={limit} onChange={e => setLimit(e.target.value)}
            sx={{ fontSize: 12, height: 32, minWidth: 80 }}>
            {LIMIT_OPTIONS.map(n => (
              <MenuItem key={n} value={n} sx={{ fontSize: 12 }}>{t('admin.screens.docQueries.lastN', 'Last {{count}}', { count: n })}</MenuItem>
            ))}
          </Select>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.docQueries.refresh', 'Refresh')}</Button>
        </Box>
      </Box>

      {error && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>{error}</Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <CollapsibleSection title={t('admin.screens.docQueries.sectionTitle', 'Search history')}
          count={rows.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                {[
                  { k: 'colWhen',    en: 'When' },
                  { k: 'colUser',    en: 'User' },
                  { k: 'colQuery',   en: 'Query' },
                  { k: 'colResults', en: 'Results' },
                  { k: 'colLatency', en: 'Latency' },
                ].map(h => (
                  <TableCell key={h.k} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                    borderBottom: '2px solid #243A72', py: 1.5, whiteSpace: 'nowrap' }}>
                    {t(`admin.screens.docQueries.${h.k}`, h.en)}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    {filter ? t('admin.screens.docQueries.noFilterMatch', 'No results match your filter.') : t('admin.screens.docQueries.emptyState', 'No search history yet. Searches from the Document Search tab will appear here.')}
                  </TableCell>
                </TableRow>
              ) : filtered.map((row, i) => (
                <TableRow key={row.searchId || i} sx={{
                  bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                  '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' },
                }}>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2', whiteSpace: 'nowrap' }}>
                    {relTime(row.timestamp)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 11, color: '#C3D3F2', maxWidth: 140,
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    <Tooltip title={row.userSub || '—'}>
                      <span>{row.userSub || '—'}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#FFFFFF', maxWidth: 320 }}>
                    <Tooltip title={row.query}>
                      <Typography fontSize={12} sx={{
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      }}>
                        {row.query}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Chip label={row.resultCount ?? 0} size='small'
                      sx={{
                        fontSize: 10, fontWeight: 700,
                        bgcolor: (row.resultCount ?? 0) > 0 ? 'rgba(61, 220, 151, 0.14)' : '#0F2450',
                        color: (row.resultCount ?? 0) > 0 ? '#3DDC97' : '#C3D3F2',
                      }} />
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                    {row.latencyMs != null ? `${row.latencyMs} ms` : '—'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1}>
        {t('admin.screens.docQueries.footer', 'History is persisted in ArcadeDB (survives service restarts).')}
      </Typography>
    </Box>
  );
}
