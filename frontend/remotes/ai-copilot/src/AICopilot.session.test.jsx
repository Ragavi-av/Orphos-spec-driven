// Exercises the REAL AICopilot component through the exact reported flow:
//
//   submit a query → unmount the panel (the dock's Documents toggle flipping to
//   Document Search unmounts it) → the answer arrives while nothing is mounted
//   → mount the panel again
//
// The store-level tests in copilotRuntime.test.js prove the store survives.
// These prove the COMPONENT is actually wired to it — that a re-mounted panel
// re-adopts the running session instead of starting a fresh one.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act } from '@testing-library/react';

const post = vi.fn();
const get  = vi.fn();

vi.mock('axios', () => {
  const inst = { post: (...a) => post(...a), get: (...a) => get(...a), put: vi.fn(), delete: vi.fn() };
  return { default: inst };
});
vi.mock('./sessionStore', () => ({
  identityFrom: () => 'user-1',
  saveSession: vi.fn(() => Promise.resolve()),
  migrateLocalSessions: vi.fn(() => Promise.resolve()),
}));
vi.mock('eip-shell-context/AuthProvider', () => ({
  useAuth: () => ({ tokenParsed: { sub: 'user-1' } }),
}));
vi.mock('eip-shell-context/TrustBadge', () => ({ default: () => null }));
// Return the key itself, so assertions can key off stable strings rather than
// copy of the English catalogue.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k) => k, i18n: { language: 'en' } }),
}));

let AICopilot, runtime;

function deferred() {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const flush = () => act(() => new Promise(r => setTimeout(r, 0)));

beforeEach(async () => {
  vi.resetModules();
  post.mockReset();
  get.mockReset();
  // Provider/key lookups the panel fires on mount; irrelevant here.
  get.mockImplementation(() => Promise.resolve({ data: {} }));
  runtime   = await import('./copilotRuntime.js');
  AICopilot = (await import('./AICopilot.jsx')).default;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('AICopilot across an unmount (Documents toggle)', () => {
  it('re-adopts the running session and shows the answer that arrived while away', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    // --- 1. panel open, query submitted ---
    const first = render(<AICopilot />);
    await flush();

    await act(async () => {
      runtime.ask({ query: 'What are my top risks?', providerId: 'inhouse' });
    });

    expect(screen.getByText('What are my top risks?')).toBeTruthy();
    // Pending: the thinking line is rendered, the answer is not.
    expect(screen.queryByText('copilot.thinking')).toBeTruthy();

    const sessionIdWhilePending = runtime.getSnapshot().sessionId;

    // --- 2. Documents toggle ON: the dock unmounts this panel ---
    first.unmount();

    // --- 3. the model answers while nothing is mounted ---
    req.resolve({ data: { answer: 'Three contracts breach SLA.', provider: 'Granite 3.1' } });
    await flush();

    // --- 4. back to AI Copilot: the dock mounts a new panel instance ---
    render(<AICopilot />);
    await flush();

    // Same session — not a fresh one.
    expect(runtime.getSnapshot().sessionId).toBe(sessionIdWhilePending);
    // The question and the completed answer are both on screen.
    expect(screen.getByText('What are my top risks?')).toBeTruthy();
    expect(screen.getByText('Three contracts breach SLA.')).toBeTruthy();
    expect(screen.queryByText('copilot.thinking')).toBeNull();
    // The welcome/empty state must NOT be showing.
    expect(screen.queryByText('copilot.welcome')).toBeNull();
    // Never re-sent.
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('shows the still-processing state when the answer has not arrived yet', async () => {
    post.mockReturnValue(deferred().promise);

    const first = render(<AICopilot />);
    await flush();
    await act(async () => {
      runtime.ask({ query: 'Summarise open incidents', providerId: 'inhouse' });
    });

    first.unmount();          // to Document Search
    render(<AICopilot />);    // and back, answer still pending
    await flush();

    expect(screen.getByText('Summarise open incidents')).toBeTruthy();
    expect(screen.queryByText('copilot.thinking')).toBeTruthy();
    expect(screen.queryByText('copilot.welcome')).toBeNull();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('keeps earlier conversation turns across the unmount', async () => {
    post.mockReturnValue(Promise.resolve({ data: { answer: 'First answer.' } }));
    const first = render(<AICopilot />);
    await flush();
    await act(async () => {
      runtime.ask({ query: 'First question', providerId: 'inhouse' });
    });
    await flush();

    first.unmount();
    render(<AICopilot />);
    await flush();

    expect(screen.getByText('First question')).toBeTruthy();
    expect(screen.getByText('First answer.')).toBeTruthy();
  });
});
