// Single source of truth for the tenant MUI theme — shared by the host shell
// AND every Module Federation remote so typography (font family + size) and
// colours are identical everywhere, not just in the shell.
//
// This module deliberately does NOT import @mui/material. It returns a plain
// `createTheme(...)` OPTIONS object so each consumer can call its OWN
// `createTheme` (the shell's, or the remote's bundled copy). That matters
// because a remote bundles its own copy of eip-shell-context / MUI (federation
// singletons are not reliable here — see RemoteApp notes), so a theme object
// built with the shell's MUI instance would not drive the remote's components.
// Passing plain params + building the theme locally on each side sidesteps that.
//
// The PLATFORM DEFAULT is the Orphos dark theme (orphosTheme.js, derived from
// the Orphos screens deck). A tenant that supplies its own branding colours
// still overrides it — and a tenant that explicitly asks for `mode: "light"`
// gets the legacy light palette below, so existing light-branded tenants are
// not force-migrated.

import { orphosThemeOptions, ORPHOS_FONT_FAMILY, ORPHOS_TOKENS, ORPHOS_COLORS } from './orphosTheme';

export const DEFAULT_FONT_FAMILY = ORPHOS_FONT_FAMILY;
export const DEFAULT_FONT_SIZE = 13;

/** Platform default — the Orphos dark palette. */
export const DEFAULT_PALETTE = {
  mode: 'dark',
  background: ORPHOS_TOKENS.bg.canvas,
  primary:    ORPHOS_COLORS.primary,
  secondary:  ORPHOS_COLORS.primaryAlt,
  text:       ORPHOS_TOKENS.text.primary,
};

/** Legacy light palette — retained for tenants branded before the Orphos theme. */
export const LIGHT_PALETTE = {
  mode: 'light',
  background: '#f5f6fa',
  primary: '#2563eb',
  secondary: '#1e293b',
  text: '#1e293b',
};

const LEGACY_LIGHT_FONT = '"Inter", "Noto Sans JP", "Roboto", sans-serif';

/**
 * Build the MUI theme options for a tenant.
 *
 * @param {object} [params]
 * @param {string} [params.mode]        "light" | "dark" (default: dark / Orphos)
 * @param {object} [params.colors]      { primary, secondary, background, text }
 * @param {string} [params.fontFamily]  tenant font stack (blank → platform default)
 * @param {number} [params.fontSize]    tenant base font size (0/blank → platform default)
 * @returns {object} options for createTheme()
 */
export function tenantThemeOptions(params) {
  const { mode, colors, fontFamily, fontSize } = params || {};

  // Only an EXPLICIT light request opts out of the Orphos theme; absent/blank
  // mode means "platform default", which is now dark.
  if (mode === 'light') return legacyLightThemeOptions(params);

  return orphosThemeOptions({ colors, fontFamily, fontSize });
}

/** The pre-Orphos light theme, unchanged, for tenants that ask for it. */
function legacyLightThemeOptions(params) {
  const { colors, fontFamily, fontSize } = params || {};
  const c = colors || {};
  const fam = (typeof fontFamily === 'string' && fontFamily.trim())
    ? fontFamily.trim() : LEGACY_LIGHT_FONT;
  const size = Number(fontSize) > 0 ? Number(fontSize) : DEFAULT_FONT_SIZE;
  return {
    palette: {
      mode: 'light',
      background: { default: c.background || LIGHT_PALETTE.background },
      primary:    { main:    c.primary    || LIGHT_PALETTE.primary    },
      secondary:  { main:    c.secondary  || LIGHT_PALETTE.secondary  },
      text:       { primary: c.text       || LIGHT_PALETTE.text       },
    },
    typography: { fontFamily: fam, fontSize: size },
    components: {
      MuiPaper: { styleOverrides: { root: { borderRadius: 12 } } },
      MuiChip:  { styleOverrides: { root: { borderRadius: 6, fontSize: 11 } } },
      // Mirrors the Orphos theme: no overlay may lock body scroll, because the
      // app reserves the scrollbar gutter itself and MUI's compensation then
      // double-counts, shifting the layout ~12px sideways. See the longer note
      // in orphosTheme.js.
      MuiModal:   { defaultProps: { disableScrollLock: true } },
      MuiDialog:  { defaultProps: { disableScrollLock: true } },
      MuiDrawer:  { defaultProps: { disableScrollLock: true } },
      MuiPopover: { defaultProps: { disableScrollLock: true } },
      MuiMenu:    { defaultProps: { disableScrollLock: true } },
    },
  };
}
