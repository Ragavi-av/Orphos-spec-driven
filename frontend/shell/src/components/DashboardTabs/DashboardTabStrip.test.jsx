import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * The tab strip's two states.
 *
 * The strip is a DOCK the panel rests on, not a header above it, and the whole
 * point of the redesign is that its chrome changes with the panel:
 *
 *   open       squared top corners + deep fill, so it reads as the panel's foot
 *   collapsed  rounded all round + card fill, so it stands alone
 *
 * Those are the only visual cues that the panel is collapsed rather than empty,
 * which is why they are asserted rather than left to the eye.
 */

import 'eip-shell-context/i18n';
import DashboardTabStrip from './DashboardTabStrip';

const TABS = [
  { key: 'tasks-for-action', label: 'Tasks for Action' },
  { key: 'notifications', label: 'Notifications' },
  { key: 'decision-log', label: 'Decision Log' },
];

const OPEN = { open: true, always: null, closing: false };
const SHUT = { open: false, always: null, closing: false };

function setup(props = {}) {
  const onChange = vi.fn();
  const onToggle = vi.fn();
  const onSetAlways = vi.fn();
  const utils = render(
    <DashboardTabStrip
      tabs={TABS} value={0} onChange={onChange}
      pref={OPEN} onToggle={onToggle} onSetAlways={onSetAlways}
      {...props}
    />,
  );
  return { ...utils, onChange, onToggle, onSetAlways };
}

/**
 * The strip's own container — the element carrying the state-dependent chrome.
 *
 * The render root, not `tablist.parentElement`: MUI nests the tablist inside its
 * own scroller, so walking up from it lands on internals rather than on the Box
 * whose border-radius is under test.
 */
const strip = (container) => container.firstChild;

describe('DashboardTabStrip', () => {
  beforeEach(() => { localStorage.clear(); });

  it('renders one tab per visible entry, as a tablist', () => {
    setup();
    expect(screen.getAllByRole('tab')).toHaveLength(TABS.length);
    for (const tabDef of TABS) {
      expect(screen.getByRole('tab', { name: new RegExp(tabDef.label, 'i') })).toBeInTheDocument();
    }
  });

  it('squares its top corners against the panel when open', () => {
    const { container } = setup({ pref: OPEN });
    expect(getComputedStyle(strip(container)).borderRadius).toBe('0 0 12px 12px');
  });

  it('rounds all round and lifts off the panel when collapsed', () => {
    const { container } = setup({ pref: SHUT });
    const css = getComputedStyle(strip(container));
    expect(css.borderRadius).toBe('12px');
    // 20px of air above it — collapsed, it is a standalone bar rather than the
    // foot of something.
    expect(css.marginTop).toBe('20px');
  });

  it('reports the selected index rather than the tab object', async () => {
    const { onChange } = setup();
    await userEvent.click(screen.getByRole('tab', { name: /notifications/i }));
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(
      TABS.findIndex(tabDef => tabDef.key === 'notifications')));
  });

  it('badges the notifications tab only when something is unread', () => {
    const { rerender } = setup({ trackerCount: 0 });
    expect(screen.queryByText('7')).not.toBeInTheDocument();

    rerender(
      <DashboardTabStrip tabs={TABS} value={0} onChange={vi.fn()}
        pref={OPEN} onToggle={vi.fn()} onSetAlways={vi.fn()} trackerCount={7} />,
    );
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('caps the badge at 99+', () => {
    setup({ trackerCount: 250 });
    expect(screen.getByText('99+')).toBeInTheDocument();
  });

  it('opens its Panel default menu upward, above the docked strip', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', { name: /panel default/i }));

    const menu = await screen.findByRole('menu', { name: /panel default/i });
    const css = getComputedStyle(menu);
    // `bottom`, not `top` — a menu dropping below a strip parked at the foot of
    // the viewport would open off-screen.
    expect(css.bottom).toBe('32px');
    expect(css.top).not.toBe('32px');
  });

  it('points its arrow the way the panel travels — up to open, down to close', () => {
    const { unmount } = setup({ pref: SHUT });
    expect(screen.getByRole('button', { name: /click to expanded/i })).toHaveTextContent('▲');
    unmount();

    setup({ pref: OPEN });
    expect(screen.getByRole('button', { name: /click to collapsed/i })).toHaveTextContent('▼');
  });

  it('renders nothing when the user can see no tabs', () => {
    const { container } = render(
      <DashboardTabStrip tabs={[]} value={0} onChange={vi.fn()}
        pref={OPEN} onToggle={vi.fn()} onSetAlways={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
