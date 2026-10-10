import { useMemo } from 'react';
import { ThemeProvider, createTheme } from '@mui/material';
import { tenantThemeOptions } from './tenantTheme';

/**
 * Applies the tenant MUI theme (font family + size + colours) inside a Module
 * Federation remote.
 *
 * The host shell wraps its own tree in a ThemeProvider, but that theme does NOT
 * reliably cross the federation boundary — a remote loads its own MUI/emotion
 * instance (same reason dashboardLayout and tenantTimezone are threaded through
 * the `context` prop). So each remote's RemoteApp wraps its content in this,
 * passing `themeParams` from `context.themeParams`. The theme is (re)built with
 * the REMOTE's own `createTheme`, so its components actually pick it up.
 *
 * `themeParams` is `{ mode, colors, fontFamily, fontSize }`; when absent the
 * platform defaults apply, so a remote never renders unstyled.
 */
export default function RemoteThemeProvider({ themeParams, children }) {
  const theme = useMemo(
    () => createTheme(tenantThemeOptions(themeParams)),
    [
      themeParams?.mode,
      themeParams?.fontFamily,
      themeParams?.fontSize,
      themeParams?.colors?.primary,
      themeParams?.colors?.secondary,
      themeParams?.colors?.background,
      themeParams?.colors?.text,
    ],
  );
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}
