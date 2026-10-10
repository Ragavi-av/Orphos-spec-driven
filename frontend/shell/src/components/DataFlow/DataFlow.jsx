import { useState, useEffect, useCallback } from 'react';
import {
  Box, Paper, Typography, Table, TableHead, TableRow, TableCell, TableBody,
  Chip, CircularProgress, Alert, Button, Tooltip,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import { formatDateTime } from 'eip-shell-context/datetime';

// Category → accent colour + display order (source planes).
const CATEGORY = {
  'Database':              { color: '#8DBCFF', order: 1 },
  'Ontology Graph':        { color: '#8DBCFF', order: 2 },
  'Document Source':       { color: '#8DBCFF', order: 3 },
  'Files':                 { color: '#3DDC97', order: 4 },
  'Email':                 { color: '#FFB84D', order: 5 },
  'Communication Channel': { color: '#3DDC97', order: 6 },
  'Infrastructure':        { color: '#C3D3F2', order: 7 },
};

// TEMPORARY — suppressed for demo. Flip back to `true` once the underlying fault
// is fixed; nothing else needs changing.
//
// The banner is honest but currently permanent, not transient as its wording
// claims: platform-core's DataFlowAggregator gets HTTP 401 from
// data-ingestion's /api/internal/sources, which is guarded in-controller by a
// shared X-Internal-Token. That token resolves EMPTY in every container
// (EIP_SECURITY_INTERNAL_SERVICE_TOKEN / ..._TENANT_DISCOVERY_SERVICE_TOKEN are
// unset in compose, .env and the Dockerfiles), so the guard rejects the call.
// data-ingestion is the only one of the four aggregated services that declares
// `eip.security.internal-service-token` in its application.yml, which is the
// leading suspect for why it alone refuses while doc-ingest, mail-insight and
// okg-ingest accept the same empty token — and that asymmetry means those three
// are arguably the ones misbehaving.
//
// Consequence of hiding it: a genuine data-ingestion outage during a demo will
// now be silent on this screen. The failure is still logged to the console below
// and remains visible in platform-core's logs.
const SHOW_PARTIAL_SOURCE_WARNING = false;

function fmtWhen(iso, t) {
  if (!iso) return { label: t('dataFlow.never'), stale: true };
  const d = new Date(iso);
  if (isNaN(d.getTime())) return { label: '—', stale: true };
  return { label: formatDateTime(d), stale: false };
}

export default function DataFlow() {
  const { t } = useTranslation();
  const [data,    setData]    = useState(null);
  const [loading, setLoading] = useState(true);
  const [err,     setErr]     = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/admin/data-flow');
      setData(r.data);
    } catch (e) {
      setErr(e?.response?.data?.error || 'Could not load data-flow sources.');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const sources = (data?.sources || []).slice().sort((a, b) => {
    const ca = CATEGORY[a.category]?.order ?? 99, cb = CATEGORY[b.category]?.order ?? 99;
    return ca !== cb ? ca - cb : String(a.name).localeCompare(String(b.name));
  });
  const failed = (data?.services || []).filter(s => s.ok === false).map(s => s.service);

  // The banner is suppressed for demos (see SHOW_PARTIAL_SOURCE_WARNING), so log
  // instead — a source that cannot be reached must not vanish silently.
  useEffect(() => {
    if (!SHOW_PARTIAL_SOURCE_WARNING && failed.length > 0) {
      // eslint-disable-next-line no-console
      console.warn('[data-flow] sources unreachable (banner suppressed):', failed.join(', '));
    }
  }, [failed.join(',')]);

  return (
    <Paper elevation={0} sx={{ border: '1px solid #243A72', borderRadius: 2, overflow: 'hidden' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', p: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={14} color='#FFFFFF'>{t('dataFlow.title')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('dataFlow.subtitle')}
          </Typography>
        </Box>
        <Button size='small' startIcon={<RefreshIcon />} onClick={load}
          sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.common.refresh')}</Button>
      </Box>

      {err && <Alert severity='warning' sx={{ mx: 2, mb: 1, fontSize: 13 }}>{err}</Alert>}
      {data && data.available === false && (
        <Alert severity='info' sx={{ mx: 2, mb: 1, fontSize: 13 }}>{data.message}</Alert>
      )}
      {SHOW_PARTIAL_SOURCE_WARNING && failed.length > 0 && (
        <Alert severity='warning' sx={{ mx: 2, mb: 1, fontSize: 12 }}>
          {t('dataFlow.partialSources', { sources: failed.join(', ') })}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={26} /></Box>
      ) : (
        <Table size='small'>
          <TableHead>
            <TableRow sx={{ bgcolor: '#0F2450' }}>
              {['source', 'category', 'entities', 'items', 'lastRefreshed'].map(h => (
                <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                  borderBottom: '2px solid #243A72' }}>
                  {t(`dataFlow.col.${h}`)}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {sources.length === 0 ? (
              <TableRow><TableCell colSpan={5} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                {t('dataFlow.empty')}
              </TableCell></TableRow>
            ) : sources.map((s, i) => {
              const cat = CATEGORY[s.category] || { color: '#C3D3F2' };
              const when = fmtWhen(s.lastRefreshAt, t);
              const entities = s.ontologyEntities || [];
              return (
                <TableRow key={`${s.service}-${s.name}-${i}`} sx={{ '&:hover': { bgcolor: '#0F2450' } }}>
                  <TableCell sx={{ fontSize: 13 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: cat.color, flexShrink: 0 }} />
                      <Box>
                        <Typography fontSize={13} fontWeight={600} color='#FFFFFF'>{s.name || '—'}</Typography>
                        {s.kind && <Typography fontSize={11} color='#7D92C4'>{s.kind}</Typography>}
                      </Box>
                      {s.enabled === false && (
                        <Chip size='small' label={t('dataFlow.disabled')} sx={{ fontSize: 9, height: 18, bgcolor: '#0F2450', color: '#7D92C4' }} />
                      )}
                    </Box>
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>{s.category}</TableCell>
                  <TableCell>
                    {entities.length === 0 ? (
                      <Tooltip title={t('dataFlow.noOntologyLink')}>
                        <Typography component='span' fontSize={13} color='#7D92C4'>—</Typography>
                      </Tooltip>
                    ) : (
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                        {entities.map(e => (
                          <Chip key={e} label={e} size='small'
                            sx={{ fontSize: 10, fontWeight: 600, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
                        ))}
                      </Box>
                    )}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                    {s.count == null ? '—' : s.count}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, color: when.stale ? '#7D92C4' : '#C3D3F2', whiteSpace: 'nowrap' }}>
                    {when.label}
                    {s.status && <Typography fontSize={10} color='#7D92C4'>{s.status}</Typography>}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
}
