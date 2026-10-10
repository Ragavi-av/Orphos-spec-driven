import { describe, it, expect } from 'vitest';
import { tenantThemeOptions } from 'eip-shell-context/tenantTheme';

/**
 * No overlay may lock body scroll, because the app reserves the gutter itself.
 *
 * App.jsx sets `html { scrollbar-gutter: stable }`, so the scrollbar's width is
 * reserved permanently. MUI's modal manager assumes otherwise: on open it hides
 * body overflow and adds `padding-right: <scrollbar width>` to compensate for a
 * scrollbar that, here, never went away. The content box therefore narrows
 * twice and every right-aligned element — the header's avatar and sign-out, the
 * whole co-pilot dock — jumps ~12px left, then back on close.
 *
 * An earlier pass covered Popover and Menu only, reasoning that true modals
 * ought to lock scroll. That left the co-pilot's model-override <Dialog>
 * shifting the page exactly as before. With a stable gutter the lock buys
 * nothing anywhere, so the rule is now unconditional.
 *
 * Asserted on the theme options rather than by rendering, because the bug is a
 * property of the defaults every overlay inherits, not of any one component: a
 * Dialog added later anywhere in the shell or in a remote has to be covered by
 * construction. jsdom also reports no scrollbar, so a render test could not
 * observe the shift that makes this worth fixing.
 */
const OVERLAYS = ['MuiModal', 'MuiDialog', 'MuiDrawer', 'MuiPopover', 'MuiMenu'];

describe('theme: overlays do not lock page scroll', () => {
  for (const [name, params] of [
    ['dark (Orphos default)', { mode: 'dark' }],
    ['light (legacy tenant)', { mode: 'light' }],
    ['unset mode', {}],
  ]) {
    it(`disables scroll lock on every overlay — ${name}`, () => {
      const { components } = tenantThemeOptions(params);
      for (const overlay of OVERLAYS) {
        expect(components[overlay]?.defaultProps?.disableScrollLock,
          `${overlay} must not lock scroll`).toBe(true);
      }
    });
  }

  it('states the gutter assumption this depends on', () => {
    // If App.jsx ever drops `scrollbar-gutter: stable`, disabling the lock
    // becomes the wrong call — the scrollbar would genuinely come and go and
    // MUI's compensation would be doing real work. This test is the paper trail
    // for that: the two settings only make sense together.
    const { components } = tenantThemeOptions({ mode: 'dark' });
    expect(components.MuiModal.defaultProps.disableScrollLock).toBe(true);
  });
});
