// The point of copilotRuntime is that a query outlives the panel rendering it:
// the shell's dock unmounts AICopilot the moment the Documents toggle flips to
// Document Search (and likewise for History, the collapsed rail, or Admin).
//
// These tests stand in for that unmount by not touching the store while a
// request is pending — exactly what an unmounted panel does — and assert that
// the answer still lands, that the session is the SAME one on return, and that
// the conversation is intact.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const post = vi.fn();
const saveSession = vi.fn(() => Promise.resolve());

vi.mock('axios', () => ({ default: { post: (...a) => post(...a) } }));
vi.mock('./sessionStore', () => ({ saveSession: (...a) => saveSession(...a) }));

let runtime;

/** A promise plus its resolvers, so a test can hold a request "in flight". */
function deferred() {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** Let the pending .then/.catch chains inside the runtime settle. */
const flush = () => new Promise(r => setTimeout(r, 0));

beforeEach(async () => {
  vi.resetModules();
  post.mockReset();
  saveSession.mockReset();
  saveSession.mockImplementation(() => Promise.resolve());
  // A fresh module instance per test, so each starts on an empty session.
  runtime = await import('./copilotRuntime.js');
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('switching to Document Search mid-query', () => {
  it('lets the answer land and shows it on return', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    runtime.ask({ query: 'What are my top risks?', providerId: 'inhouse' });

    expect(runtime.isBusy()).toBe(true);
    expect(runtime.getSnapshot().messages).toHaveLength(1);

    // --- Documents toggle ON: the panel unmounts, nothing reads the store ---

    req.resolve({ data: { answer: 'Three contracts breach SLA.', provider: 'Granite 3.1' } });
    await flush();

    // --- back to AI Copilot: the panel re-mounts and re-reads the store ---

    const [msg] = runtime.getSnapshot().messages;
    expect(runtime.isBusy()).toBe(false);
    expect(msg.loading).toBe(false);
    expect(msg.question).toBe('What are my top risks?');
    expect(msg.answer).toBe('Three contracts breach SLA.');
    // Exactly one POST — the query was never cancelled, restarted or re-sent.
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('does not start a new session, and keeps the conversation', async () => {
    // A conversation already in progress before the user touches the toggle.
    post.mockReturnValue(Promise.resolve({ data: { answer: 'First answer.' } }));
    runtime.ask({ query: 'First question', providerId: 'inhouse' });
    await flush();

    const before = runtime.getSnapshot();
    const sessionIdBefore = before.sessionId;
    const startedAtBefore = before.startedAt;

    // A second query still running when the toggle flips.
    const req = deferred();
    post.mockReturnValue(req.promise);
    runtime.ask({ query: 'Second question', providerId: 'inhouse' });

    // --- away to Document Search and back, with the answer arriving between ---
    req.resolve({ data: { answer: 'Second answer.' } });
    await flush();

    const after = runtime.getSnapshot();
    // The session identity must survive — a new id here is the reported bug.
    expect(after.sessionId).toBe(sessionIdBefore);
    expect(after.startedAt).toBe(startedAtBefore);
    // Full history preserved, in order, both answers present.
    expect(after.messages.map(m => m.question))
      .toEqual(['First question', 'Second question']);
    expect(after.messages.map(m => m.answer))
      .toEqual(['First answer.', 'Second answer.']);
    expect(post).toHaveBeenCalledTimes(2);
  });

  it('is still reported as processing if the answer has not arrived yet', async () => {
    post.mockReturnValue(deferred().promise);

    runtime.ask({ query: 'Summarise open incidents', providerId: 'inhouse' });
    await flush();

    // Returning mid-flight shows the pending message, not an empty panel.
    expect(runtime.isBusy()).toBe(true);
    expect(runtime.getSnapshot().messages[0]).toMatchObject({
      question: 'Summarise open incidents', loading: true, answer: null,
    });
  });

  it('keeps the half-typed question across the switch', () => {
    runtime.setDraft('half-typed quest');
    expect(runtime.getSnapshot().draft).toBe('half-typed quest');
    // Draft edits must not each cost a history write.
    expect(saveSession).not.toHaveBeenCalled();
  });

  it('notifies a panel that re-subscribes after the request started', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    runtime.ask({ query: 'Status?', providerId: 'inhouse' });

    // The re-mounted panel subscribes late; it must still be told when the
    // answer lands, or it would sit on a stale spinner forever.
    const listener = vi.fn();
    const unsubscribe = runtime.subscribe(listener);

    req.resolve({ data: { answer: 'All clear.' } });
    await flush();

    expect(listener).toHaveBeenCalled();
    expect(runtime.getSnapshot().messages[0].answer).toBe('All clear.');
    unsubscribe();
  });
});

describe('answer bookkeeping', () => {
  it('records a failure as the answer rather than losing the message', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    runtime.ask({
      query: 'Anything on AWS spend?', providerId: 'inhouse',
      fallbackFollowups: ['Show me the trend'],
    });
    req.reject({ response: { status: 500 } });
    await flush();

    const [msg] = runtime.getSnapshot().messages;
    expect(runtime.isBusy()).toBe(false);
    expect(msg.loading).toBe(false);
    expect(msg.answer).toMatch(/AI service returned an error/);
    expect(msg.followups).toEqual(['Show me the trend']);
  });

  it('writes the finished answer to history even though nothing was mounted', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    runtime.ask({ query: 'Which SLAs are at risk?', providerId: 'inhouse' });
    // Nothing is persisted while the only message is still in flight.
    expect(saveSession).not.toHaveBeenCalled();

    req.resolve({ data: { answer: 'Two.' } });
    await flush();

    expect(saveSession).toHaveBeenCalledTimes(1);
    expect(saveSession.mock.calls[0][0]).toMatchObject({
      label: 'Which SLAs are at risk?',
      messages: [expect.objectContaining({ answer: 'Two.' })],
    });
  });

  it('drops an answer belonging to a session the user has explicitly left', async () => {
    const req = deferred();
    post.mockReturnValue(req.promise);

    runtime.ask({ query: 'Old question', providerId: 'inhouse' });
    // Only the "new session" button does this — never a section switch.
    runtime.startNewSession();

    req.resolve({ data: { answer: 'Answer to the old question' } });
    await flush();

    expect(runtime.getSnapshot().messages).toEqual([]);
    expect(runtime.isBusy()).toBe(false);
  });

  it('claims each cross-remote query object exactly once', () => {
    const first  = { query: 'From a widget', label: 'Cloud spend' };
    const second = { query: 'From a widget', label: 'Cloud spend' };

    expect(runtime.claimExternalQuery(first)).toBe(true);
    // A re-mount re-runs the effect with the SAME object — must not re-ask.
    expect(runtime.claimExternalQuery(first)).toBe(false);
    // A genuinely new ask, even with identical text, must go through.
    expect(runtime.claimExternalQuery(second)).toBe(true);
    expect(runtime.claimExternalQuery(null)).toBe(false);
  });
});
