import { useState, useCallback } from 'react';
import { Box, ButtonBase, ClickAwayListener } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { ORPHOS_TOKENS, ORPHOS_MOTION, orphosAnim } from './orphosTheme';

// The v3 JP artboard's section control: one split button doing two jobs.
//
//   left half   collapse/expand this section, now
//   right half  pin what it should do BY DEFAULT, from now on
//
// They are drawn as one object — the halves share an outline and the seam
// between them is a single border, not a gap — because they act on the same
// section. Splitting them into two separate buttons made the caret read as an
// unrelated overflow menu.
//
// The lock dot on the left half is the only signal that a default is pinned, so
// it is not decorative: without it a section that refuses to stay closed looks
// like a bug rather than a preference. The caret takes the same blue when
// locked, so the pair reads as one lit control.

const PANEL_BG = '#04102B';   // menu ground — deeper than any card, so it floats
const OPT_ON_BG = '#123A6B';  // selected option, the artboard's info-tile blue
const LOCK_BLUE = '#4C8DFF';

/**
 * @param {boolean}  open          is the section currently expanded
 * @param {?string}  always        'expand' | 'collapse' | null — the pinned default
 * @param {function} onToggle      collapse/expand now
 * @param {function} onSetAlways   pin a default (null = remember last choice)
 * @param {string}   menuTitle     heading over the menu, e.g. "Morning brief default"
 * @param {object}   words         { expand, collapse } — past-participle wording
 *                                 for this section ("expanded"/"minimised",
 *                                 "shown"/"hidden"), used in the menu options
 *                                 and the tooltip.
 * @param {boolean}  [invertArrows=false]  the tab strip opens DOWNWARD, so its
 *                                 arrows are the other way round from a section
 *                                 that grows upward into the page.
 * @param {'below'|'above'} [menuPlacement='below']  which side the caret menu
 *                                 opens on. The tab strip is docked at the
 *                                 bottom of the dashboard, so a menu dropping
 *                                 below it would open off-screen.
 * @param {object}   [sx]          merged onto the control's wrapper. Needed by
 *                                 call sites whose row aligns on `baseline`:
 *                                 this control is a flex container, so its
 *                                 baseline resolves to the ▲ glyph's, which
 *                                 leaves the button hanging a couple of pixels
 *                                 below the text it sits beside. Those rows
 *                                 pass `alignSelf: 'center'`.
 */
export default function SectionToggle({
  open, always, onToggle, onSetAlways, menuTitle, words,
  invertArrows = false, menuPlacement = 'below', sx,
}) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuClosing, setMenuClosing] = useState(false);

  const closeMenu = useCallback(() => {
    if (!menuOpen) return;
    setMenuClosing(true);
    setTimeout(() => { setMenuClosing(false); setMenuOpen(false); }, ORPHOS_MOTION.popMs);
  }, [menuOpen]);

  const locked = !!always;

  // Which way the caret on the LEFT half points: toward what a click will do.
  const arrows = invertArrows ? { open: '▼', closed: '▲' } : { open: '▲', closed: '▼' };

  const tooltip = locked
    ? t('dashboard.section.lockedTo', 'Locked to always {{state}}', {
        state: always === 'expand' ? words.expand : words.collapse })
    : t('dashboard.section.clickTo', 'Click to {{action}}', {
        action: open ? words.collapse : words.expand });

  const options = [
    { id: null, label: t('dashboard.section.rememberLast', 'Remember my last choice') },
    { id: 'expand', label: t('dashboard.section.always', 'Always {{state}}', { state: words.expand }) },
    { id: 'collapse', label: t('dashboard.section.always', 'Always {{state}}', { state: words.collapse }) },
  ];

  const halfSx = {
    bgcolor: ORPHOS_TOKENS.bg.deep,
    border: `1px solid ${ORPHOS_TOKENS.line.strong}`,
    color: ORPHOS_TOKENS.text.bright,
    fontSize: 12,
    lineHeight: 1,
    transition: 'background-color 180ms ease, color 180ms ease, border-color 180ms ease',
    '&:hover': { bgcolor: ORPHOS_TOKENS.state.hover },
  };

  return (
    <ClickAwayListener onClickAway={closeMenu}>
      <Box sx={{
        position: 'relative', display: 'flex', alignItems: 'center', flex: '0 0 auto',
        ...sx,
      }}>
        {/* ── Left half: act now ── */}
        <ButtonBase
          onClick={onToggle}
          aria-expanded={open}
          aria-label={tooltip}
          title={tooltip}
          sx={{
            ...halfSx,
            borderRadius: '8px 0 0 8px',
            px: '10px', py: '6px',
            // Floor size from the artboard: without it the left half visibly
            // narrows when the lock dot is not rendered, so pinning a default
            // resized the control under the pointer.
            minWidth: 30, minHeight: 28,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '5px',
          }}
        >
          {open ? arrows.open : arrows.closed}
          {/* Only rendered when pinned — an always-present dim dot would read as
              a disabled control rather than as "no default set". */}
          {locked && (
            <Box component='span' aria-hidden sx={{ color: LOCK_BLUE, fontSize: 14, lineHeight: 1 }}>
              •
            </Box>
          )}
        </ButtonBase>

        {/* ── Right half: pin a default ──
            Pulled 4px left over the sibling's border so the two share one seam
            instead of showing a double-thickness line where they meet. */}
        <ButtonBase
          onClick={() => (menuOpen ? closeMenu() : setMenuOpen(true))}
          aria-haspopup='menu'
          aria-expanded={menuOpen}
          aria-label={menuTitle}
          title={menuTitle}
          sx={{
            ...halfSx,
            ml: '-4px',
            borderLeft: 'none',
            borderRadius: '0 8px 8px 0',
            px: '8px', py: '5px',
            minHeight: 28,
            color: locked ? LOCK_BLUE : ORPHOS_TOKENS.text.muted,
          }}
        >
          ▾
        </ButtonBase>

        {(menuOpen || menuClosing) && (
          <Box
            role='menu'
            aria-label={menuTitle}
            sx={{
              position: 'absolute', right: 0, zIndex: 24, width: 196,
              ...(menuPlacement === 'above' ? { bottom: 32 } : { top: 32 }),
              bgcolor: PANEL_BG,
              border: `1px solid ${ORPHOS_TOKENS.line.strong}`,
              borderRadius: '11px', p: '6px',
              boxShadow: '0 18px 40px rgba(0, 0, 0, 0.55)',
              // Normalised because this menu is often rendered inside an
              // uppercase, letter-spaced section header, which its own copy
              // must not inherit.
              textTransform: 'none', letterSpacing: 'normal',
              animation: orphosAnim('grow', menuClosing, ORPHOS_MOTION.popMs),
            }}
          >
            <Box sx={{
              fontSize: 12, fontWeight: 600, color: ORPHOS_TOKENS.text.muted,
              textTransform: 'uppercase', letterSpacing: '0.9px', p: '5px 7px 6px',
            }}>
              {menuTitle}
            </Box>

            {options.map((o) => {
              const on = always === o.id;
              return (
                <ButtonBase
                  key={o.id || 'remember'}
                  role='menuitemradio'
                  aria-checked={on}
                  onClick={() => { onSetAlways(o.id); closeMenu(); }}
                  sx={{
                    width: '100%', justifyContent: 'flex-start',
                    display: 'flex', alignItems: 'center', gap: '8px',
                    p: '7px', borderRadius: '8px',
                    bgcolor: on ? OPT_ON_BG : 'transparent',
                    '&:hover': { bgcolor: on ? OPT_ON_BG : ORPHOS_TOKENS.state.hover },
                  }}
                >
                  <Box aria-hidden sx={{
                    width: 12, height: 12, borderRadius: '50%', flex: '0 0 auto',
                    border: `1px solid ${on ? '#5B9DFF' : ORPHOS_TOKENS.line.strong}`,
                    bgcolor: on ? '#2F6FF0' : 'transparent',
                  }} />
                  <Box component='span' sx={{
                    fontSize: 13.5, textAlign: 'left',
                    color: on ? '#DBE9FF' : ORPHOS_TOKENS.text.bright,
                  }}>
                    {o.label}
                  </Box>
                </ButtonBase>
              );
            })}
          </Box>
        )}
      </Box>
    </ClickAwayListener>
  );
}
