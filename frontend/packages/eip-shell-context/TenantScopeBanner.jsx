import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Box, Typography } from '@mui/material';
import BadgeIcon from '@mui/icons-material/Badge';
import { useTenant } from 'eip-shell-context/AuthProvider';
import { getActingTenant, onActingTenantChange } from 'eip-shell-context/actingTenant';

/**
 * Shows the currently-scoped tenant at the top of a tenant-scoped admin tab.
 *
 * <p>The Branding / Email / Auth tabs all hit endpoints filtered by
 * X-Tenant-Id (acting tenant for platform admins, JWT tenant_id otherwise) —
 * without this banner, an admin who wears the platform-admin hat could not
 * tell which tenant's config they are editing. The displayed tenant follows
 * the same precedence as the axios interceptor: acting tenant wins when set,
 * else the JWT-resolved tenant.</p>
 */
export default function TenantScopeBanner({ resource }) {
  const { t } = useTranslation();
  const jwtTenant = useTenant();
  const [acting, setActing] = useState(() => getActingTenant());
  useEffect(() => onActingTenantChange(setActing), []);

  // Effective tenant — acting override wins, else JWT-derived.
  const tenant = acting?.tenantId
    ? {
        tenantId:    acting.tenantId,
        displayName: acting.displayName || null,
        domain:      acting.domain || acting.tenantId,
      }
    : jwtTenant;

  if (!tenant) return null;
  const label = tenant.displayName || tenant.domain;
  return (
    <Alert
      severity={acting ? 'warning' : 'info'}
      icon={<BadgeIcon fontSize='small' />}
      sx={{
        mb: 2,
        py: 0.5,
        bgcolor: acting ? 'rgba(255, 184, 77, 0.16)' : 'rgba(91, 157, 255, 0.14)',
        borderColor: acting ? 'rgba(255, 184, 77, 0.45)' : 'rgba(91, 157, 255, 0.42)',
        '& .MuiAlert-icon': { color: acting ? '#FFB84D' : '#8DBCFF' },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Typography fontSize={12} color={acting ? '#FFB84D' : '#8DBCFF'}>
          {acting
            ? (resource
                ? t('admin.screens.tenantScope.actingOnResourceFor', 'Acting on {{resource}} for', { resource })
                : t('admin.screens.tenantScope.actingOnConfigFor', 'Acting on configuration for'))
            : (resource
                ? t('admin.screens.tenantScope.editingResourceFor', 'Editing {{resource}} for', { resource })
                : t('admin.screens.tenantScope.editingConfigFor', 'Editing configuration for'))}
        </Typography>
        <Typography fontSize={12} fontWeight={800}
          color={acting ? '#FFB84D' : '#8DBCFF'}>
          {label}
        </Typography>
        {tenant.domain && tenant.displayName && (
          <Typography fontSize={10} color='#C3D3F2' fontFamily='monospace'>
            ({tenant.domain})
          </Typography>
        )}
      </Box>
    </Alert>
  );
}
