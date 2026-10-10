import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Drawer, Box, Typography, IconButton, Divider, Chip, CircularProgress, Alert,
  Table, TableBody, TableCell, TableRow, Button, Tooltip,
} from '@mui/material';
import CloseIcon         from '@mui/icons-material/Close';
import RefreshIcon       from '@mui/icons-material/Refresh';
import StorageIcon       from '@mui/icons-material/Storage';
import LockIcon          from '@mui/icons-material/Lock';
import GroupIcon         from '@mui/icons-material/Group';
import AccessTimeIcon    from '@mui/icons-material/AccessTime';
import PauseCircleIcon   from '@mui/icons-material/PauseCircle';
import PlayCircleIcon    from '@mui/icons-material/PlayCircle';
import DeleteForeverIcon from '@mui/icons-material/DeleteForever';
import PlayArrowIcon     from '@mui/icons-material/PlayArrow';
import CheckCircleIcon   from '@mui/icons-material/CheckCircle';
import ErrorOutlineIcon  from '@mui/icons-material/ErrorOutline';
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty';
import axios from 'axios';
import { useToast } from 'eip-shell-context/ToastProvider';

/**
 * Side drawer with operational metrics for a single tenant. Fetches
 * {@code GET /api/admin/tenants/{id}/metrics} on open and re-fetches on demand.
 *
 * <p>Lifecycle actions (suspend / activate / soft-delete / destroy) are
 * delegated back to the parent via {@code onAction}, which already owns the
 * dialogs and reload logic.</p>
 */
export default function TenantDetailDrawer({ tenant, open, onClose, onAction }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [metrics, setMetrics] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState(null);
  const [migrations, setMigrations] = useState(null);
  const [migrationsLoading, setMigrationsLoading] = useState(false);
  const [migrationsError, setMigrationsError]     = useState(null);
  const [migrating, setMigrating]                 = useState(false);

  const fetchMetrics = useCallback(async (id) => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get(`/api/admin/tenants/${id}/metrics`);
      setMetrics(r.data);
    } catch (e) {
      setError(humanError(e, t('admin.screens.tenantDrawer.errMetrics', 'Could not load tenant metrics')));
    }
    setLoading(false);
  }, [t]);

  const fetchMigrations = useCallback(async (id) => {
    setMigrationsLoading(true); setMigrationsError(null);
    try {
      const r = await axios.get(`/api/admin/tenants/${id}/migrations`);
      setMigrations(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setMigrationsError(humanError(e, t('admin.screens.tenantDrawer.errMigrations', 'Could not load migration history')));
    }
    setMigrationsLoading(false);
  }, [t]);

  const runPendingMigrations = useCallback(async (id) => {
    setMigrating(true); setMigrationsError(null);
    try {
      await axios.post(`/api/admin/tenants/${id}/migrate`);
      await fetchMigrations(id);
      toast.success(t('admin.screens.tenantDrawer.migrationsAppliedToast', 'Pending migrations applied for {{name}}.', {
        name: tenant?.displayName || tenant?.domain || t('admin.screens.tenantDrawer.tenantFallback', 'tenant'),
      }));
    } catch (e) {
      setMigrationsError(humanError(e, t('admin.screens.tenantDrawer.errMigrationRun', 'Migration run failed')));
    }
    setMigrating(false);
  }, [fetchMigrations, toast, tenant?.displayName, tenant?.domain, t]);

  useEffect(() => {
    if (!open || !tenant?.id) {
      setMetrics(null);
      setError(null);
      setMigrations(null);
      setMigrationsError(null);
      return;
    }
    fetchMetrics(tenant.id);
    fetchMigrations(tenant.id);
  }, [open, tenant?.id, fetchMetrics, fetchMigrations]);

  if (!tenant) return null;

  const isDeleted   = tenant.status === 'DELETED';
  const isSuspended = tenant.status === 'SUSPENDED';
  const isActive    = tenant.status === 'ACTIVE';

  return (
    <Drawer anchor='right' open={open} onClose={onClose}
      hideBackdrop
      ModalProps={{ disableScrollLock: true, disableEnforceFocus: true, disableAutoFocus: true }}
      // Hover-preview panel: no backdrop and pointer-events pass through the modal
      // root so the tenant table behind it stays hoverable/clickable. The Paper
      // itself re-enables pointer events so its own buttons still work.
      sx={{ pointerEvents: 'none' }}
      PaperProps={{ sx: {
        width: { xs: '100%', sm: 460 }, p: 0, pointerEvents: 'auto',
        boxShadow: '-8px 0 24px rgba(0,0,0,0.5)',
      } }}>
      <Box sx={{ p: 2.5, display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        borderBottom: '1px solid #243A72' }}>
        <Box>
          <Typography fontWeight={800} fontSize={15} color='#FFFFFF' sx={{ fontFamily: 'monospace' }}>
            {tenant.domain}
          </Typography>
          <Typography fontSize={12} color='#C3D3F2'>{tenant.displayName || '—'}</Typography>
        </Box>
        <IconButton onClick={onClose} size='small'><CloseIcon /></IconButton>
      </Box>

      <Box sx={{ p: 2.5, display: 'flex', flexDirection: 'column', gap: 2.5 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          <Chip size='small' label={tenant.status}
            sx={{ fontSize: 10, fontWeight: 700,
              bgcolor: isActive ? 'rgba(61, 220, 151, 0.14)' : isSuspended ? 'rgba(255, 184, 77, 0.16)' : 'rgba(255, 0, 0, 0.14)',
              color:   isActive ? '#3DDC97' : isSuspended ? '#FFB84D' : '#FF6B81' }} />
          <Chip size='small' label={tenant.isolationMode}
            sx={{ fontSize: 10, fontWeight: 700,
              bgcolor: tenant.isolationMode === 'DEDICATED' ? 'rgba(255, 184, 77, 0.16)' : 'rgba(91, 157, 255, 0.14)',
              color:   tenant.isolationMode === 'DEDICATED' ? '#FFB84D' : '#8DBCFF' }} />
        </Box>

        {error && <Alert severity='warning' sx={{ fontSize: 12 }}>{error}</Alert>}

        <Box>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
            <Typography fontWeight={700} fontSize={12} color='#FFFFFF'>{t('admin.screens.tenantDrawer.overview', 'Overview')}</Typography>
            <Tooltip title={t('admin.screens.tenantDrawer.refreshMetrics', 'Refresh metrics')}>
              <IconButton size='small' onClick={() => fetchMetrics(tenant.id)} disabled={loading}>
                <RefreshIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          </Box>
          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}>
              <CircularProgress size={22} />
            </Box>
          ) : (
            <OverviewGrid tenant={tenant} metrics={metrics} />
          )}
        </Box>

        <Divider />

        <Box>
          <Typography fontWeight={700} fontSize={12} color='#FFFFFF' mb={1}>{t('admin.screens.tenantDrawer.resources', 'Resources')}</Typography>
          <ResourceRow icon={<StorageIcon sx={{ fontSize: 16, color: '#C3D3F2' }} />}
            label={t('admin.screens.tenantDrawer.resArcadeDb', 'ArcadeDB')} value={tenant.arcadeDatabase}
            ok={metrics?.databaseReachable} loading={loading} />
          <ResourceRow icon={<LockIcon sx={{ fontSize: 16, color: '#C3D3F2' }} />}
            label={t('admin.screens.tenantDrawer.resKeycloakRealm', 'Keycloak realm')} value={tenant.keycloakRealm}
            ok={metrics?.keycloakRealmReachable} loading={loading} />
        </Box>

        <Divider />

        <Box>
          <Typography fontWeight={700} fontSize={12} color='#FFFFFF' mb={1}>{t('admin.screens.tenantDrawer.documents', 'Documents')}</Typography>
          {!metrics ? (
            <Typography fontSize={12} color='#7D92C4'>{t('admin.screens.tenantDrawer.noData', 'No data.')}</Typography>
          ) : (
            <Table size='small'>
              <TableBody>
                {Object.entries(metrics.docCounts || {}).map(([type, n]) => (
                  <TableRow key={type}>
                    <TableCell sx={{ fontSize: 11, color: '#C3D3F2', borderBottom: '1px solid #243A72', py: 0.5 }}>
                      {type}
                    </TableCell>
                    <TableCell sx={{ fontSize: 11, fontWeight: 700, color: n < 0 ? '#FF6B81' : '#FFFFFF',
                      textAlign: 'right', borderBottom: '1px solid #243A72', py: 0.5 }}>
                      {n < 0 ? t('admin.screens.tenantDrawer.unavailable', 'unavailable') : n.toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
                <TableRow>
                  <TableCell sx={{ fontSize: 11, fontWeight: 800, color: '#FFFFFF',
                    borderBottom: 'none', py: 1 }}>
                    {t('admin.screens.tenantDrawer.total', 'Total')}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, fontWeight: 800, color: '#FFFFFF',
                    textAlign: 'right', borderBottom: 'none', py: 1 }}>
                    {(metrics.totalDocs || 0).toLocaleString()}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          )}
        </Box>

        <Divider />

        <MigrationsSection
          migrations={migrations}
          loading={migrationsLoading}
          error={migrationsError}
          migrating={migrating}
          onRefresh={() => fetchMigrations(tenant.id)}
          onRun={() => runPendingMigrations(tenant.id)}
        />

        <Divider />

        <Box>
          <Typography fontWeight={700} fontSize={12} color='#FFFFFF' mb={1}>{t('admin.screens.tenantDrawer.lifecycle', 'Lifecycle')}</Typography>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
            {isActive && (
              <Button size='small' startIcon={<PauseCircleIcon />}
                onClick={() => onAction?.('suspend', tenant)}
                sx={{ textTransform: 'none', fontSize: 12, color: '#FFB84D' }}>
                {t('admin.screens.tenantDrawer.suspend', 'Suspend')}
              </Button>
            )}
            {isSuspended && (
              <Button size='small' startIcon={<PlayCircleIcon />}
                onClick={() => onAction?.('activate', tenant)}
                sx={{ textTransform: 'none', fontSize: 12, color: '#3DDC97' }}>
                {t('admin.screens.tenantDrawer.reactivate', 'Reactivate')}
              </Button>
            )}
            <Button size='small' startIcon={<DeleteForeverIcon />}
              onClick={() => onAction?.('destroy', tenant)}
              sx={{ textTransform: 'none', fontSize: 12, color: '#FF6B81' }}>
              {t('admin.screens.tenantDrawer.destroy', 'Destroy')}
            </Button>
          </Box>
        </Box>
      </Box>
    </Drawer>
  );
}

function OverviewGrid({ tenant, metrics }) {
  const { t } = useTranslation();
  const lastActivity = metrics?.lastActivityAt
    ? new Date(metrics.lastActivityAt).toLocaleString('en-GB')
    : '—';
  const userCount = metrics?.userCount;
  const userDisplay = userCount == null
    ? '—'
    : userCount < 0
      ? t('admin.screens.tenantDrawer.unavailable', 'unavailable')
      : userCount.toLocaleString();

  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}>
      <Stat icon={<GroupIcon sx={{ fontSize: 18, color: '#8DBCFF' }} />} label={t('admin.screens.tenantDrawer.statUsers', 'Users')} value={userDisplay} />
      <Stat icon={<StorageIcon sx={{ fontSize: 18, color: '#8DBCFF' }} />} label={t('admin.screens.tenantDrawer.statTotalDocs', 'Total docs')}
        value={metrics ? (metrics.totalDocs || 0).toLocaleString() : '—'} />
      <Stat icon={<AccessTimeIcon sx={{ fontSize: 18, color: '#3DDC97' }} />} label={t('admin.screens.tenantDrawer.statLastActivity', 'Last activity')} value={lastActivity} />
      <Stat icon={<LockIcon sx={{ fontSize: 18, color: '#FF6B81' }} />} label={t('admin.screens.tenantDrawer.statTenantUuid', 'Tenant UUID')}
        value={<code style={{ fontSize: 10 }}>{tenant.id}</code>} />
    </Box>
  );
}

function Stat({ icon, label, value }) {
  return (
    <Box sx={{ p: 1.25, border: '1px solid #243A72', borderRadius: 1.5, bgcolor: '#0B1533' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5 }}>
        {icon}
        <Typography fontSize={10} fontWeight={700} color='#C3D3F2' textTransform='uppercase' letterSpacing='0.05em'>
          {label}
        </Typography>
      </Box>
      <Typography fontSize={13} fontWeight={700} color='#FFFFFF' sx={{ wordBreak: 'break-all' }}>
        {value}
      </Typography>
    </Box>
  );
}

function ResourceRow({ icon, label, value, ok, loading }) {
  const { t } = useTranslation();
  let statusChip;
  if (loading) {
    statusChip = <Chip size='small' label='…' sx={{ fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />;
  } else if (ok === true) {
    statusChip = <Chip size='small' label={t('admin.screens.tenantDrawer.reachable', 'reachable')} sx={{ fontSize: 10, bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97', fontWeight: 700 }} />;
  } else if (ok === false) {
    statusChip = <Chip size='small' label={t('admin.screens.tenantDrawer.unavailable', 'unavailable')} sx={{ fontSize: 10, bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', fontWeight: 700 }} />;
  } else {
    statusChip = <Chip size='small' label={t('admin.screens.tenantDrawer.unknown', 'unknown')} sx={{ fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />;
  }
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: '1px solid #243A72' }}>
      {icon}
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography fontSize={11} fontWeight={700} color='#FFFFFF'>{label}</Typography>
        <Typography fontSize={10} fontFamily='monospace' color='#C3D3F2' noWrap>{value}</Typography>
      </Box>
      {statusChip}
    </Box>
  );
}

function MigrationsSection({ migrations, loading, error, migrating, onRefresh, onRun }) {
  const { t } = useTranslation();
  const pendingCount = (migrations || []).filter(m => m.status === 'PENDING').length;
  const failedCount  = (migrations || []).filter(m => m.status === 'FAILED').length;

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography fontWeight={700} fontSize={12} color='#FFFFFF'>{t('admin.screens.tenantDrawer.schemaMigrations', 'Schema migrations')}</Typography>
          {pendingCount > 0 && (
            <Chip size='small' label={t('admin.screens.tenantDrawer.pendingChip', '{{n}} pending', { n: pendingCount })}
              sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
          )}
          {failedCount > 0 && (
            <Chip size='small' label={t('admin.screens.tenantDrawer.failedChip', '{{n}} failed', { n: failedCount })}
              sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81' }} />
          )}
        </Box>
        <Box sx={{ display: 'flex', gap: 0.5 }}>
          <Tooltip title={t('admin.screens.tenantDrawer.refresh', 'Refresh')}>
            <span>
              <IconButton size='small' onClick={onRefresh} disabled={loading || migrating}>
                <RefreshIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </span>
          </Tooltip>
          <Button size='small' startIcon={migrating ? <CircularProgress size={12} /> : <PlayArrowIcon />}
            disabled={migrating || (pendingCount === 0 && failedCount === 0)}
            onClick={onRun}
            sx={{ textTransform: 'none', fontSize: 11, color: '#8DBCFF' }}>
            {migrating
              ? t('admin.screens.tenantDrawer.running', 'Running…')
              : t('admin.screens.tenantDrawer.runPending', 'Run pending')}
          </Button>
        </Box>
      </Box>

      {error && <Alert severity='warning' sx={{ fontSize: 12, mb: 1 }}>{error}</Alert>}

      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
          <CircularProgress size={20} />
        </Box>
      ) : !migrations || migrations.length === 0 ? (
        <Typography fontSize={12} color='#7D92C4'>{t('admin.screens.tenantDrawer.noMigrations', 'No migrations registered.')}</Typography>
      ) : (
        <Box>
          {migrations.map(m => <MigrationRow key={`${m.version}-${m.status}`} m={m} />)}
        </Box>
      )}
    </Box>
  );
}

function MigrationRow({ m }) {
  let icon, color;
  if (m.status === 'APPLIED') {
    icon = <CheckCircleIcon sx={{ fontSize: 14, color: '#3DDC97' }} />;
    color = '#3DDC97';
  } else if (m.status === 'FAILED') {
    icon = <ErrorOutlineIcon sx={{ fontSize: 14, color: '#FF6B81' }} />;
    color = '#FF6B81';
  } else {
    icon = <HourglassEmptyIcon sx={{ fontSize: 14, color: '#FFB84D' }} />;
    color = '#FFB84D';
  }
  const ts = m.appliedAt ? new Date(m.appliedAt).toLocaleString('en-GB') : '';
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1, py: 0.5,
      borderBottom: '1px solid #243A72' }}>
      <Box sx={{ pt: 0.25 }}>{icon}</Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
          <Typography fontSize={11} fontWeight={700} color='#FFFFFF' fontFamily='monospace'>
            v{m.version}
          </Typography>
          <Typography fontSize={11} color='#FFFFFF' sx={{ wordBreak: 'break-word' }}>
            {m.name}
          </Typography>
        </Box>
        <Typography fontSize={10} color={color} fontWeight={700}>
          {m.status}{ts ? ` · ${ts}` : ''}
        </Typography>
        {m.errorMessage && (
          <Typography fontSize={10} color='#FF6B81' sx={{ wordBreak: 'break-word' }}>
            {m.errorMessage}
          </Typography>
        )}
      </Box>
    </Box>
  );
}

function humanError(e, fallback) {
  return e?.response?.data?.message || e?.response?.data?.error || e?.message || fallback;
}
