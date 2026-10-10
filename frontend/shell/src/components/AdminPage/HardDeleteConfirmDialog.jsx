import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Dialog, DialogTitle, DialogContent, DialogActions,
  Box, Typography, TextField, Button, Alert, Checkbox, FormControlLabel,
  Divider, CircularProgress, Chip,
} from '@mui/material';
import DeleteForeverIcon from '@mui/icons-material/DeleteForever';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';

/**
 * Type-domain-to-confirm dialog for permanent tenant destruction.
 *
 * Hard-delete is irreversible: it drops the Keycloak realm, the DEDICATED
 * ArcadeDB database, the DEDICATED Fuseki dataset, and (when cascade=true)
 * the SHARED tenant's row data + named graph. The Tenant row itself is
 * HARD-deleted (not soft) so the same UUID cannot be recreated.
 *
 * <p>The dialog mirrors GitHub's repository-delete confirmation:
 * <ol>
 *   <li>A red header and an explicit "this cannot be undone" warning.</li>
 *   <li>A bullet list of every resource that will be released.</li>
 *   <li>A text input that requires typing the tenant's exact domain.</li>
 *   <li>For SHARED tenants, an optional cascade checkbox with its own warning.</li>
 *   <li>The Destroy button stays disabled until typed text === tenant.domain.</li>
 * </ol>
 */
export default function HardDeleteConfirmDialog({ open, tenant, onClose, onConfirmed }) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState('');
  const [cascade, setCascade] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  // Reset every time the dialog opens — paranoid to avoid stale state.
  useEffect(() => {
    if (open) {
      setTyped('');
      setCascade(false);
      setSubmitting(false);
      setError(null);
    }
  }, [open, tenant?.id]);

  if (!tenant) return null;

  const isDedicated = tenant.isolationMode === 'DEDICATED';
  const matches = typed.trim() === tenant.domain;
  const canSubmit = matches && !submitting;

  const handleDestroy = async () => {
    setSubmitting(true);
    setError(null);
    try {
      // The cascade flag is meaningful for SHARED only; ignored server-side
      // for DEDICATED but we send it as configured to keep the API contract
      // honest.
      await onConfirmed({ cascade: !isDedicated && cascade });
    } catch (e) {
      const msg = e?.response?.data?.message
        || e?.response?.data?.error
        || e?.message
        || t('admin.screens.hardDelete.destroyFailed', 'Destroy failed');
      setError(msg);
      setSubmitting(false);
    }
  };

  // Build the resource-release bullet list per isolation mode.
  const resources = isDedicated
    ? [
        { label: t('admin.screens.hardDelete.resKeycloakRealm', 'Keycloak realm'),     detail: tenant.keycloakRealm },
        { label: t('admin.screens.hardDelete.resArcadeDb', 'ArcadeDB database'),  detail: tenant.arcadeDatabase },
        { label: t('admin.screens.hardDelete.resFusekiDataset', 'Fuseki dataset'),     detail: tenant.arcadeDatabase },
        { label: t('admin.screens.hardDelete.resRegistryRow', 'Tenant registry row'), detail: tenant.id },
      ]
    : [
        { label: t('admin.screens.hardDelete.resKeycloakRealm', 'Keycloak realm'),          detail: tenant.keycloakRealm },
        ...(cascade
          ? [
              { label: t('admin.screens.hardDelete.resSharedRows', 'Shared-DB rows'),          detail: `WHERE tenantId = '${tenant.id}' across 14 doc types` },
              { label: t('admin.screens.hardDelete.resNamedGraph', 'Fuseki named graph'),     detail: `<urn:eip:tenant:${tenant.id}>` },
            ]
          : []),
        { label: t('admin.screens.hardDelete.resRegistryRow', 'Tenant registry row'),    detail: tenant.id },
      ];

  return (
    <Dialog open={open} onClose={submitting ? undefined : onClose} maxWidth='sm' fullWidth>
      <DialogTitle sx={{
        bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', fontWeight: 800,
        display: 'flex', alignItems: 'center', gap: 1, fontSize: 16,
      }}>
        <WarningAmberIcon sx={{ color: '#FF6B81' }} />
        {t('admin.screens.hardDelete.title', 'Destroy tenant — this cannot be undone')}
      </DialogTitle>

      <DialogContent sx={{ pt: 3 }}>
        <Box sx={{ mb: 2 }}>
          <Typography fontSize={13} color='#C3D3F2' mb={1.5}>
            {t('admin.screens.hardDelete.aboutToDelete', 'You are about to permanently delete')}{' '}
            <strong style={{ color: '#FFFFFF' }}>{tenant.displayName || tenant.domain}</strong>{' '}
            (<code style={{ fontSize: 12 }}>{tenant.domain}</code>).{' '}
            {t('admin.screens.hardDelete.isolationModeLabel', 'Isolation mode:')}{' '}
            <Chip label={tenant.isolationMode} size='small'
              sx={{
                bgcolor: isDedicated ? 'rgba(255, 184, 77, 0.16)' : 'rgba(91, 157, 255, 0.14)',
                color:   isDedicated ? '#FFB84D' : '#8DBCFF',
                fontWeight: 700, fontSize: 10, ml: 0.5,
              }} />
          </Typography>

          <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>
            <strong>{t('admin.screens.hardDelete.releaseHeading', 'This will release the following resources irreversibly:')}</strong>
            <Box component='ul' sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
              {resources.map(r => (
                <li key={r.label} style={{ marginBottom: 4 }}>
                  <strong>{r.label}</strong>
                  {r.detail && (
                    <Box component='span' sx={{
                      fontFamily: 'monospace', fontSize: 12, ml: 0.5, color: '#C3D3F2',
                    }}>
                      — {r.detail}
                    </Box>
                  )}
                </li>
              ))}
            </Box>
          </Alert>

          {!isDedicated && (
            <Box sx={{
              p: 1.5, mb: 2, border: '1px solid rgba(255, 184, 77, 0.45)', borderRadius: 1.5,
              bgcolor: 'rgba(255, 184, 77, 0.16)',
            }}>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={cascade}
                    onChange={e => setCascade(e.target.checked)}
                    disabled={submitting}
                    sx={{ p: 0.5 }}
                  />
                }
                label={
                  <Box>
                    <Typography fontSize={13} fontWeight={700} color='#FFB84D'>
                      {t('admin.screens.hardDelete.cascadeLabel', "Cascade: also wipe the tenant's row data and named graph")}
                    </Typography>
                    <Typography fontSize={11} color='#FFB84D' mt={0.25}>
                      {t('admin.screens.hardDelete.cascadeHelp', 'Without this, shared-database rows tagged with this tenantId are LEFT BEHIND as orphans. Audit-friendly but recoverable. Tick the box only if the data should truly be gone.')}
                    </Typography>
                  </Box>
                }
                sx={{ alignItems: 'flex-start', m: 0 }}
              />
            </Box>
          )}

          <Divider sx={{ my: 1.5 }} />

          <Typography fontSize={12} color='#C3D3F2' mb={1}>
            {t('admin.screens.hardDelete.confirmPromptBefore', "To confirm, type the tenant's domain")}{' '}
            <Box component='code' sx={{
              fontFamily: 'monospace', fontSize: 12, fontWeight: 700,
              bgcolor: '#0F2450', px: 0.75, py: 0.25, borderRadius: 0.5,
              color: '#FFFFFF',
            }}>{tenant.domain}</Box>{' '}
            {t('admin.screens.hardDelete.confirmPromptAfter', 'below.')}
          </Typography>
          <TextField
            fullWidth
            size='small'
            placeholder={tenant.domain}
            value={typed}
            onChange={e => setTyped(e.target.value)}
            disabled={submitting}
            // Defensive: discourage browsers from auto-completing the field.
            autoComplete='off'
            inputProps={{ 'aria-label': t('admin.screens.hardDelete.confirmInputAria', 'Type the tenant domain to confirm destruction') }}
            sx={{ '& input': { fontFamily: 'monospace', fontSize: 13 } }}
          />
          {typed.length > 0 && !matches && (
            <Typography fontSize={11} color='#FF6B81' mt={0.5}>
              {t('admin.screens.hardDelete.mismatch', "Doesn't match. Expected:")} <code>{tenant.domain}</code>
            </Typography>
          )}
        </Box>

        {error && (
          <Alert severity='error' sx={{ mt: 2, fontSize: 13 }}>
            {error}
          </Alert>
        )}
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} disabled={submitting}
          sx={{ textTransform: 'none', fontSize: 13 }}>
          {t('admin.screens.hardDelete.cancel', 'Cancel')}
        </Button>
        <Button
          onClick={handleDestroy}
          disabled={!canSubmit}
          variant='contained'
          color='error'
          startIcon={submitting ? <CircularProgress size={14} color='inherit' /> : <DeleteForeverIcon />}
          sx={{ textTransform: 'none', fontWeight: 700, fontSize: 13 }}>
          {submitting
            ? t('admin.screens.hardDelete.confirmBusy', 'Destroying…')
            : t('admin.screens.hardDelete.confirm', 'Destroy permanently')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
