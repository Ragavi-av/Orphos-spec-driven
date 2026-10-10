import { Box, ButtonBase, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { TIME_FORMATS, useTimeFormat } from 'eip-shell-context/timeFormat';

/**
 * Clock-format picker — Auto / 12h / 24h.
 *
 * Lives in the header's avatar menu rather than on the header band itself. It
 * is a set-once preference, not something a reader touches during a session, so
 * it does not earn permanent space beside the language pills — and the band is
 * already carrying the role picker, the language control, the live clock,
 * alerts and the avatar.
 *
 * `Auto` follows the workspace country's convention (India 6:00 am, Japan
 * 06:00) and is the default. The explicit options are for readers that suits
 * badly; Auto is how they get back, which is why it stays on screen rather than
 * being a hidden initial state.
 *
 * Sits inside a MUI <Menu>, which by default moves focus to the first focusable
 * child and closes on Enter/Space from a MenuItem. These are ButtonBases in a
 * plain Box, not MenuItems, so choosing a format leaves the menu open — a
 * reader comparing 12h against 24h wants to see the clock change without
 * reopening the menu each time.
 */
export default function TimeFormatToggle({ colors }) {
  const { t } = useTranslation();
  const { format, setTimeFormat } = useTimeFormat();

  const label = (code) => {
    if (code === 'auto') return t('header.timeFormat.auto', 'Auto');
    if (code === '12') return t('header.timeFormat.h12', '12h');
    return t('header.timeFormat.h24', '24h');
  };

  return (
    <Box sx={{ px: 0.5, py: 0.75 }}>
      <Typography
        component='div'
        id='time-format-label'
        fontSize={11.5}
        color={colors.muted}
        mb={0.75}
      >
        {t('header.timeFormat.label', 'Clock')}
      </Typography>
      <Box
        role='group'
        aria-labelledby='time-format-label'
        sx={{
          display: 'flex', gap: '2px', p: '2px',
          bgcolor: 'rgba(18, 35, 73, 0.85)',
          border: `1px solid ${colors.menuLine}`,
          borderRadius: 999,
        }}
      >
        {TIME_FORMATS.map((code) => {
          const active = code === format;
          return (
            <ButtonBase
              key={code}
              onClick={() => setTimeFormat(code)}
              aria-pressed={active}
              sx={{
                flex: 1, px: '8px', py: '4px', borderRadius: 999,
                fontSize: 11.5, fontWeight: 600, lineHeight: 1.2,
                // Same contrast floor as the language pills: these are
                // interactive labels, not captions, so the inactive state is
                // held at #A8BBE6 rather than the dimmer slate.
                color: active ? '#FFFFFF' : '#A8BBE6',
                bgcolor: active ? '#2F6FF0' : 'transparent',
                transition: 'background-color 120ms ease, color 120ms ease',
                '&:hover': {
                  bgcolor: active ? '#2F6FF0' : 'rgba(168, 187, 230, 0.12)',
                  color: '#FFFFFF',
                },
              }}
            >
              {label(code)}
            </ButtonBase>
          );
        })}
      </Box>
    </Box>
  );
}
