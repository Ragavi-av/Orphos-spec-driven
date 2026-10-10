import { Box, Typography, Button } from '@mui/material';
import LockIcon from '@mui/icons-material/Lock';
import { useTranslation } from 'react-i18next';
import { useRole } from 'eip-shell-context/useRole';

/**
 * Render-time RBAC gate for the Admin section.
 *
 * <p>Wraps any admin-only subtree. Re-checks {@code isAdmin} on every
 * render so a token refresh that drops the admin role takes effect
 * immediately (e.g. an admin whose role was revoked in Azure Entra ID
 * loses the page on next render, not on next full reload).</p>
 *
 * <p>This is defense-in-depth, not the primary gate - the backend
 * enforces {@code hasRole('admin')} on every {@code /api/admin/**}
 * endpoint. The job here is to give a clean UX so non-admins never
 * see broken empty tabs.</p>
 *
 * <p>If a non-admin somehow reaches an admin tree (state tampering,
 * stale render after role revocation), we surface an explicit "access
 * denied" panel rather than rendering the protected children.</p>
 */
export default function AdminGuard({ children, onBack }) {
  const { t } = useTranslation();
  const { isAdmin, isIngestionAdmin, isDataAiAdmin, isSecopsAdmin, roleDisplay } = useRole();

  // Full admins/platform admins see everything; the scoped ingestion_admin,
  // data_ai_admin and secops_admin roles also reach the Admin section (AdminPage
  // narrows each to its own group — Document Ingestion / Data & AI /
  // Authentication & Authorization respectively).
  if (isAdmin || isIngestionAdmin || isDataAiAdmin || isSecopsAdmin) return children;

  return (
    <Box sx={{
      minHeight: '60vh',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      flexDirection: 'column', gap: 2, p: 4,
    }}>
      <LockIcon sx={{ fontSize: 48, color: '#FF6B81' }} />
      <Typography fontWeight={800} fontSize={20} color='#FFFFFF'>
        {t('admin.screens.guard.accessDenied', 'Access denied')}
      </Typography>
      <Typography fontSize={13} color='#C3D3F2' sx={{ maxWidth: 480, textAlign: 'center' }}>
        {t('admin.screens.guard.restrictedRoles', 'Administration is restricted to users with the Admin, Ingestion Admin, Data & AI Admin or Security & Auth Admin role.')}
        {' '}
        {t('admin.screens.guard.currentRoleHint', 'Your current role is {{role}}. Contact your administrator if you believe this is in error - role assignments are managed in Azure Entra ID and take effect on your next sign-in.', { role: roleDisplay })}
      </Typography>
      {onBack && (
        <Button variant='outlined' size='small' onClick={onBack}
          sx={{ textTransform: 'none', mt: 1 }}>
          {t('admin.screens.guard.returnToDashboard', 'Return to dashboard')}
        </Button>
      )}
    </Box>
  );
}
