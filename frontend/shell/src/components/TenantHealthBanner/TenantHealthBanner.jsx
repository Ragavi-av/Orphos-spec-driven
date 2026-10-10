import { useEffect, useState } from 'react';
import { Alert, Box, IconButton, Slide } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { useTranslation } from 'react-i18next';
import { useTenant } from 'eip-shell-context/AuthProvider';

/**
 * Renders a sticky banner when the tenant's dedicated data store is reporting
 * unavailable. Driven by the {@code orphos:tenant-degraded} window event that
 * axiosAuth dispatches on a 503 with {@code X-Tenant-DB-Status: unavailable}.
 *
 * <p>Auto-clears 60 s after the last degraded event; users can dismiss it
 * manually. We avoid auto-clearing aggressively because a stale banner is
 * less harmful than a flickering one during transient retries.</p>
 */
const AUTO_CLEAR_MS = 60_000;

export default function TenantHealthBanner() {
  const tenant = useTenant();
  const { t } = useTranslation();
  const [state, setState] = useState({ open: false, store: null });

  useEffect(() => {
    let clearTimer;
    const handler = (e) => {
      const store = e.detail?.store || null;
      setState({ open: true, store });
      if (clearTimer) clearTimeout(clearTimer);
      clearTimer = setTimeout(() => setState(s => ({ ...s, open: false })), AUTO_CLEAR_MS);
    };
    window.addEventListener('orphos:tenant-degraded', handler);
    return () => {
      window.removeEventListener('orphos:tenant-degraded', handler);
      if (clearTimer) clearTimeout(clearTimer);
    };
  }, []);

  const dismiss = () => setState(s => ({ ...s, open: false }));

  return (
    <Slide direction='down' in={state.open} mountOnEnter unmountOnExit>
      <Box sx={{ position: 'sticky', top: 0, zIndex: 1300, px: 2, pt: 1 }}>
        <Alert
          severity='warning'
          variant='filled'
          action={
            <IconButton size='small' color='inherit' onClick={dismiss}
              aria-label={t('tenantHealth.dismiss')}>
              <CloseIcon fontSize='small' />
            </IconButton>
          }
          sx={{ borderRadius: 2 }}
        >
          {/* One sentence, one key. Assembling it from a clause plus a trailing
              ". Some panels may…" fragment forced English word order on the
              Japanese banner, which puts the store name and the verb elsewhere. */}
          {/* The store name is optional, and i18next has no conditionals, so the
              parenthetical is composed here and interpolated as one blank-or-not
              fragment rather than needing a second key per branch. */}
          {(() => {
            const store = state.store ? ` (${state.store})` : '';
            return tenant?.domain
              ? t('tenantHealth.degradedTenant', { domain: tenant.domain, store })
              : t('tenantHealth.degraded', { store });
          })()}
        </Alert>
      </Box>
    </Slide>
  );
}
