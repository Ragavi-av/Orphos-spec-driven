import { useEffect, useState } from 'react';
import {
  Box, Paper, Grid, Stack, Typography, CircularProgress, Alert, Button, LinearProgress,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import { useTranslation } from 'react-i18next';
import { getAnalytics, exportAnalyticsUrl } from './api';

/**
 * Decision_Analytics_Dashboard (Req 8): KPI tiles, distribution bars, and a
 * 12-month trend, rendered with lightweight MUI primitives (no chart lib in
 * the federation bundle). Data comes live from /api/decisions/analytics.
 */
export default function AnalyticsView() {
  const { t } = useTranslation();
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getAnalytics()
      .then(setData)
      .catch(() => setError(t('decisionLog.loadError')));
  }, [t]);

  if (error) return <Alert severity="warning">{error}</Alert>;
  if (!data) return <CircularProgress />;

  const kpis = [
    { label: t('decisionLog.analytics.total'), value: data.total },
    { label: t('decisionLog.analytics.pending'), value: (data.byStatus?.PENDING || 0) + (data.byStatus?.DETECTED || 0) },
    { label: t('decisionLog.analytics.velocity'), value: data.velocityPerMonth },
    { label: t('decisionLog.analytics.aiDetected'), value: data.aiDetectedCount },
    { label: t('decisionLog.analytics.escalated'), value: data.escalatedCount },
    { label: t('decisionLog.analytics.outcomeCoverage'), value: `${Math.round((data.outcomeCoverage || 0) * 100)}%` },
  ];

  return (
    <Box>
      <Stack direction="row" justifyContent="flex-end" mb={1}>
        <Button size="small" startIcon={<DownloadIcon />} href={exportAnalyticsUrl('csv')}>
          {t('decisionLog.actions.exportCsv')}
        </Button>
      </Stack>
      <Grid container spacing={2}>
        {kpis.map(k => (
          <Grid item xs={6} sm={4} md={2} key={k.label}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h5">{k.value ?? 0}</Typography>
              <Typography variant="caption" color="text.secondary">{k.label}</Typography>
            </Paper>
          </Grid>
        ))}

        <Grid item xs={12}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle2" gutterBottom>{t('decisionLog.analytics.trend')}</Typography>
            <TrendBars trend={data.monthlyTrend || {}} />
          </Paper>
        </Grid>

        <DistributionCard title={t('decisionLog.analytics.byDomain')} counts={data.byDomain} />
        <DistributionCard title={t('decisionLog.analytics.byPriority')} counts={data.byPriority} />
        <DistributionCard title={t('decisionLog.analytics.byStatus')} counts={data.byStatus} />
        <DistributionCard title={t('decisionLog.analytics.bySource')} counts={data.byDetectionSource} />
      </Grid>
    </Box>
  );
}

function DistributionCard({ title, counts }) {
  const entries = Object.entries(counts || {}).sort((a, b) => b[1] - a[1]).slice(0, 8);
  const max = Math.max(1, ...entries.map(([, v]) => v));
  return (
    <Grid item xs={12} sm={6} md={3}>
      <Paper sx={{ p: 2, height: '100%' }}>
        <Typography variant="subtitle2" gutterBottom>{title}</Typography>
        {entries.length === 0 && <Typography variant="caption" color="text.secondary">—</Typography>}
        {entries.map(([label, value]) => (
          <Box key={label} mb={1}>
            <Stack direction="row" justifyContent="space-between">
              <Typography variant="caption" noWrap sx={{ maxWidth: '70%' }}>{label}</Typography>
              <Typography variant="caption">{value}</Typography>
            </Stack>
            <LinearProgress variant="determinate" value={(value / max) * 100} sx={{ height: 6, borderRadius: 3 }} />
          </Box>
        ))}
      </Paper>
    </Grid>
  );
}

function TrendBars({ trend }) {
  const entries = Object.entries(trend);
  const max = Math.max(1, ...entries.map(([, v]) => v.total || 0));
  return (
    <Stack direction="row" alignItems="flex-end" spacing={1} sx={{ height: 140, overflowX: 'auto', pt: 1 }}>
      {entries.map(([month, v]) => (
        <Stack key={month} alignItems="center" spacing={0.5} sx={{ minWidth: 48 }}>
          <Typography variant="caption">{v.total || 0}</Typography>
          <Box sx={{ width: 26, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', height: 90 }}>
            <Box sx={{
              height: `${((v.aiDetected || 0) / max) * 100}%`,
              bgcolor: 'secondary.main', borderRadius: '3px 3px 0 0',
            }} title={`AI: ${v.aiDetected || 0}`} />
            <Box sx={{
              height: `${(((v.total || 0) - (v.aiDetected || 0)) / max) * 100}%`,
              bgcolor: 'primary.main', borderRadius: (v.aiDetected || 0) > 0 ? 0 : '3px 3px 0 0',
            }} />
          </Box>
          <Typography variant="caption" color="text.secondary">{month.slice(2)}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}
