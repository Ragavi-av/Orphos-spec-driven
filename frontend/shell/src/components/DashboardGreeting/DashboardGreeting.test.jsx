import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * The greeting banner's ranked tasks and their per-task "Ask Orphos" buttons.
 *
 * The v3 JP artboard replaced ONE button carrying the whole digest with one
 * button PER ranked task. That is the contract under test here, because the
 * component cannot enforce any of it on its own:
 *
 *   1. Every task gets its own button, labelled "Ask Orphos" — a label the
 *      digest's own i18n bundle owns, not a literal in this file.
 *   2. Clicking a task's button hands `onAsk` a `{ label, query }` OBJECT
 *      naming THAT task. The co-pilot panel reads `externalQuery.query`
 *      (ai-copilot/src/AICopilot.jsx), so a bare string silently produced an
 *      undefined query and a click that did nothing.
 *   3. Tasks are ranked 1..n, worst first — the rank chip is the only thing
 *      telling the user what to do first.
 */

// The banner reads its digest over HTTP on mount; serve it from here instead.
const mockGet = vi.fn();
vi.mock('eip-shell-context/axiosAuth', () => ({
  default: { get: (...args) => mockGet(...args) },
}));

// The next-meeting panel reads the calendar on its own, through a separate
// failure domain. Stubbed to empty so these cases only exercise the digest —
// the meeting panel has its own expectations at the bottom of this file.
const mockMeetings = vi.fn();
vi.mock('eip-shell-context/meetings', async (importOriginal) => ({
  ...(await importOriginal()),
  fetchTodaysMeetings: (...args) => mockMeetings(...args),
}));

// Real i18n bundle — the point is to prove the LABEL resolves, not to stub it.
import 'eip-shell-context/i18n';
import DashboardGreeting from './DashboardGreeting';

const DIGEST = {
  headline: 'Here is what needs your attention today.',
  items: [
    { title: 'Database Connection Pool Exhaustion on Salesforce CRM',
      detail: 'Sustained 98% utilisation', severity: 'critical', kind: 'incident' },
    { title: 'sys-00010 Transaction Throughput',
      detail: 'Below target for 3 days', severity: 'critical', kind: 'sla' },
    { title: 'Vendor Contract Renewal Decision', severity: 'warning', kind: 'decision' },
  ],
};

const askButtons = () => screen.findAllByRole('button', { name: /ask orphos/i });

describe('DashboardGreeting → ranked tasks', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockGet.mockResolvedValue({ data: DIGEST });
    mockMeetings.mockReset();
    mockMeetings.mockResolvedValue([]);
    // useSectionPref persists the brief's collapse state to localStorage, which
    // outlives testing-library's DOM cleanup — without this, one test that
    // collapses the brief leaves every later test rendering the collapsed
    // one-liner and asserting against tasks that are no longer on screen.
    localStorage.clear();
  });

  it('gives every ranked task its own "Ask Orphos" button', async () => {
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);

    expect(await askButtons()).toHaveLength(DIGEST.items.length);
    // The old single-button wording must be gone, not merely joined by the new.
    expect(screen.queryByRole('button', { name: /ask copilot/i })).not.toBeInTheDocument();
  });

  it('ranks the tasks 1..n in digest order, worst first', async () => {
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await askButtons();

    for (const [idx, item] of DIGEST.items.entries()) {
      const row = screen.getByText(item.title).closest('div').parentElement.parentElement;
      expect(within(row).getByText(String(idx + 1))).toBeInTheDocument();
    }
  });

  it('hands the co-pilot a { label, query } naming the task that was clicked', async () => {
    const onAsk = vi.fn();
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={onAsk} />);

    const buttons = await askButtons();
    await userEvent.click(buttons[1]);          // the SECOND task, not the first

    await waitFor(() => expect(onAsk).toHaveBeenCalledTimes(1));
    const arg = onAsk.mock.calls[0][0];

    // Shape — the half that was broken before.
    expect(typeof arg).toBe('object');
    expect(typeof arg.query).toBe('string');
    expect(arg.query.length).toBeGreaterThan(0);

    // Content — the clicked task and its detail line, and NOT its neighbours:
    // a per-task button that sent the whole digest would defeat the redesign.
    const clicked = DIGEST.items[1];
    expect(arg.query).toContain(clicked.title);
    expect(arg.query).toContain(clicked.detail);
    expect(arg.query).not.toContain(DIGEST.items[0].title);
    expect(arg.query).not.toContain(DIGEST.items[2].title);

    // The badge carries the record kind, so the conversation shows where the
    // question came from.
    expect(arg.label).toBe('SLA breach');
  });

  it('omits the detail parentheses when a task has no detail line', async () => {
    const onAsk = vi.fn();
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={onAsk} />);

    const buttons = await askButtons();
    await userEvent.click(buttons[2]);          // the one with no `detail`

    await waitFor(() => expect(onAsk).toHaveBeenCalledTimes(1));
    expect(onAsk.mock.calls[0][0].query).not.toContain('()');
  });

  /** Collapse the brief — the task count lives on its one-line summary. */
  const collapseBrief = async () => {
    // Found by aria-expanded rather than by wording: SectionToggle's label is a
    // translated sentence, and pinning it here would make this test fail on a
    // copy edit that has nothing to do with pluralisation.
    const [toggle] = await screen.findAllByRole('button', { expanded: true });
    await userEvent.click(toggle);
  };

  // i18next selects a plural form from `count`, NOT from an arbitrary `n`.
  // Passing `n` looked correct and silently fell back to the raw key once these
  // strings gained _one/_other forms, so both ends are pinned here.
  //
  // Asserted on the COLLAPSED brief. The expanded card used to repeat this
  // count as a header above row 1; the design drops it there (the numbered rank
  // chips already say how many), so the collapsed summary is now the only place
  // it renders — and the only place the plural form can regress.
  it('counts the tasks with real plural forms, singular and plural', async () => {
    const many = render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await askButtons();
    await collapseBrief();
    expect(await screen.findByText(/3 things need you today/)).toBeInTheDocument();
    many.unmount();
    // The collapse above persisted; the singular half needs to start expanded
    // again so it has a toggle to click.
    localStorage.clear();

    mockGet.mockResolvedValue({ data: { ...DIGEST, items: [DIGEST.items[0]] } });
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await askButtons();
    await collapseBrief();
    expect(await screen.findByText(/1 thing needs you today/)).toBeInTheDocument();

    // The "(s)" placeholder wording must be gone, not merely joined by the new.
    expect(screen.queryByText(/thing\(s\)/)).not.toBeInTheDocument();
  });

  it('renders a counted fallback line when the model wrote no prose', async () => {
    // briefing-service returns titleKey + n when the AI provider is unreachable.
    mockGet.mockResolvedValue({ data: { headline: '', items: [
      { titleKey: 'dashboard.priorities.criticalAlerts', n: 1, severity: 'critical' },
      { titleKey: 'dashboard.priorities.pendingDecisions', n: 4, severity: 'warning' },
    ] } });
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);

    expect(await screen.findByText('1 critical alert needs review')).toBeInTheDocument();
    expect(screen.getByText('4 decisions awaiting approval')).toBeInTheDocument();
  });

  it('renders no button when there is nothing to ask about', async () => {
    mockGet.mockResolvedValue({ data: { headline: '', items: [] } });
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);

    await screen.findByText(/all caught up/i);
    expect(screen.queryByRole('button', { name: /ask orphos/i })).not.toBeInTheDocument();
  });
});

describe('DashboardGreeting → collapsing the brief', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockGet.mockResolvedValue({ data: DIGEST });
    mockMeetings.mockReset();
    mockMeetings.mockResolvedValue([]);
    localStorage.clear();
  });

  it('collapses to a summary line and back, and remembers the choice', async () => {
    const { unmount } = render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await askButtons();

    await userEvent.click(screen.getByRole('button', { name: /click to minimised/i }));

    // The exit animation keeps the body mounted briefly, so wait it out rather
    // than asserting immediately.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /ask orphos/i })).not.toBeInTheDocument());
    expect(screen.getByText(DIGEST.headline)).toBeInTheDocument();

    // A remount reads the persisted preference rather than springing back open.
    unmount();
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await screen.findByText(DIGEST.headline);
    expect(screen.queryByRole('button', { name: /ask orphos/i })).not.toBeInTheDocument();
  });

  it('pins a default from the caret menu and marks the toggle as locked', async () => {
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);
    await askButtons();

    await userEvent.click(screen.getByRole('button', { name: /morning brief default/i }));
    await userEvent.click(await screen.findByRole('menuitemradio', { name: /always expanded/i }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /locked to always expanded/i }))
        .toBeInTheDocument());
  });
});

describe('DashboardGreeting → next meeting', () => {
  const MEETING = {
    id: 'm1',
    title: 'Board finance review',
    senderDisplay: 'Finance',
    dueDate: new Date(Date.now() + 45 * 60000).toISOString(),
  };

  beforeEach(() => {
    mockGet.mockReset();
    mockGet.mockResolvedValue({ data: DIGEST });
    mockMeetings.mockReset();
    localStorage.clear();
  });

  it('names the next meeting and how long until it starts', async () => {
    mockMeetings.mockResolvedValue([MEETING]);
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);

    expect(await screen.findByText(MEETING.title)).toBeInTheDocument();
    expect(screen.getByText(/in 45 min/i)).toBeInTheDocument();
  });

  it('survives a calendar the digest knows nothing about', async () => {
    // The two are separate failure domains: a dead calendar must still leave a
    // readable brief rather than blanking the banner.
    mockMeetings.mockRejectedValue(new Error('calendar down'));
    render(<DashboardGreeting userFullName="Kenji Tanaka" onAsk={vi.fn()} />);

    expect(await askButtons()).toHaveLength(DIGEST.items.length);
    expect(screen.getByText(/nothing else on the calendar/i)).toBeInTheDocument();
  });
});
