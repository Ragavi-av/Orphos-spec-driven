import { Box, Typography, Button, Paper, Divider } from '@mui/material';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import EmailOutlinedIcon from '@mui/icons-material/EmailOutlined';
import { Trans, useTranslation } from 'react-i18next';
import { useAuth } from 'eip-shell-context/AuthProvider';
import { tenantRedirectUri } from 'eip-shell-context/tenantResolver';

export default function GuestPage({ userFullName, userEmail }) {
  const keycloak = useAuth();
  const { t } = useTranslation();

  return (
    <Box sx={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      bgcolor: '#0F2450',
    }}>
      <Paper elevation={0} sx={{
        maxWidth: 480,
        width: '100%',
        mx: 2,
        p: 5,
        borderRadius: 3,
        border: '1px solid #243A72',
        textAlign: 'center',
      }}>

        {/* Lock icon */}
        <Box sx={{
          width: 72, height: 72,
          borderRadius: '50%',
          bgcolor: 'rgba(91, 157, 255, 0.14)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          mx: 'auto', mb: 3,
        }}>
          <LockOutlinedIcon sx={{ fontSize: 36, color: '#8DBCFF' }} />
        </Box>

        {/* Title */}
        <Typography fontWeight={700} fontSize={22} color='#FFFFFF' mb={1}>
          {t('guest.title')}
        </Typography>

        {/* Personalised greeting. <Trans> rather than t(): the name is bolded
            mid-sentence, and Japanese puts it in a different position, so the
            translation has to own where the <strong> goes. */}
        {userFullName && (
          <Typography fontSize={14} color='#C3D3F2' mb={2}>
            <Trans i18nKey='guest.greeting' values={{ name: userFullName }}
              components={{ strong: <strong /> }} />
          </Typography>
        )}

        <Divider sx={{ my: 2.5 }} />

        {/* Instruction */}
        <Box sx={{ bgcolor: '#0F2450', borderRadius: 2, p: 2.5, mb: 3, textAlign: 'left' }}>
          <Typography fontSize={13} color='#C3D3F2' lineHeight={1.8}>
            {t('guest.instructions')}
          </Typography>
          <Box component='ul' sx={{ mt: 1.5, mb: 0, pl: 2.5 }}>
            <Typography component='li' fontSize={13} color='#C3D3F2'>{t('guest.needName')}</Typography>
            <Typography component='li' fontSize={13} color='#C3D3F2'>{t('guest.needRole')}</Typography>
            {userEmail && (
              <Typography component='li' fontSize={13} color='#C3D3F2'>
                {t('guest.needEmail')} <strong>{userEmail}</strong>
              </Typography>
            )}
          </Box>
        </Box>

        {/* Contact admin button */}
        <Button
          variant='contained'
          startIcon={<EmailOutlinedIcon />}
          href='mailto:admin@orphos.io?subject=Orphos%20Access%20Request'
          sx={{
            bgcolor: '#2F6FF0',
            borderRadius: 2,
            textTransform: 'none',
            fontWeight: 600,
            px: 3,
            py: 1.2,
            '&:hover': { bgcolor: '#2C3868' },
          }}
        >
          {t('guest.contactAdmin')}
        </Button>

        {/* Sign out link */}
        <Box mt={3}>
          <Typography
            component='span'
            fontSize={12}
            color='#7D92C4'
            sx={{ cursor: 'pointer', '&:hover': { color: '#C3D3F2' } }}
            onClick={() => keycloak.logout({ redirectUri: tenantRedirectUri() })}
          >
            {t('guest.signOut')}
          </Typography>
        </Box>
      </Paper>
    </Box>
  );
}
