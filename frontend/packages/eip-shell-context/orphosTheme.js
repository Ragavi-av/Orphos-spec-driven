// Orphos design tokens.
//
// SURFACES, INTERACTIVE BLUES AND STATUS come from the "Orphos Dashboard v3"
// design canvas (artboard 1a, "Signal first"), which supersedes the earlier
// Orphos.pptx navy family. Read as literals off the artboard:
//
//   0B1533  canvas               0C193C  header band
//   152652  card / panel         0F2450  nested / deep panel
//   243A72  hairline             2E4A8C  strong outline
//   EAF0FF  primary text         C3D3F2  secondary
//   A8BBE6  bright caption       7D92C4  slate caption      4D6299  hint
//   2F6FF0  primary blue         5B9DFF  light blue
//   3DDC97  good   FFB84D  warn   FF6B81  bad
//
// The predecessor palette (0E1330 / 1E2761 / 0563C1 / E8A33D) is gone. It came
// from the deck's title slides, which had no interactive states to draw; the
// canvas is a full dashboard and specifies its own. Gold is no longer the
// accent OR the only warning — amber FFB84D carries warning, and blue carries
// every interactive affordance.
//
// A full application still needs states the canvas did not have to draw (hover,
// focus, pressed, tinted status callouts). Those are DERIVED, never invented:
// the hue is always a canvas colour and only the alpha varies. See
// ORPHOS_TOKENS.
//
// Like tenantTheme.js this module deliberately does NOT import @mui/material —
// it exports plain values plus a createTheme() OPTIONS object, so the shell and
// every Module Federation remote can build an identical theme with their own
// bundled MUI copy.

export const ORPHOS_COLORS = {
  // Surfaces — design canvas
  canvas:      '#0B1533',  // page ground
  surface:     '#152652',  // card + panel fill
  surfaceDeep: '#0F2450',  // nested card, drawer, composer, drill-down
  // Sits BELOW the card it is inside — the artboard insets the greeting band's
  // task list into a well that reads as recessed rather than raised. It is the
  // only surface darker than its parent, which is why it is not `surfaceDeep`
  // (that one is *raised* above the canvas, despite the name).
  surfaceInset: '#0D1C44',
  band:        '#1D3672',  // callout / raised band / chat bubble
  header:      '#0C193C',  // sticky header band, tab strip ground

  // Blues — the canvas's interactive family
  primary:    '#2F6FF0',   // filled buttons, active pill, active tab
  primaryAlt: '#5B9DFF',   // secondary graphic, sparkline "on target"
  accent:     '#8DBCFF',   // links, AI attributions, focus ring

  // Type — design canvas
  textPrimary: '#EAF0FF',
  textMuted:   '#C3D3F2',  // secondary body copy
  textBright:  '#A8BBE6',  // captions on dark panels, quiet buttons
  textGrey:    '#7D92C4',  // slate — section labels, metadata
  textDim:     '#4D6299',  // hint text and decorative marks only (see note)
  textOnLight: '#0B1533',  // on a light or saturated fill

  // Assistant surface. The Ask Orphos rail runs a half-step violet off the
  // dashboard navy — same value, different hue — so the co-pilot reads as a
  // different KIND of surface rather than as another panel of the dashboard.
  assist:       '#161C46',  // rail + panel fill
  assistRaised: '#1D2456',  // its header strip
  assistLine:   '#2C3A72',  // its edge against the dashboard

  // Lines
  line:       '#243A72',   // hairline between rows and around cards
  lineStrong: '#2E4A8C',   // outline on inputs, drawers, quiet buttons

  // Status — the canvas's TONE map
  critical:     '#FF6B81',
  criticalDark: '#E0344A',
  warning:      '#FFB84D',
  success:      '#3DDC97',
  highlight:    '#FFFF00',
};

// ── Derived values ────────────────────────────────────────────────────────
// Contrast against the card fill #152652, per WCAG 2.1 relative luminance.
// Recomputed for this palette — #152652 is DARKER than the previous #1E2761,
// so every ratio moved up:
//
//   #EAF0FF on #152652 → 15.29:1  ✔
//   #C3D3F2 on #152652 → 10.83:1  ✔
//   #A8BBE6 on #152652 →  8.06:1  ✔
//   #FFB84D on #152652 →  8.71:1  ✔
//   #3DDC97 on #152652 →  9.06:1  ✔
//   #8DBCFF on #152652 →  8.19:1  ✔
//   #FF6B81 on #152652 →  5.31:1  ✔
//   #7D92C4 on #152652 →  4.73:1  ✔
//   #4D6299 on #152652 →  2.41:1  ✘ fails AA for text
//
// `textDim` is the one that fails, and it is kept because the canvas uses it
// deliberately for decorative marks and for hint copy that always restates
// something already stated in an AA-passing tone beside it ("auto-ordered by
// distance from target" under a labelled section, a "›" chevron). It must NOT
// carry information of its own — reach for `textGrey` the moment it would.
//
// Critical is the one status hue that has to work as text: at 5.31:1 it does,
// so unlike the previous palette there is no separate text tone. The export is
// kept so callers that reference it do not break.
export const ORPHOS_CRITICAL_TEXT = '#FF6B81';

const rgba = (hex, a) => {
  const h = hex.replace('#', '');
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
};

/**
 * Semantic tokens — what components should reference. Reaching for
 * ORPHOS_COLORS directly is fine for one-off graphics; anything that expresses
 * a ROLE (a surface level, a status, an interactive state) belongs here so the
 * whole app moves together.
 */
export const ORPHOS_TOKENS = {
  bg: {
    canvas:  ORPHOS_COLORS.canvas,       // page background
    surface: ORPHOS_COLORS.surface,      // cards, rows, inputs
    deep:    ORPHOS_COLORS.surfaceDeep,  // drawers, composers, nested cards
    inset:   ORPHOS_COLORS.surfaceInset, // a well recessed into a card
    raised:  ORPHOS_COLORS.band,         // callouts, active section, chat bubble
    header:  ORPHOS_COLORS.header,       // sticky header band, tab strip ground
    assist:       ORPHOS_COLORS.assist,       // Ask Orphos rail + panel
    assistRaised: ORPHOS_COLORS.assistRaised, // its header strip
    overlay: rgba('#000000', 0.6),       // modal scrim
  },
  text: {
    primary:  ORPHOS_COLORS.textPrimary,
    secondary:ORPHOS_COLORS.textMuted,
    bright:   ORPHOS_COLORS.textBright,
    muted:    ORPHOS_COLORS.textGrey,
    // Decorative / restated hints only — 2.41:1 on the card fill. See the
    // contrast note above before using this for anything a user must read.
    hint:     ORPHOS_COLORS.textDim,
    link:     ORPHOS_COLORS.accent,
    onFilled: '#FFFFFF',                 // on primary/band fills — 5.3:1
  },
  line: {
    subtle: ORPHOS_COLORS.line,          // hairline between rows and cards
    strong: ORPHOS_COLORS.lineStrong,    // input + drawer + quiet-button outline
    assist: ORPHOS_COLORS.assistLine,    // edge of the Ask Orphos surface
    focus:  ORPHOS_COLORS.accent,        // focus ring
  },
  state: {
    hover:    rgba(ORPHOS_COLORS.primaryAlt, 0.12),
    selected: rgba(ORPHOS_COLORS.primary, 0.24),
    pressed:  rgba(ORPHOS_COLORS.primaryAlt, 0.18),
    disabled: rgba('#FFFFFF', 0.38),
  },
  // Each status carries a foreground for text, a tint for the callout fill and
  // a border. Tints are the status hue at low alpha over whatever sits behind.
  status: {
    critical: { fg: ORPHOS_COLORS.critical, graphic: ORPHOS_COLORS.critical,
                bg: rgba(ORPHOS_COLORS.critical, 0.14), border: rgba(ORPHOS_COLORS.critical, 0.42) },
    warning:  { fg: ORPHOS_COLORS.warning, graphic: ORPHOS_COLORS.warning,
                bg: rgba(ORPHOS_COLORS.warning, 0.14), border: rgba(ORPHOS_COLORS.warning, 0.42) },
    success:  { fg: ORPHOS_COLORS.success, graphic: ORPHOS_COLORS.success,
                bg: rgba(ORPHOS_COLORS.success, 0.14), border: rgba(ORPHOS_COLORS.success, 0.42) },
    // The canvas's "Orphos said" chip — a saturated blue tile rather than a
    // wash, so info carries its own fill and border rather than an alpha tint.
    info:     { fg: ORPHOS_COLORS.accent, graphic: ORPHOS_COLORS.primaryAlt,
                bg: '#123A6B', border: '#245C9E' },
    neutral:  { fg: ORPHOS_COLORS.textBright, graphic: ORPHOS_COLORS.textGrey,
                bg: ORPHOS_COLORS.surfaceDeep, border: ORPHOS_COLORS.lineStrong },
  },
};

/** Shape + spacing rhythm read off the mockups. */
export const ORPHOS_SHAPE = {
  radiusCard: 12,
  radiusPill: 8,
  radiusButton: 8,
  radiusChip: 6,
  barHeight: 6,
  cardPadding: 20,
};

// ── Motion ────────────────────────────────────────────────────────────────
// The v3 JP artboard animates a section BOTH ways. That is the part worth
// copying: a panel that fades in but vanishes on close reads as a glitch,
// because the eye is given no motion to follow and the layout appears to jump
// on its own. So every collapsible surface here has a matching pair, and the
// exit runs slightly faster than the entrance (200ms vs 240ms) — leaving is
// allowed to feel brisker than arriving.
//
// Three distances, matched to how far the thing being revealed actually sits
// from where it came from, rather than one generic fade:
//
//   grow  (6px up)    small in-place reveals — menus, popovers, chips
//   rise  (10px up)   a section opening in the vertical flow
//   slide (14px right) a panel arriving from the edge — the Ask Orphos rail
//
// Keyframes are injected once via MuiCssBaseline (below) so any component —
// shell or Module Federation remote — can name them in an `animation` without
// redeclaring them and without a styled() wrapper.
export const ORPHOS_MOTION = {
  enterMs: 240,
  exitMs: 200,
  // Menus and popovers are smaller and closer to the click, so they read as
  // sluggish at the section duration.
  popMs: 180,
  easing: 'ease',
  keyframes: {
    grow:  { in: 'orphGrow',  out: 'orphGrowOut'  },
    rise:  { in: 'orphRise',  out: 'orphRiseOut'  },
    slide: { in: 'orphSlide', out: 'orphSlideOut' },
  },
};

/**
 * `animation` shorthand for one half of a motion pair.
 *
 * @param {'grow'|'rise'|'slide'} name  which distance to travel
 * @param {boolean} [closing=false]     true while the exit is playing
 * @param {number}  [ms]                override the duration (see popMs)
 * @returns {string} e.g. "orphRise 240ms ease both"
 *
 * `both` matters: without it the element snaps back to its pre-animation
 * offset on the last frame of the exit, which is the flicker this replaced.
 */
export function orphosAnim(name, closing = false, ms) {
  const pair = ORPHOS_MOTION.keyframes[name] || ORPHOS_MOTION.keyframes.grow;
  const dur = ms != null ? ms : (closing ? ORPHOS_MOTION.exitMs : ORPHOS_MOTION.enterMs);
  return `${closing ? pair.out : pair.in} ${dur}ms ${ORPHOS_MOTION.easing} both`;
}

// TYPE, like the surfaces above, now comes from the "Orphos Dashboard v3"
// canvas: it sets `font-family: Inter, system-ui, sans-serif` on the artboard
// root and on every control inside it, and loads Inter 400–800 from Google
// Fonts. Loaded as a webfont in shell/index.html.
//
// This replaces the Orphos.pptx pairing (Cambria headings / Calibri body, via
// the metric-compatible Caladea and Carlito). That pairing came from the deck's
// title slides; the canvas is the full dashboard and specifies its own type,
// the same reason its palette superseded the deck's navy family above.
//
// One family, not a serif/sans pair — the canvas draws headings as a HEAVIER
// WEIGHT of the same sans (700–800) rather than a different face, so
// ORPHOS_HEADING_FAMILY is now the body stack and headings carry weight
// instead. It is still exported: callers reference it, and keeping it as a
// separate name leaves room for a display face later.
//
// CJK faces sit behind Inter — Inter carries no CJK glyphs, so Japanese would
// otherwise fall through to whatever the browser picks. Font fallback is
// per-GLYPH, so Latin still renders in Inter on a Japanese board; only the
// characters Inter cannot draw reach the faces after it.
//
// The v3 JP artboard names the platform faces before the webfont —
// 'Hiragino Kaku Gothic ProN' (macOS) and 'Yu Gothic' (Windows) — because a
// reader on either OS gets their system's own Japanese face, which is
// hinted for that OS's rasteriser and already resident. Noto Sans JP is the
// backstop for everyone else and is the one we actually ship (shell/index.html).
//
// These MUST precede system-ui: on Windows system-ui resolves to Segoe UI,
// whose limited kana coverage would otherwise win over Yu Gothic for the
// characters it does have and mix two faces inside one Japanese word.
export const ORPHOS_FONT_FAMILY =
  '"Inter", "Hiragino Kaku Gothic ProN", "Yu Gothic", "Noto Sans JP", system-ui, "Helvetica Neue", Arial, sans-serif';
export const ORPHOS_HEADING_FAMILY = ORPHOS_FONT_FAMILY;

/**
 * MUI createTheme() options for the Orphos theme.
 *
 * @param {object}  [params]
 * @param {object}  [params.colors]      tenant overrides { primary, secondary, background, text }
 * @param {string}  [params.fontFamily]  tenant override (blank → Inter stack)
 * @param {number}  [params.fontSize]    tenant base size (0/blank → 13)
 * @returns {object} options for createTheme()
 */
export function orphosThemeOptions(params) {
  const { colors, fontFamily, fontSize } = params || {};
  const o = colors || {};
  const fam = (typeof fontFamily === 'string' && fontFamily.trim())
    ? fontFamily.trim() : ORPHOS_FONT_FAMILY;
  const size = Number(fontSize) > 0 ? Number(fontSize) : 13;
  const c = ORPHOS_COLORS;
  const T = ORPHOS_TOKENS;

  return {
    palette: {
      mode: 'dark',
      background: { default: o.background || T.bg.canvas, paper: T.bg.surface },
      primary:    { main: o.primary || c.primary, dark: c.band, light: c.accent,
                    contrastText: T.text.onFilled },
      secondary:  { main: o.secondary || c.accent },
      error:      { main: c.critical, light: ORPHOS_CRITICAL_TEXT, dark: c.criticalDark },
      warning:    { main: c.warning },
      success:    { main: c.success },
      info:       { main: c.primaryAlt },
      divider:    T.line.subtle,
      action: {
        hover: T.state.hover,
        selected: T.state.selected,
        disabled: T.state.disabled,
      },
      text: {
        primary: o.text || T.text.primary,
        secondary: T.text.secondary,
        disabled: T.text.muted,
      },
    },
    typography: {
      fontFamily: fam,
      fontSize: size,
      // The canvas draws every heading as Inter 700 with slightly negative
      // tracking — the greeting at 21/700/-0.3px, its headline at 16/700, a
      // tile figure at 23/700/-0.6px. So headings carry WEIGHT rather than a
      // second family, and the larger they get the tighter they track.
      h1: { fontWeight: 700, letterSpacing: '-0.6px' },
      h2: { fontWeight: 700, letterSpacing: '-0.5px' },
      h3: { fontWeight: 700, letterSpacing: '-0.4px' },
      h4: { fontWeight: 700, letterSpacing: '-0.3px' },
      h5: { fontWeight: 700, letterSpacing: '-0.2px' },
      h6: { fontWeight: 700, letterSpacing: '-0.1px' },
      button: { textTransform: 'none', fontWeight: 700 },
    },
    components: {
      // NOTHING may lock page scroll — the app reserves the scrollbar gutter
      // itself, so MUI's compensation double-counts and shifts the layout.
      //
      // App.jsx sets `html { scrollbar-gutter: stable }`, which reserves the
      // scrollbar's width permanently whether or not the page overflows. MUI's
      // modal manager does not know that: on open it sets body overflow hidden
      // and adds `padding-right: <scrollbar width>` to compensate for a
      // scrollbar it assumes has just vanished. With a stable gutter that
      // scrollbar never vanished, so the content box narrows twice and every
      // right-aligned element — the header's avatar and sign-out, the whole
      // co-pilot dock — visibly jumps ~12px left, then back on close.
      //
      // Hence every overlay, not just the anchored ones. An earlier pass fixed
      // Popover and Menu only, on the reasoning that true modals ought to lock
      // scroll; that reasoning does not survive a stable gutter, where the lock
      // buys nothing and costs a visible shift. Modal is the shared base, so it
      // covers anything built on it; Dialog, Drawer, Popover and Menu are named
      // explicitly because each re-declares the prop rather than inheriting it.
      //
      // The trade is that the page can scroll behind an open dialog. Preferable
      // to the jump, and the gutter means the two cannot both be avoided.
      // MuiDialog is NOT listed here: it is declared further down with its own
      // styleOverrides, and a second key for it in this same object literal
      // would silently discard whichever half came first. Its defaultProps live
      // with that declaration instead.
      MuiModal:   { defaultProps: { disableScrollLock: true } },
      MuiDrawer:  { defaultProps: { disableScrollLock: true } },
      MuiPopover: { defaultProps: { disableScrollLock: true } },
      MuiMenu:    { defaultProps: { disableScrollLock: true } },

      // Every focusable control gets the same visible ring — the light theme
      // relied on the browser default, which is invisible on navy.
      MuiCssBaseline: {
        styleOverrides: {
          '*:focus-visible': {
            outline: `2px solid ${T.line.focus}`,
            outlineOffset: 2,
          },
          '::selection': { background: T.state.selected },

          // Motion pairs from the v3 JP artboard. Declared here, once, so the
          // shell and every remote can name them in an `animation` — see
          // ORPHOS_MOTION / orphosAnim() above for which distance to use.
          '@keyframes orphGrow': {
            from: { opacity: 0, transform: 'translateY(6px)' },
            to:   { opacity: 1, transform: 'none' },
          },
          '@keyframes orphGrowOut': {
            from: { opacity: 1, transform: 'none' },
            to:   { opacity: 0, transform: 'translateY(6px)' },
          },
          '@keyframes orphRise': {
            from: { opacity: 0, transform: 'translateY(10px)' },
            to:   { opacity: 1, transform: 'none' },
          },
          '@keyframes orphRiseOut': {
            from: { opacity: 1, transform: 'none' },
            to:   { opacity: 0, transform: 'translateY(10px)' },
          },
          '@keyframes orphSlide': {
            from: { opacity: 0, transform: 'translateX(14px)' },
            to:   { opacity: 1, transform: 'none' },
          },
          '@keyframes orphSlideOut': {
            from: { opacity: 1, transform: 'none' },
            to:   { opacity: 0, transform: 'translateX(14px)' },
          },

          // The artboard has no reduced-motion state to draw, but a product
          // does. Collapsed to a near-instant step rather than `animation:
          // none`: the exit animations gate an unmount (see useSectionPref),
          // so removing them entirely would strand a closing section on screen
          // waiting for an animationend that never fires.
          '@media (prefers-reduced-motion: reduce)': {
            '*, *::before, *::after': {
              animationDuration: '1ms !important',
              animationIterationCount: '1 !important',
              transitionDuration: '1ms !important',
              scrollBehavior: 'auto !important',
            },
          },
          // Scrollbars default to a light track on dark surfaces in Chromium.
          '*::-webkit-scrollbar': { width: 10, height: 10 },
          '*::-webkit-scrollbar-track': { background: T.bg.canvas },
          '*::-webkit-scrollbar-thumb': {
            background: T.line.strong, borderRadius: 8,
            border: `2px solid ${T.bg.canvas}`,
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            borderRadius: ORPHOS_SHAPE.radiusCard,
            backgroundColor: T.bg.surface,
          },
          // Menus/popovers sit ABOVE cards, so they take the deeper fill plus a
          // line — without it they melt into the card underneath.
          elevation8: { backgroundColor: T.bg.deep, border: `1px solid ${T.line.subtle}` },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: ORPHOS_SHAPE.radiusChip, fontSize: 11, fontWeight: 700 },
          outlined: { borderColor: T.line.subtle },
        },
      },
      MuiButton: {
        styleOverrides: {
          root: { borderRadius: ORPHOS_SHAPE.radiusButton, boxShadow: 'none' },
          containedPrimary: {
            backgroundColor: c.primary, color: T.text.onFilled,
            '&:hover': { backgroundColor: c.band, boxShadow: 'none' },
          },
          // The deck's secondary action is an outlined pill on the panel fill.
          outlined: {
            borderColor: T.line.strong, color: T.text.primary,
            '&:hover': { borderColor: c.accent, backgroundColor: T.state.hover },
          },
          text: { '&:hover': { backgroundColor: T.state.hover } },
        },
      },
      MuiIconButton: {
        styleOverrides: {
          root: { color: T.text.primary, '&:hover': { backgroundColor: T.state.hover } },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            backgroundColor: T.bg.surface,
            borderRadius: ORPHOS_SHAPE.radiusButton,
            '& .MuiOutlinedInput-notchedOutline': { borderColor: T.line.strong },
            '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: c.accent },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
              borderColor: c.accent, borderWidth: 2,
            },
          },
          input: { '&::placeholder': { color: T.text.muted, opacity: 1 } },
        },
      },
      MuiLinearProgress: {
        styleOverrides: {
          root: { height: ORPHOS_SHAPE.barHeight, borderRadius: ORPHOS_SHAPE.barHeight / 2,
                  backgroundColor: c.primaryAlt },   // deck: unfilled track is the blue
          bar:  { borderRadius: ORPHOS_SHAPE.barHeight / 2 },
        },
      },
      MuiTab: {
        styleOverrides: {
          // Deck tabs are pills that fill with accent3 when active.
          root: {
            textTransform: 'none', fontWeight: 700, minHeight: 38,
            borderRadius: ORPHOS_SHAPE.radiusPill, color: T.text.secondary,
            '&:hover': { color: T.text.primary, backgroundColor: T.state.hover },
            '&.Mui-selected': { color: T.text.onFilled, backgroundColor: c.primary },
          },
        },
      },
      MuiTabs: { styleOverrides: { indicator: { height: 0 } } },
      MuiTableCell: {
        styleOverrides: {
          root: { borderColor: T.line.subtle },
          head: { backgroundColor: T.bg.deep, fontWeight: 700, color: T.text.primary },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { backgroundColor: T.bg.deep, border: `1px solid ${T.line.subtle}`,
                     fontSize: 12 },
          arrow: { color: T.bg.deep },
        },
      },
      MuiDialog: {
        // disableScrollLock belongs with the scroll-lock note near the top of
        // this components block, but it has to be declared HERE: two MuiDialog
        // keys in one object literal do not merge — the later wins outright.
        defaultProps: { disableScrollLock: true },
        styleOverrides: { paper: { backgroundColor: T.bg.deep } },
      },
      MuiAlert: {
        styleOverrides: {
          standardError:   { backgroundColor: T.status.critical.bg, color: T.status.critical.fg },
          standardWarning: { backgroundColor: T.status.warning.bg,  color: T.status.warning.fg },
          standardSuccess: { backgroundColor: T.status.success.bg,  color: T.status.success.fg },
          standardInfo:    { backgroundColor: T.status.info.bg,     color: T.status.info.fg },
        },
      },
    },
  };
}
