import { useTranslation } from 'react-i18next';
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, Box, Typography, Alert, Chip,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';

/**
 * Soft-delete confirmation dialog. Spells out what soft-delete does and does
 * NOT do — replacing the previous {@code window.confirm} so admins are not
 * surprised when a "deleted" tenant's data is still queryable, or that the
 * Keycloak realm + databases stay in place.
 */
export default function SoftDeleteConfirmDialog({ open, tenant, onClose, onConfirm, busy }) {
  const { t } = useTranslation();
  if (!tenant) return null;
  const isShared = tenant.isolationMode === 'SHARED';

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} maxWidth='sm' fullWidth>
      <DialogTitle sx={{ fontWeight: 800, fontSize: 16, display: 'flex', alignItems: 'center', gap: 1 }}>
        <DeleteOutlineIcon sx={{ color: '#C3D3F2' }} />
        {t('admin.screens.softDelete.title', 'Soft-delete tenant')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 2 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box>
            <Typography fontSize={13} color='#FFFFFF' mb={0.5}>{t('admin.screens.softDelete.aboutTo', 'You are about to soft-delete:')}</Typography>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography fontSize={14} fontWeight={800} fontFamily='monospace' color='#FFFFFF'>
                {tenant.domain}
              </Typography>
              <Chip size='small' label={tenant.isolationMode}
                sx={{ fontSize: 10, fontWeight: 700,
                  bgcolor: isShared ? 'rgba(91, 157, 255, 0.14)' : 'rgba(255, 184, 77, 0.16)',
                  color:   isShared ? '#8DBCFF' : '#FFB84D' }} />
            </Box>
            {tenant.displayName && (
              <Typography fontSize={12} color='#C3D3F2'>{tenant.displayName}</Typography>
            )}
          </Box>

          <Alert severity='info' sx={{ fontSize: 12 }}>
            <strong>{t('admin.screens.softDelete.reversibleLead', 'This is reversible.')}</strong>{' '}
            {t('admin.screens.softDelete.reversibleBody', 'The tenant row is marked DELETED; an operator can restore it later.')}
          </Alert>

          <Box>
            <Typography fontSize={12} fontWeight={700} color='#FFFFFF' mb={0.75}>
              {t('admin.screens.softDelete.doesHeading', 'What soft-delete does:')}
            </Typography>
            <Box component='ul' sx={{ pl: 2.5, m: 0, fontSize: 12, color: '#C3D3F2', lineHeight: 1.7 }}>
              <li>{t('admin.screens.softDelete.doesStatus', 'Status set to DELETED — new users cannot log in.')}</li>
              <li>{t('admin.screens.softDelete.doesDomainFree', 'The domain becomes free to reassign to a different tenant.')}</li>
              <li>{t('admin.screens.softDelete.doesCaches', 'Caches (tenant lookup, allowed-issuers) are evicted.')}</li>
            </Box>
          </Box>

          <Box>
            <Typography fontSize={12} fontWeight={700} color='#FFFFFF' mb={0.75}>
              {t('admin.screens.softDelete.doesNotHeading', 'What soft-delete does not do:')}
            </Typography>
            <Box component='ul' sx={{ pl: 2.5, m: 0, fontSize: 12, color: '#C3D3F2', lineHeight: 1.7 }}>
              <li>{t('admin.screens.softDelete.notRealm', 'The Keycloak realm {{realm}} is left in place.', { realm: tenant.keycloakRealm })}</li>
              {isShared ? (
                <li>
                  {t('admin.screens.softDelete.notSharedRows', 'Tenant-scoped rows in {{db}} remain (orphaned but auditable) — use Destroy with cascade to wipe them.', { db: tenant.arcadeDatabase })}
                </li>
              ) : (
                <li>
                  {t('admin.screens.softDelete.notDedicatedDb', 'The dedicated ArcadeDB {{db}} and matching Fuseki dataset are left in place — use Destroy to release them.', { db: tenant.arcadeDatabase })}
                </li>
              )}
              <li>{t('admin.screens.softDelete.notSecrets', 'External SSO/SMTP integrations keep their existing secrets in OpenBao.')}</li>
            </Box>
          </Box>
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy} sx={{ textTransform: 'none', fontSize: 13 }}>
          {t('admin.screens.softDelete.cancel', 'Cancel')}
        </Button>
        <Button onClick={onConfirm} variant='contained' color='warning' disabled={busy}
          startIcon={<DeleteOutlineIcon />}
          sx={{ textTransform: 'none', fontWeight: 700, fontSize: 13 }}>
          {busy
            ? t('admin.screens.softDelete.confirmBusy', 'Soft-deleting…')
            : t('admin.screens.softDelete.confirm', 'Soft-delete')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
