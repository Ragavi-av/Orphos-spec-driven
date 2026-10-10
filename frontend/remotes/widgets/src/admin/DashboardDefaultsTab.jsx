import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, Switch, Divider, Alert, CircularProgress,
  IconButton, Tooltip,
} from '@mui/material';
import RefreshIcon       from '@mui/icons-material/Refresh';
import RestartAltIcon    from '@mui/icons-material/RestartAlt';
import ArrowUpwardIcon   from '@mui/icons-material/ArrowUpward';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import { useToast } from 'eip-shell-context/ToastProvider';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { CATALOG, ALL_IDS, DEFAULT_VISIBLE } from 'eip-shell-context/catalog';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { notifyDashboardLayoutChanged } from 'eip-shell-context/DashboardLayoutProvider';

/**
 * Tenant admin UI for the dashboard default layout.
 *
 * <p>The saved layout becomes the starting point for every new user in this
 * tenant, and what existing users see if they hit "Reset to tenant default"
 * in the widget picker. Per-user overrides are stored independently and are
 * not touched by changes here.</p>
 */
export default function DashboardDefaultsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [visibleIds, setVisibleIds] = useState(DEFAULT_VISIBLE);
  const [order, setOrder]           = useState(ALL_IDS);
  const [source, setSource]         = useState('system');
  const [loading, setLoading]       = useState(true);
  const [saving, setSaving]         = useState(false);
  const [error, setError]           = useState(null);
  const [info, setInfo]             = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/admin/dashboard/layout/default');
      const { visibleIds: v, order: o, source: s } = sanitise(r.data?.visibleIds, r.data?.order);
      setVisibleIds(v);
      setOrder(o);
      setSource(r.data?.source || 'system');
      void s;
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.dashboardDefaults.errLoad', 'Failed to load tenant default.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const toggle = (id) => {
    setVisibleIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
    setInfo(null);
  };

  const move = (id, dir) => {
    setOrder(prev => {
      const next = [...prev];
      const from = next.indexOf(id);
      if (from === -1) return prev;
      const to = dir === 'up' ? from - 1 : from + 1;
      if (to < 0 || to >= next.length) return prev;
      [next[from], next[to]] = [next[to], next[from]];
      return next;
    });
    setInfo(null);
  };

  const save = async () => {
    setSaving(true); setError(null); setInfo(null);
    try {
      const r = await axios.put('/api/admin/dashboard/layout/default', { visibleIds, order });
      const { visibleIds: v, order: o } = sanitise(r.data?.visibleIds, r.data?.order);
      setVisibleIds(v);
      setOrder(o);
      setSource(r.data?.source || 'tenant');
      setInfo(t('admin.screens.dashboardDefaults.infoSaved', 'Tenant default saved. New users in this tenant will see this layout.'));
      toast.success(t('admin.screens.dashboardDefaults.toastSaved', 'Dashboard defaults saved successfully.'));
      // Fire so any logged-in user whose layout was inheriting the tenant
      // default re-fetches without a manual reload.
      notifyDashboardLayoutChanged();
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.dashboardDefaults.errSave', 'Save failed.'));
    }
    setSaving(false);
  };

  const clearDefault = async () => {
    if (!window.confirm(t('admin.screens.dashboardDefaults.confirmClear', 'Clear the tenant default? New users will fall back to the platform default until you save a new one.'))) return;
    setSaving(true); setError(null); setInfo(null);
    try {
      await axios.delete('/api/admin/dashboard/layout/default');
      setInfo(t('admin.screens.dashboardDefaults.infoCleared', 'Tenant default cleared.'));
      toast.success(t('admin.screens.dashboardDefaults.toastCleared', 'Dashboard default cleared successfully.'));
      notifyDashboardLayoutChanged();
      await load();
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.dashboardDefaults.errDelete', 'Delete failed.'));
    }
    setSaving(false);
  };

  // Render widgets in current order; let admin reorder via ↑/↓ buttons (drag
  // works fine but explicit buttons are clearer for a config screen).
  const widgetsInOrder = order
    .map(id => CATALOG.find(w => w.id === id))
    .filter(Boolean);

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.dashboardDefaults.scopeResource', 'dashboard defaults')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
            {t('admin.screens.dashboardDefaults.title', 'Dashboard Defaults')}
          </Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.dashboardDefaults.subtitle', 'The widget selection and order new users in this tenant see on first load. Existing users keep their own customisation until they reset.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load} disabled={loading || saving}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.dashboardDefaults.refresh', 'Refresh')}</Button>
          {source === 'tenant' && (
            <Button size='small' startIcon={<RestartAltIcon />} onClick={clearDefault} disabled={saving}
              sx={{ textTransform: 'none', fontSize: 12, color: '#FF6B81' }}>{t('admin.screens.dashboardDefaults.clearDefault', 'Clear default')}</Button>
          )}
        </Box>
      </Box>

      {source === 'system' && !loading && (
        <Alert severity='info' sx={{ mb: 2, fontSize: 13 }}>
          {t('admin.screens.dashboardDefaults.noDefaultLead', 'No tenant default is set yet — new users currently see the platform default. Customise below and click')}
          {' '}<strong>{t('admin.screens.dashboardDefaults.saveDefault', 'Save default')}</strong>{' '}
          {t('admin.screens.dashboardDefaults.noDefaultTrail', 'to override it for this tenant.')}
        </Alert>
      )}

      {error && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}
      {info  && <Alert severity='success' sx={{ mb: 2, fontSize: 13 }}>{info}</Alert>}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>
      ) : (
        <CollapsibleSection title={t('admin.screens.dashboardDefaults.widgetsSection', 'Widgets')}
          count={widgetsInOrder.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          {widgetsInOrder.map((w, idx) => {
            const checked = visibleIds.includes(w.id);
            return (
              <Box key={w.id} sx={{
                display: 'flex', alignItems: 'center',
                px: 2, py: 1.25,
                bgcolor: idx % 2 === 0 ? '#152652' : '#0F2450',
                borderBottom: idx < widgetsInOrder.length - 1 ? '1px solid #243A72' : 'none',
              }}>
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, mr: 1 }}>
                  <Tooltip title={t('admin.screens.dashboardDefaults.moveUp', 'Move up')}>
                    <span>
                      <IconButton size='small' onClick={() => move(w.id, 'up')} disabled={idx === 0}
                        sx={{ p: 0.25 }}>
                        <ArrowUpwardIcon sx={{ fontSize: 14 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title={t('admin.screens.dashboardDefaults.moveDown', 'Move down')}>
                    <span>
                      <IconButton size='small' onClick={() => move(w.id, 'down')} disabled={idx === widgetsInOrder.length - 1}
                        sx={{ p: 0.25 }}>
                        <ArrowDownwardIcon sx={{ fontSize: 14 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Box>
                <Box sx={{
                  width: 6, height: 36, borderRadius: 1, bgcolor: w.color, mr: 1.5, flexShrink: 0,
                }} />
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography fontSize={13} fontWeight={600} color='#FFFFFF'>{w.label}</Typography>
                  <Typography fontSize={11} color='#7D92C4' noWrap>{w.description}</Typography>
                  {(w.area || w.source) && (
                    <Typography fontSize={10} color='#7D92C4' noWrap>
                      {[w.area && t(`widgets.areas.${w.area}`, w.area),
                        w.source && `${t('widgets.board.sourceLabel', 'Source:')} ${t(`widgets.sources.${w.source}`, w.source)}`]
                        .filter(Boolean).join('  ·  ')}
                    </Typography>
                  )}
                </Box>
                <Switch
                  size='small'
                  checked={checked}
                  onChange={() => toggle(w.id)}
                  sx={{
                    '& .MuiSwitch-switchBase.Mui-checked': { color: w.color },
                    '& .MuiSwitch-switchBase.Mui-checked + .MuiSwitch-track': { bgcolor: w.color },
                  }}
                />
              </Box>
            );
          })}
        </Paper>
        </CollapsibleSection>
      )}

      <Divider sx={{ my: 2.5 }} />

      <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
        <Button onClick={save} variant='contained' size='small' disabled={loading || saving}
          sx={{ textTransform: 'none', fontSize: 13 }}>
          {saving ? t('admin.screens.dashboardDefaults.saving', 'Saving…') : t('admin.screens.dashboardDefaults.saveDefault', 'Save default')}
        </Button>
      </Box>
    </Box>
  );
}

function sanitise(visibleIds, order) {
  const known = new Set(ALL_IDS);
  const dedup = (arr) => Array.from(new Set((arr || []).filter(id => known.has(id))));
  const ord = dedup(order);
  ALL_IDS.forEach(id => { if (!ord.includes(id)) ord.push(id); });
  return { visibleIds: dedup(visibleIds), order: ord, source: null };
}
