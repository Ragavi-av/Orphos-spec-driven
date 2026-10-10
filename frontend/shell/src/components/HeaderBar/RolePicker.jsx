import { Box, Select, MenuItem, Typography } from '@mui/material';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import { useTranslation } from 'react-i18next';
import { useRole } from 'eip-shell-context/useRole';
import { useRoleLabels } from 'eip-shell-context/RolesProvider';
import { setActiveRole } from 'eip-shell-context/activeRole';

/**
 * Role picker shown only when the user holds 2+ dashboard-affecting roles.
 *
 * <p>Selecting a role updates {@code activeRole} in localStorage and fires
 * the {@code orphos:active-role} event; every {@code useRole()} consumer
 * re-renders, so the tab set, copilot visibility, and admin-page access
 * all switch together.</p>
 */
export default function RolePicker() {
  const { t } = useTranslation();
  const { availableRoles, activeRole } = useRole();
  const { aliases, roleLabel: aliasLabel } = useRoleLabels();

  if (!availableRoles || availableRoles.length < 2) return null;

  // Tenant alias wins; otherwise the translated built-in label (falls back to
  // the alias-provider's built-in map for custom roles).
  const roleLabel = r => aliases[r] || t(`roles.${r}`, aliasLabel(r));

  return (
    <Box sx={{
      display: 'flex', alignItems: 'center', gap: 0.75,
      px: '10px', py: '4px', borderRadius: 999,
      bgcolor: '#122349',
      border: '1px solid #2E4A8C',
    }}>
      <SwapHorizIcon sx={{ fontSize: 14, color: '#5B9DFF' }} />
      <Select
        value={activeRole || availableRoles[0]}
        onChange={e => setActiveRole(e.target.value)}
        variant='standard'
        disableUnderline
        renderValue={v => (
          <Typography fontSize={12} fontWeight={600} color='#A8BBE6'
            sx={{ whiteSpace: 'nowrap' }}>
            {t('header.actingAs', { role: roleLabel(v) })}
          </Typography>
        )}
        sx={{
          '& .MuiSelect-select': { py: 0, pr: '20px !important' },
          '& .MuiSelect-icon':   { fontSize: 16, color: '#5B9DFF' },
        }}
      >
        {availableRoles.map(r => (
          <MenuItem key={r} value={r} sx={{ fontSize: 12 }}>
            {roleLabel(r)}
          </MenuItem>
        ))}
      </Select>
    </Box>
  );
}
