import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * useSectionPref's cross-instance sync.
 *
 * The dashboard's tab strip collapses the morning brief when the panel opens
 * (the artboard's openTabsPanel), but the brief is RENDERED by the greeting
 * band — a different component, holding its own hook on the same key. Without
 * a shared registry each instance kept private state, so App's write reached
 * localStorage and nothing on screen moved until a reload.
 *
 * These cover the registry directly rather than through App.jsx, which needs
 * Keycloak, branding and the plugin registry to mount.
 */

import useSectionPref from 'eip-shell-context/useSectionPref';

/** Two independent components sharing one key, as App and the band do. */
function Watcher({ sectionKey, name }) {
  const pref = useSectionPref(sectionKey, true);
  return (
    <div>
      <span data-testid={`${name}-state`}>{pref.open ? 'open' : 'shut'}</span>
      <span data-testid={`${name}-always`}>{String(pref.always)}</span>
      <button onClick={pref.toggle}>toggle-{name}</button>
      <button onClick={() => pref.setAlways('expand')}>pin-{name}</button>
    </div>
  );
}

const stateOf = name => screen.getByTestId(`${name}-state`).textContent;

describe('useSectionPref → cross-instance sync', () => {
  beforeEach(() => { localStorage.clear(); });

  it('moves every instance on the same key when one of them writes', async () => {
    render(<><Watcher sectionKey='brief' name='a' /><Watcher sectionKey='brief' name='b' /></>);
    expect(stateOf('a')).toBe('open');
    expect(stateOf('b')).toBe('open');

    await userEvent.click(screen.getByText('toggle-a'));

    // The exit animation holds `open` true briefly, so wait it out.
    await waitFor(() => expect(stateOf('a')).toBe('shut'));
    expect(stateOf('b')).toBe('shut');
  });

  it('propagates a pinned default too, not just the open flag', async () => {
    render(<><Watcher sectionKey='brief' name='a' /><Watcher sectionKey='brief' name='b' /></>);

    await userEvent.click(screen.getByText('pin-a'));

    await waitFor(() =>
      expect(screen.getByTestId('b-always').textContent).toBe('expand'));
  });

  it('keeps different keys independent', async () => {
    render(<><Watcher sectionKey='brief' name='a' /><Watcher sectionKey='tabs' name='b' /></>);

    await userEvent.click(screen.getByText('toggle-a'));

    await waitFor(() => expect(stateOf('a')).toBe('shut'));
    // The tab panel must not close because the brief did.
    expect(stateOf('b')).toBe('open');
  });

  it('unsubscribes on unmount rather than writing to a dead component', async () => {
    const { unmount } = render(<Watcher sectionKey='brief' name='a' />);
    unmount();

    // A surviving instance writing after the first unmounted must not throw.
    render(<Watcher sectionKey='brief' name='b' />);
    await act(async () => { await userEvent.click(screen.getByText('toggle-b')); });
    await waitFor(() => expect(stateOf('b')).toBe('shut'));
  });

  it('reads a persisted preference back on a fresh mount', async () => {
    const { unmount } = render(<Watcher sectionKey='brief' name='a' />);
    await userEvent.click(screen.getByText('toggle-a'));
    await waitFor(() => expect(stateOf('a')).toBe('shut'));
    unmount();

    render(<Watcher sectionKey='brief' name='c' />);
    expect(stateOf('c')).toBe('shut');
  });
});
