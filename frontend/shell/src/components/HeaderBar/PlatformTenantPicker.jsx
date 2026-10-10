import { useEffect, useState, useCallback } from 'react';
import { Box, Select, MenuItem, Typography, CircularProgress } from '@mui/material';
import VisibilityIcon from '@mui/icons-material/Visibility';
import axios from 'eip-shell-context/axiosAuth';
import {
  getActingTenant,
  setActingTenant,
  clearActingTenant,
  onActingTenantChange,
} from 'eip-shell-context/actingTenant';

const SENTINEL_NONE = '__none__';

/**
 * Header-bar tenant picker for platform admins.
 *
 * <p>Lists all tenants from {@code GET /api/admin/tenants} and lets the user
 * select one to "act as". The selected UUID is persisted in localStorage
 * via {@link actingTenant} and attached as {@code X-Tenant-Id} to every
 * outbound request by the axios interceptor. admin-service's
 * {@code TenantResolutionFilter} honours the header only when the JWT
 * carries {@code ROLE_platform_admin}, so the override is safe to expose
 * unconditionally; non-platform-admins simply have it ignored.</p>
 *
 * <p>Rendered only when the caller is a platform admin — the parent
 * gates visibility via {@code useRole().isPlatformAdmin}. The component
 * also fails closed: if {@code /api/admin/tenants} returns 403 (the
 * caller lost the role mid-session) the picker hides itself.</p>
 */
export default function PlatformTenantPicker() {
  const [tenants, setTenants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [available, setAvailable] = useState(true);   // true unless the tenant list endpoint refuses us
  const [acting, setActing] = useState(() => getActingTenant());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // Send no X-Tenant-Id so the backend returns the cross-tenant list.
      const r = await axios.get('/api/admin/tenants', {
        headers: { 'X-Tenant-Id': '' },
      });
      const all = Array.isArray(r.data) ? r.data : (r.data?.tenants ?? []);
      // Drop soft-deleted entries — switching into a DELETED tenant would
      // succeed at the filter level but every downstream query would fail.
      setTenants(all.filter(t => !t.status || t.status === 'ACTIVE'));
      setAvailable(true);
    } catch (e) {
      // 403 = caller isn't actually platform_admin; hide the control.
      if (e?.response?.status === 403 || e?.response?.status === 401) {
        setAvailable(false);
      }
      setTenants([]);
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => onActingTenantChange(setActing), []);

  const onChange = (e) => {
    const v = e.target.value;
    if (v === SENTINEL_NONE) {
      clearActingTenant();
      return;
    }
    const picked = tenants.find(t => t.id === v);
    if (!picked) return;
    setActingTenant({
      tenantId:    picked.id,
      domain:      picked.domain,
      displayName: picked.displayName,
    });
  };

  if (!available) return null;

  const value = acting?.tenantId ?? SENTINEL_NONE;
  const label = acting
    ? (acting.displayName || acting.domain || 'tenant')
    : 'Platform (no override)';

  return (
    <Box sx={{
      display: 'flex', alignItems: 'center', gap: 0.75,
      px: 1, py: 0.25, borderRadius: 1.5,
      bgcolor: acting ? 'rgba(255, 184, 77, 0.16)' : '#0F2450',
      border: '1px solid ' + (acting ? 'rgba(255, 184, 77, 0.45)' : '#243A72'),
    }}>
      <VisibilityIcon sx={{ fontSize: 14, color: acting ? '#FFB84D' : '#C3D3F2' }} />
      {loading ? (
        <CircularProgress size={12} sx={{ ml: 0.5 }} />
      ) : (
        <Select
          value={value}
          onChange={onChange}
          variant='standard'
          disableUnderline
          renderValue={() => (
            <Typography fontSize={11} fontWeight={700}
              color={acting ? '#FFB84D' : '#C3D3F2'}
              sx={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {label}
            </Typography>
          )}
          sx={{
            '& .MuiSelect-select': { py: 0, pr: '20px !important' },
            '& .MuiSelect-icon':   { fontSize: 16, color: acting ? '#FFB84D' : '#C3D3F2' },
          }}
        >
          <MenuItem value={SENTINEL_NONE} sx={{ fontSize: 12 }}>
            Platform (no override)
          </MenuItem>
          {tenants.map(t => (
            <MenuItem key={t.id} value={t.id} sx={{ fontSize: 12 }}>
              {t.displayName || t.domain}
              <Typography component='span' fontSize={10} color='#7D92C4' sx={{ ml: 0.75 }}>
                · {t.domain}
              </Typography>
            </MenuItem>
          ))}
        </Select>
      )}
    </Box>
  );
}
