import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Dialog, DialogTitle, DialogContent, DialogActions,
  Box, Typography, TextField, Button, Alert, Checkbox, FormControlLabel,
  Divider, CircularProgress, Chip,
} from '@mui/material';
import DeleteForeverIcon from '@mui/icons-material/DeleteForever';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';

// Fixed, non-translated confirmation word — typing it is the deliberate gate
// before a multi-tenant hard-delete. Kept literal so it reads the same in
// every language (mirrors GitHub's "type the repo name" pattern).
const CONFIRM_WORD = 'DESTROY';

/**
 * Type-to-confirm dialog for destroying SEVERAL tenants at once from the
 * multi-select action bar.
 *
 * <p>There is no bulk-destroy endpoint; the parent fans out over the per-tenant
 * {@code DELETE /destroy} route. Each tenant is released independently, so a
 * protected tenant (e.g. the demo) simply comes back as a failure in the
 * partial-result report rather than aborting the batch.</p>
 *
 * <p>Unlike the single-tenant dialog (which asks the operator to type the exact
 * domain), typing every domain here would be impractical, so a fixed
 * {@code DESTROY} keyword guards the action instead. The cascade option is
 * offered only when at least one SHARED tenant is in the selection.</p>
 */
export default function BulkDestroyConfirmDialog({ open, tenants = [], onClose, onConfirmed }) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState('');
  const [cascade, setCascade] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  // Reset whenever the dialog (re)opens — avoid carrying stale confirm text.
  useEffect(() => {
    if (open) {
      setTyped('');
      setCascade(false);
      setSubmitting(false);
      setError(null);
    }
  }, [open]);

  const hasShared = tenants.some(t => t.isolationMode !== 'DEDICATED');
  const matches = typed.trim() === CONFIRM_WORD;
  const canSubmit = matches && !submitting && tenants.length > 0;

  const handleDestroy = async () => {
    setSubmitting(true);
    setError(null);
    try {
      // cascade only meaningful for SHARED; harmless for DEDICATED server-side.
      await onConfirmed({ cascade: hasShared && cascade });
      // On success the parent closes us via `open` — no local close needed.
    } catch (e) {
      setError(e?.message || t('admin.screens.bulkDestroy.failed', 'Destroy failed'));
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={submitting ? undefined : onClose} maxWidth='sm' fullWidth>
      <DialogTitle sx={{
        bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', fontWeight: 800,
        display: 'flex', alignItems: 'center', gap: 1, fontSize: 16,
      }}>
        <WarningAmberIcon sx={{ color: '#FF6B81' }} />
        {t('admin.screens.bulkDestroy.title', 'Destroy {{n}} tenant(s) — this cannot be undone', { n: tenants.length })}
      </DialogTitle>

      <DialogContent sx={{ pt: 3 }}>
        <Typography fontSize={13} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.bulkDestroy.intro', 'Each tenant below has its Keycloak realm, dedicated ArcadeDB database and Fuseki dataset released, and its registry row hard-deleted. There is no undo.')}
        </Typography>

        {/* Scrollable list of exactly what will be destroyed. */}
        <Box sx={{
          border: '1px solid #243A72', borderRadius: 1.5, mb: 2,
          maxHeight: 220, overflowY: 'auto',
        }}>
          {tenants.map((tn, i) => (
            <Box key={tn.id} sx={{
              display: 'flex', alignItems: 'center', gap: 1, px: 1.5, py: 1,
              bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
            }}>
              <Typography sx={{ fontFamily: 'monospace', fontSize: 12, color: '#FFFFFF', flex: 1 }}>
                {tn.domain}
              </Typography>
              <Typography fontSize={12} color='#C3D3F2' sx={{ flex: 1 }}>
                {tn.displayName || '—'}
              </Typography>
              <Chip size='small' label={tn.isolationMode}
                sx={{
                  fontSize: 10, fontWeight: 700,
                  bgcolor: tn.isolationMode === 'DEDICATED' ? 'rgba(255, 184, 77, 0.16)' : 'rgba(91, 157, 255, 0.14)',
                  color:   tn.isolationMode === 'DEDICATED' ? '#FFB84D' : '#8DBCFF',
                }} />
              <Chip size='small' label={tn.status}
                sx={{ fontSize: 10, fontWeight: 700, bgcolor: '#0F2450', color: '#C3D3F2' }} />
            </Box>
          ))}
        </Box>

        <Alert severity='warning' sx={{ mb: 2, fontSize: 12 }}>
          {t('admin.screens.bulkDestroy.protectedNote', 'Protected tenants (such as the demo tenant) reject destroy and will be reported as failures — the rest still proceed.')}
        </Alert>

        {hasShared && (
          <Box sx={{
            p: 1.5, mb: 2, border: '1px solid rgba(255, 184, 77, 0.45)', borderRadius: 1.5, bgcolor: 'rgba(255, 184, 77, 0.16)',
          }}>
            <FormControlLabel
              control={
                <Checkbox checked={cascade} onChange={e => setCascade(e.target.checked)}
                  disabled={submitting} sx={{ p: 0.5 }} />
              }
              label={
                <Box>
                  <Typography fontSize={13} fontWeight={700} color='#FFB84D'>
                    {t('admin.screens.bulkDestroy.cascadeLabel', "Cascade: also wipe row data and named graph for SHARED tenants")}
                  </Typography>
                  <Typography fontSize={11} color='#FFB84D' mt={0.25}>
                    {t('admin.screens.bulkDestroy.cascadeHelp', 'Applies to SHARED tenants in the selection only; ignored for DEDICATED. Without it, their shared-database rows are left behind as recoverable orphans.')}
                  </Typography>
                </Box>
              }
              sx={{ alignItems: 'flex-start', m: 0 }}
            />
          </Box>
        )}

        <Divider sx={{ my: 1.5 }} />

        <Typography fontSize={12} color='#C3D3F2' mb={1}>
          {t('admin.screens.bulkDestroy.confirmPromptBefore', 'To confirm, type')}{' '}
          <Box component='code' sx={{
            fontFamily: 'monospace', fontSize: 12, fontWeight: 700,
            bgcolor: '#0F2450', px: 0.75, py: 0.25, borderRadius: 0.5, color: '#FFFFFF',
          }}>{CONFIRM_WORD}</Box>{' '}
          {t('admin.screens.bulkDestroy.confirmPromptAfter', 'below.')}
        </Typography>
        <TextField
          fullWidth
          size='small'
          placeholder={CONFIRM_WORD}
          value={typed}
          onChange={e => setTyped(e.target.value)}
          disabled={submitting}
          autoComplete='off'
          inputProps={{ 'aria-label': t('admin.screens.bulkDestroy.confirmInputAria', 'Type DESTROY to confirm bulk destruction') }}
          sx={{ '& input': { fontFamily: 'monospace', fontSize: 13 } }}
        />
        {typed.length > 0 && !matches && (
          <Typography fontSize={11} color='#FF6B81' mt={0.5}>
            {t('admin.screens.bulkDestroy.mismatch', "Doesn't match. Expected:")} <code>{CONFIRM_WORD}</code>
          </Typography>
        )}

        {error && (
          <Alert severity='error' sx={{ mt: 2, fontSize: 13 }}>
            {error}
          </Alert>
        )}
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} disabled={submitting}
          sx={{ textTransform: 'none', fontSize: 13 }}>
          {t('admin.screens.bulkDestroy.cancel', 'Cancel')}
        </Button>
        <Button
          onClick={handleDestroy}
          disabled={!canSubmit}
          variant='contained'
          color='error'
          startIcon={submitting ? <CircularProgress size={14} color='inherit' /> : <DeleteForeverIcon />}
          sx={{ textTransform: 'none', fontWeight: 700, fontSize: 13 }}>
          {submitting
            ? t('admin.screens.bulkDestroy.confirmBusy', 'Destroying…')
            : t('admin.screens.bulkDestroy.confirm', 'Destroy {{n}} permanently', { n: tenants.length })}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
