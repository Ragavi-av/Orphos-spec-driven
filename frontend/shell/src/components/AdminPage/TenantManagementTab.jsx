import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Button, Chip, Paper,
  Table, TableHead, TableRow, TableCell, TableBody,
  IconButton, CircularProgress, Alert, Tooltip, Checkbox,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, MenuItem, Select, FormControl, InputLabel,
} from '@mui/material';
import RefreshIcon         from '@mui/icons-material/Refresh';
import AddIcon             from '@mui/icons-material/Add';
import DeleteForeverIcon   from '@mui/icons-material/DeleteForever';
import PauseCircleIcon     from '@mui/icons-material/PauseCircle';
import PlayCircleIcon      from '@mui/icons-material/PlayCircle';
import LockIcon            from '@mui/icons-material/Lock';
import axios from 'axios';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { useRole } from 'eip-shell-context/useRole';
import { useToast } from 'eip-shell-context/ToastProvider';
import { setActingTenant } from 'eip-shell-context/actingTenant';
import HardDeleteConfirmDialog from './HardDeleteConfirmDialog';
import BulkDestroyConfirmDialog from './BulkDestroyConfirmDialog';
import TenantDetailDrawer      from './TenantDetailDrawer';

/**
 * Platform-superadmin tab for the tenant registry.
 *
 * <p>Gated by {@code isPlatformAdmin} — the per-tenant {@code admin} role is
 * NOT sufficient. A regular admin who lands on this tab sees a "platform
 * only" panel instead of the tenant list. The backend mirrors this gate via
 * {@code @PreAuthorize("hasRole('platform_admin')")} on every endpoint, so
 * this is UX defense-in-depth, not the security boundary.</p>
 *
 * <p>Operations:
 * <ul>
 *   <li><b>Create</b> — POST /api/admin/tenants (modal with domain, displayName, isolationMode)</li>
 *   <li><b>Suspend / Activate</b> — PATCH status; reversible</li>
 *   <li><b>Soft-delete</b> — DELETE; reversible by an operator who edits the row</li>
 *   <li><b>Destroy</b> — DELETE .../destroy; type-domain-to-confirm; IRREVERSIBLE</li>
 * </ul>
 */
export default function TenantManagementTab() {
  const { t } = useTranslation();
  const { isPlatformAdmin } = useRole();

  if (!isPlatformAdmin) {
    return (
      <Box sx={{
        minHeight: 360, display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexDirection: 'column', gap: 1.5, p: 4, textAlign: 'center',
      }}>
        <LockIcon sx={{ fontSize: 40, color: '#FF6B81' }} />
        <Typography fontWeight={700} fontSize={16} color='#FFFFFF'>
          {t('admin.screens.tenants.platformOnlyTitle', 'Platform-superadmin only')}
        </Typography>
        <Typography fontSize={13} color='#C3D3F2' sx={{ maxWidth: 460 }}>
          {t('admin.screens.tenants.platformOnlyBody1', 'The tenant registry is reserved for the')}{' '}
          <code style={{ fontSize: 12 }}>platform_admin</code>{' '}
          {t('admin.screens.tenants.platformOnlyBody2', 'role, issued from the dedicated')}{' '}
          <code style={{ fontSize: 12 }}>platform</code>{' '}
          {t('admin.screens.tenants.platformOnlyBody3', 'realm. Tenant-scoped administrators can manage users, branding, and email for their own tenant via the other tabs.')}
        </Typography>
      </Box>
    );
  }

  return <TenantList />;
}

// ── List + actions ──────────────────────────────────────────────────────────
function TenantList() {
  const { t } = useTranslation();
  const toast = useToast();
  const [tenants, setTenants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState(null);
  const [actionBusy, setActionBusy] = useState({});
  const [createOpen, setCreateOpen] = useState(false);
  const [destroyTarget, setDestroyTarget] = useState(null);
  const [drawerTenant, setDrawerTenant] = useState(null);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkDestroyOpen, setBulkDestroyOpen] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/admin/tenants');
      setTenants(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(humanError(e, t('admin.screens.tenants.errLoad', 'Could not load tenants')));
    }
    setLoading(false);
  }, [t]);

  useEffect(() => { reload(); }, [reload]);

  const setStatus = async (tenant, status) => {
    setActionBusy(b => ({ ...b, [tenant.id]: status }));
    try {
      await axios.patch(`/api/admin/tenants/${tenant.id}`, { status });
      await reload();
      toast.success(status === 'SUSPENDED'
        ? t('admin.screens.tenants.suspendedToast', 'Tenant {{name}} suspended.', { name: tenant.displayName || tenant.domain })
        : t('admin.screens.tenants.activatedToast', 'Tenant {{name}} activated.', { name: tenant.displayName || tenant.domain }));
    } catch (e) {
      setError(humanError(e, t('admin.screens.tenants.errSetStatus', 'Failed to set {{domain}} to {{status}}', { domain: tenant.domain, status })));
    }
    setActionBusy(b => ({ ...b, [tenant.id]: null }));
  };

  const handleDestroy = async ({ cascade }) => {
    if (!destroyTarget) return;
    const destroyedLabel = destroyTarget.displayName || destroyTarget.domain;
    await axios.delete(
      `/api/admin/tenants/${destroyTarget.id}/destroy${cascade ? '?cascade=true' : ''}`
    );
    // Successful destroy → close dialog + drawer + selection, reload list.
    const destroyedId = destroyTarget.id;
    setDestroyTarget(null);
    if (drawerTenant?.id === destroyedId) setDrawerTenant(null);
    setSelectedIds(prev => {
      if (!prev.has(destroyedId)) return prev;
      const next = new Set(prev);
      next.delete(destroyedId);
      return next;
    });
    await reload();
    setError(null);
    toast.success(t('admin.screens.tenants.destroyedToast', 'Tenant {{name}} destroyed permanently.', { name: destroyedLabel }));
  };

  // Bulk status update — used by the multi-select action bar.
  const bulkStatus = async (status) => {
    if (selectedIds.size === 0) return;
    setBulkBusy(true);
    try {
      const r = await axios.post('/api/admin/tenants/bulk-status', {
        ids: Array.from(selectedIds),
        status,
      });
      const failed = r.data?.results?.filter(item => !item.ok) || [];
      if (failed.length) {
        setError(t('admin.screens.tenants.bulkPartial', 'Bulk action: {{succeeded}}/{{total}} succeeded. Failures: {{failures}}', {
          succeeded: r.data.succeeded,
          total: r.data.total,
          failures: failed.map(f => f.error || f.id).join('; '),
        }));
      } else {
        setError(null);
        toast.success(status === 'SUSPENDED'
          ? t('admin.screens.tenants.bulkSuspendedToast', '{{n}} tenant(s) suspended.', { n: r.data?.succeeded ?? selectedIds.size })
          : t('admin.screens.tenants.bulkReactivatedToast', '{{n}} tenant(s) reactivated.', { n: r.data?.succeeded ?? selectedIds.size }));
      }
      setSelectedIds(new Set());
      await reload();
    } catch (e) {
      setError(humanError(e, t('admin.screens.tenants.errBulk', 'Bulk action failed')));
    }
    setBulkBusy(false);
  };

  // Drawer action delegates - dispatch to the existing dialogs / handlers so the
  // drawer never owns lifecycle state itself.
  const handleDrawerAction = (kind, t) => {
    if (kind === 'suspend')      setStatus(t, 'SUSPENDED');
    else if (kind === 'activate') setStatus(t, 'ACTIVE');
    else if (kind === 'destroy')      setDestroyTarget(t);
  };

  // Clicking a tenant switches the whole Administration view into that tenant's
  // scope (the header picker reflects it) via the shared acting-tenant store.
  // Deleted tenants can't be acted as - a click there only previews the drawer.
  const openTenantAdmin = (row) => {
    if (row.status === 'DELETED') return;
    setDrawerTenant(null);
    setActingTenant({
      tenantId:    row.id,
      domain:      row.domain,
      displayName: row.displayName,
    });
  };

  // Status makeup of the current selection — drives which bulk actions are
  // valid. Reactivate only makes sense when everything picked is SUSPENDED,
  // Suspend only when everything picked is ACTIVE. A mixed selection disables
  // both (the button tooltips explain why).
  const selectedTenants = useMemo(
    () => tenants.filter(t => selectedIds.has(t.id)),
    [tenants, selectedIds]);
  const allSelectedSuspended =
    selectedTenants.length > 0 && selectedTenants.every(t => t.status === 'SUSPENDED');
  const allSelectedActive =
    selectedTenants.length > 0 && selectedTenants.every(t => t.status === 'ACTIVE');

  // Bulk hard-delete. No batch endpoint exists, so fan out over the per-tenant
  // /destroy route; each id succeeds or fails independently (the demo tenant is
  // rejected server-side and surfaces as a failure in the partial report).
  const bulkDestroy = async ({ cascade }) => {
    const ids = Array.from(selectedIds);
    if (ids.length === 0) return;
    const settled = await Promise.allSettled(ids.map(id =>
      axios.delete(`/api/admin/tenants/${id}/destroy${cascade ? '?cascade=true' : ''}`)));
    const failed = [];
    settled.forEach((res, i) => {
      if (res.status === 'rejected') {
        const row = tenants.find(t => t.id === ids[i]);
        failed.push({ label: row?.domain || ids[i], error: humanError(res.reason, '') });
      }
    });
    const succeeded = ids.length - failed.length;
    setBulkDestroyOpen(false);
    if (drawerTenant && ids.includes(drawerTenant.id)) setDrawerTenant(null);
    setSelectedIds(new Set());
    await reload();
    if (failed.length) {
      setError(t('admin.screens.tenants.bulkDestroyPartial',
        'Destroyed {{succeeded}}/{{total}}. Failures: {{failures}}', {
          succeeded, total: ids.length,
          failures: failed.map(f => f.error ? `${f.label} (${f.error})` : f.label).join('; '),
        }));
    } else {
      setError(null);
      toast.success(t('admin.screens.tenants.bulkDestroyedToast',
        '{{n}} tenant(s) destroyed permanently.', { n: succeeded }));
    }
  };

  const selectableIds = useMemo(
    () => tenants.filter(t => t.status !== 'DELETED').map(t => t.id),
    [tenants]);
  const allSelectable = selectableIds.length > 0;
  const allSelected   = allSelectable && selectableIds.every(id => selectedIds.has(id));
  const someSelected  = !allSelected && selectableIds.some(id => selectedIds.has(id));
  const toggleAll = () => {
    setSelectedIds(prev => {
      if (allSelected) return new Set();
      return new Set(selectableIds);
    });
  };
  const toggleOne = (id) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.tenants.heading', 'Tenants')}</Typography>
          <Chip label={t('admin.screens.tenants.platformAdminChip', 'Platform Admin')} size='small'
            sx={{ bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D', fontWeight: 700, fontSize: 10 }} />
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<AddIcon />} variant='contained'
            onClick={() => setCreateOpen(true)}
            sx={{ textTransform: 'none', fontSize: 12, fontWeight: 700 }}>
            {t('admin.screens.tenants.createTenant', 'Create tenant')}
          </Button>
          <Button size='small' startIcon={<RefreshIcon />} onClick={reload}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.tenants.refresh', 'Refresh')}</Button>
        </Box>
      </Box>

      {error && <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}

      {selectedIds.size > 0 && (
        <Paper variant='outlined' sx={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          px: 2, py: 1, mb: 1.5, bgcolor: 'rgba(91, 157, 255, 0.14)', borderColor: 'rgba(91, 157, 255, 0.42)',
        }}>
          <Typography fontSize={13} color='#8DBCFF' fontWeight={700}>
            {t('admin.screens.tenants.nSelected', '{{n}} selected', { n: selectedIds.size })}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            {/* Tooltip on a span so the hint still shows while the button is disabled. */}
            <Tooltip title={allSelectedActive ? '' : t('admin.screens.tenants.suspendHint',
              'Only available when every selected tenant is ACTIVE.')}>
              <span>
                <Button size='small' disabled={bulkBusy || !allSelectedActive} startIcon={<PauseCircleIcon />}
                  onClick={() => bulkStatus('SUSPENDED')}
                  sx={{ textTransform: 'none', fontSize: 12, color: '#FFB84D' }}>
                  {t('admin.screens.tenants.suspend', 'Suspend')}
                </Button>
              </span>
            </Tooltip>
            <Tooltip title={allSelectedSuspended ? '' : t('admin.screens.tenants.reactivateHint',
              'Only available when every selected tenant is SUSPENDED.')}>
              <span>
                <Button size='small' disabled={bulkBusy || !allSelectedSuspended} startIcon={<PlayCircleIcon />}
                  onClick={() => bulkStatus('ACTIVE')}
                  sx={{ textTransform: 'none', fontSize: 12, color: '#3DDC97' }}>
                  {t('admin.screens.tenants.reactivate', 'Reactivate')}
                </Button>
              </span>
            </Tooltip>
            <Button size='small' disabled={bulkBusy} startIcon={<DeleteForeverIcon />}
              onClick={() => setBulkDestroyOpen(true)}
              sx={{ textTransform: 'none', fontSize: 12, color: '#FF6B81' }}>
              {t('admin.screens.tenants.destroy', 'Destroy')}
            </Button>
            <Button size='small' disabled={bulkBusy}
              onClick={() => setSelectedIds(new Set())}
              sx={{ textTransform: 'none', fontSize: 12 }}>
              {t('admin.screens.tenants.clear', 'Clear')}
            </Button>
          </Box>
        </Paper>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <CollapsibleSection title={t('admin.screens.tenants.sectionTitle', 'Tenants')}
          count={tenants.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                <TableCell padding='checkbox' sx={{ borderBottom: '2px solid #243A72' }}>
                  <Checkbox size='small' checked={allSelected} indeterminate={someSelected}
                    onChange={toggleAll} disabled={!allSelectable} />
                </TableCell>
                {[
                  t('admin.screens.tenants.colDomain', 'Domain'),
                  t('admin.screens.tenants.colDisplayName', 'Display name'),
                  t('admin.screens.tenants.colMode', 'Mode'),
                  t('admin.screens.tenants.colStatus', 'Status'),
                  t('admin.screens.tenants.colDataset', 'Dataset'),
                  t('admin.screens.tenants.colRealm', 'Realm'),
                  t('admin.screens.tenants.colActions', 'Actions'),
                ].map(h => (
                  <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                    borderBottom: '2px solid #243A72', py: 1.5 }}>
                    {h}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {tenants.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    {t('admin.screens.tenants.empty', 'No tenants registered yet.')}
                  </TableCell>
                </TableRow>
              ) : tenants.map((row, i) => (
                <TableRow key={row.id} hover sx={{
                  bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                  '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' },
                  opacity: row.status === 'DELETED' ? 0.55 : 1,
                }}>
                  <TableCell padding='checkbox'>
                    <Checkbox size='small' checked={selectedIds.has(row.id)}
                      disabled={row.status === 'DELETED'}
                      onChange={() => toggleOne(row.id)} />
                  </TableCell>
                  <TableCell sx={{ fontSize: 13, fontFamily: 'monospace' }}>
                    {/* The domain is the only interactive element: hover previews details,
                        click opens that tenant's admin scope. The rest of the row is inert. */}
                    <Tooltip title={row.status === 'DELETED'
                      ? t('admin.screens.tenants.deletedNoOpen', 'Deleted — hover to preview; cannot open')
                      : t('admin.screens.tenants.openTenantAdmin', 'Open {{domain}} admin', { domain: row.domain })}>
                      <Box component='span'
                        onMouseEnter={() => setDrawerTenant(row)}
                        onClick={() => openTenantAdmin(row)}
                        sx={{
                          color: row.status === 'DELETED' ? '#7D92C4' : '#8DBCFF',
                          cursor: row.status === 'DELETED' ? 'default' : 'pointer',
                          fontWeight: 600,
                          '&:hover': { textDecoration: row.status === 'DELETED' ? 'none' : 'underline' },
                        }}>
                        {row.domain}
                      </Box>
                    </Tooltip>
                  </TableCell>
                  <TableCell sx={{ fontSize: 13, color: '#FFFFFF' }}>
                    {row.displayName || '—'}
                  </TableCell>
                  <TableCell>
                    <Chip size='small' label={row.isolationMode}
                      sx={{
                        fontSize: 10, fontWeight: 700,
                        bgcolor: row.isolationMode === 'DEDICATED' ? 'rgba(255, 184, 77, 0.16)' : 'rgba(91, 157, 255, 0.14)',
                        color:   row.isolationMode === 'DEDICATED' ? '#FFB84D' : '#8DBCFF',
                      }} />
                  </TableCell>
                  <TableCell>
                    <StatusChip status={row.status} />
                  </TableCell>
                  <TableCell sx={{ fontSize: 11, fontFamily: 'monospace', color: '#C3D3F2',
                    maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {row.arcadeDatabase}
                  </TableCell>
                  <TableCell sx={{ fontSize: 11, fontFamily: 'monospace', color: '#C3D3F2',
                    maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {row.keycloakRealm}
                  </TableCell>
                  <TableCell onClick={e => e.stopPropagation()}>
                    <Box sx={{ display: 'flex', gap: 0.5 }}>
                      {row.status === 'ACTIVE' && (
                        <Tooltip title={t('admin.screens.tenants.suspend', 'Suspend')}>
                          <IconButton size='small' onClick={() => setStatus(row, 'SUSPENDED')}
                            disabled={!!actionBusy[row.id]}>
                            <PauseCircleIcon sx={{ fontSize: 18, color: '#FFB84D' }} />
                          </IconButton>
                        </Tooltip>
                      )}
                      {row.status === 'SUSPENDED' && (
                        <Tooltip title={t('admin.screens.tenants.reactivate', 'Reactivate')}>
                          <IconButton size='small' onClick={() => setStatus(row, 'ACTIVE')}
                            disabled={!!actionBusy[row.id]}>
                            <PlayCircleIcon sx={{ fontSize: 18, color: '#3DDC97' }} />
                          </IconButton>
                        </Tooltip>
                      )}
                      <Tooltip title={t('admin.screens.tenants.destroyTooltip', 'Destroy permanently (irreversible)')}>
                        {/* span needed because IconButton's disabled prop blocks tooltip events */}
                        <span>
                          <IconButton size='small'
                            onClick={() => setDestroyTarget(row)}
                            disabled={!!actionBusy[row.id]}>
                            <DeleteForeverIcon sx={{ fontSize: 18, color: '#FF6B81' }} />
                          </IconButton>
                        </span>
                      </Tooltip>
                    </Box>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1.5}>
        {t('admin.screens.tenants.footerNote', 'Destroy releases all external resources and hard-deletes the row. The demo tenant is protected: destroy is rejected for it.')}
      </Typography>

      <CreateTenantDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={async () => { setCreateOpen(false); await reload(); }}
      />

      <HardDeleteConfirmDialog
        open={!!destroyTarget}
        tenant={destroyTarget}
        onClose={() => setDestroyTarget(null)}
        onConfirmed={handleDestroy}
      />

      <BulkDestroyConfirmDialog
        open={bulkDestroyOpen}
        tenants={selectedTenants}
        onClose={() => setBulkDestroyOpen(false)}
        onConfirmed={bulkDestroy}
      />

      <TenantDetailDrawer
        open={!!drawerTenant}
        tenant={drawerTenant}
        onClose={() => setDrawerTenant(null)}
        onAction={handleDrawerAction}
      />
    </Box>
  );
}

// ── Subcomponents ───────────────────────────────────────────────────────────
function StatusChip({ status }) {
  const cfg = {
    ACTIVE:    { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
    SUSPENDED: { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
    DELETED:   { bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
  }[status] || { bg: '#243A72', color: '#C3D3F2' };
  return (
    <Chip size='small' label={status}
      sx={{ fontSize: 10, fontWeight: 700, bgcolor: cfg.bg, color: cfg.color }} />
  );
}

function CreateTenantDialog({ open, onClose, onCreated }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [domain, setDomain] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [isolationMode, setIsolationMode] = useState('DEDICATED');
  const [arcadeConnectionId, setArcadeConnectionId] = useState('arcadedb-bundled');
  const [connections, setConnections] = useState([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (open) {
      setDomain(''); setDisplayName(''); setIsolationMode('DEDICATED');
      setArcadeConnectionId('arcadedb-bundled');
      setSubmitting(false); setError(null);
      // Populate the DB Connection picker (DEDICATED tenants only) with the
      // registered ArcadeDB connections.
      axios.get('/api/admin/db-connections')
        .then(r => setConnections((r.data?.connections || []).filter(c => c.type === 'ARCADEDB')))
        .catch(() => setConnections([]));
    }
  }, [open]);

  // Always offer the bundled ArcadeDB even if the registry list didn't load.
  const connOptions = connections.some(c => c.id === 'arcadedb-bundled')
    ? connections
    : [{ id: 'arcadedb-bundled', name: 'Bundled ArcadeDB' }, ...connections];

  const submit = async () => {
    setSubmitting(true); setError(null);
    try {
      await axios.post('/api/admin/tenants', {
        domain: domain.trim().toLowerCase(),
        displayName: displayName.trim(),
        isolationMode,
        arcadeConnectionId: isolationMode === 'DEDICATED' ? arcadeConnectionId : null,
      });
      onCreated();
      toast.success(t('admin.screens.tenants.createdToast', 'Tenant {{name}} created successfully.', { name: displayName.trim() || domain.trim() }));
    } catch (e) {
      setError(humanError(e, t('admin.screens.tenants.errCreate', 'Failed to create tenant')));
      setSubmitting(false);
    }
  };

  const canSubmit = domain.trim() !== '' && displayName.trim() !== '' && !submitting;

  return (
    <Dialog open={open} onClose={submitting ? undefined : onClose} maxWidth='sm' fullWidth>
      <DialogTitle sx={{ fontWeight: 800, fontSize: 16 }}>Create tenant</DialogTitle>
      <DialogContent sx={{ pt: 2 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <TextField
            label='Domain'
            placeholder='acme.orphos.io'
            value={domain}
            onChange={e => setDomain(e.target.value)}
            disabled={submitting}
            size='small'
            helperText='RFC 1035 hostname — lowercase, dots, hyphens. Unique across the platform.'
            inputProps={{ 'aria-label': 'Tenant domain' }}
            sx={{ '& input': { fontFamily: 'monospace' } }}
          />
          <TextField
            label='Display name'
            placeholder='Acme Corp'
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
            disabled={submitting}
            size='small'
          />
          <FormControl size='small'>
            <InputLabel id='iso-mode-label'>Isolation mode</InputLabel>
            <Select
              labelId='iso-mode-label'
              label='Isolation mode'
              value={isolationMode}
              onChange={e => setIsolationMode(e.target.value)}
              disabled={submitting}>
              <MenuItem value='DEDICATED'>
                DEDICATED — own ArcadeDB + Fuseki dataset (recommended; strong isolation)
              </MenuItem>
              <MenuItem value='SHARED'>
                SHARED — row-level tenantId in orphos (cheap, weaker isolation)
              </MenuItem>
            </Select>
          </FormControl>
          {isolationMode === 'DEDICATED' && (
            <FormControl size='small'>
              <InputLabel id='arcade-conn-label'>DB Connection</InputLabel>
              <Select
                labelId='arcade-conn-label'
                label='DB Connection'
                value={arcadeConnectionId}
                onChange={e => setArcadeConnectionId(e.target.value)}
                disabled={submitting}>
                {connOptions.map(c => (
                  <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>
                ))}
              </Select>
              <Typography variant='caption' sx={{ mt: 0.5, color: 'text.secondary' }}>
                Which ArcadeDB hosts this tenant's database. Defaults to the bundled ArcadeDB.
              </Typography>
            </FormControl>
          )}
          {error && <Alert severity='error' sx={{ fontSize: 13 }}>{error}</Alert>}
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={submitting} sx={{ textTransform: 'none', fontSize: 13 }}>
          Cancel
        </Button>
        <Button onClick={submit} variant='contained' disabled={!canSubmit}
          startIcon={submitting ? <CircularProgress size={14} color='inherit' /> : null}
          sx={{ textTransform: 'none', fontWeight: 700, fontSize: 13 }}>
          {submitting ? 'Creating…' : 'Create tenant'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Helpers ─────────────────────────────────────────────────────────────────
function humanError(e, fallback) {
  return (
    e?.response?.data?.message ||
    e?.response?.data?.error ||
    e?.message ||
    fallback
  );
}
