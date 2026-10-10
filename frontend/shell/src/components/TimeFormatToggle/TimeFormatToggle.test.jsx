import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * The reader's clock, and the line it must not cross.
 *
 * 14:30 and 2:30 PM are the same instant said two ways, so the choice is the
 * reader's — like the language, and unlike the time ZONE, which decides WHICH
 * instant is meant and stays with the admin's country. These tests hold that
 * line: the preference must move every time on the page, and must move nothing
 * else about them.
 */

import i18n from 'eip-shell-context/i18n';
import { setTenantCountry } from 'eip-shell-context/country';
import { setTimeFormat, timeFormat, hour12Options } from 'eip-shell-context/timeFormat';
import { tenantTimezone } from 'eip-shell-context/ScheduleEditor';
import { formatTime } from 'eip-shell-context/datetime';
import TimeFormatToggle from './TimeFormatToggle';

// 09:30 UTC — 15:00 in Kolkata and 18:30 in Tokyo. Both land in the afternoon,
// so 12h and 24h genuinely differ (an 08:00 would read "8:00 AM" and "08:00",
// which a broken hour12 could pass by accident).
const INSTANT = '2026-08-31T09:30:00Z';

const COLORS = { muted: '#7D92C4', menuLine: '#2E4A8C' };
const pill = name => screen.getByRole('button', { name });

describe('clock format (reader preference)', () => {
  beforeEach(async () => {
    localStorage.clear();
    setTimeFormat('auto');
    setTenantCountry('IN');
    await i18n.changeLanguage('en');
  });

  it('defaults to auto, following the workspace country', () => {
    expect(timeFormat()).toBe('auto');
    // Nothing forced — Intl gets no hour12 key at all and the locale answers.
    expect(hour12Options()).toEqual({});
  });

  it('forces 24-hour everywhere when the reader picks it', async () => {
    render(<TimeFormatToggle colors={COLORS} />);
    await userEvent.click(pill('24h'));

    expect(timeFormat()).toBe('24');
    expect(formatTime(INSTANT)).toContain('15:00');
    expect(formatTime(INSTANT)).not.toMatch(/[AaPp]\.?[Mm]/);
  });

  it('forces 12-hour everywhere when the reader picks it', async () => {
    render(<TimeFormatToggle colors={COLORS} />);
    await userEvent.click(pill('12h'));

    expect(timeFormat()).toBe('12');
    const shown = formatTime(INSTANT);
    expect(shown).toMatch(/3:00/);
    expect(shown).toMatch(/[AaPp]\.?[Mm]/);
  });

  it('changes only the notation — never the instant or the zone', async () => {
    setTenantCountry('JP');
    // The country move also resets the reader's language, which relabels these
    // pills 12時間/24時間. Read the board in English so the selectors below stay
    // about the clock rather than about translation.
    await i18n.changeLanguage('en');
    render(<TimeFormatToggle colors={COLORS} />);

    await userEvent.click(pill('24h'));
    expect(formatTime(INSTANT)).toContain('18:30');

    await userEvent.click(pill('12h'));
    // Same moment, same Tokyo clock, said the other way. If the zone had moved
    // with the preference this would read 3:00 (Kolkata) instead.
    expect(formatTime(INSTANT)).toMatch(/6:30/);
    expect(tenantTimezone()).toBe('Asia/Tokyo');
  });

  it('survives a remount and marks the chosen option pressed', async () => {
    const { unmount } = render(<TimeFormatToggle colors={COLORS} />);
    await userEvent.click(pill('12h'));
    await waitFor(() => expect(pill('12h')).toHaveAttribute('aria-pressed', 'true'));
    expect(pill('24h')).toHaveAttribute('aria-pressed', 'false');
    expect(pill('Auto')).toHaveAttribute('aria-pressed', 'false');

    unmount();
    render(<TimeFormatToggle colors={COLORS} />);
    expect(pill('12h')).toHaveAttribute('aria-pressed', 'true');
  });

  it('returns to the country default when the reader picks Auto again', async () => {
    render(<TimeFormatToggle colors={COLORS} />);
    await userEvent.click(pill('12h'));
    expect(hour12Options()).toEqual({ hour12: true });

    await userEvent.click(pill('Auto'));
    expect(timeFormat()).toBe('auto');
    expect(hour12Options()).toEqual({});
  });

  it('is unaffected by the country moving', async () => {
    render(<TimeFormatToggle colors={COLORS} />);
    await userEvent.click(pill('12h'));

    // The admin relocates the workspace. That resets the reader's LANGUAGE by
    // design, but the clock preference is about notation the country has no
    // opinion on once overridden — it stays.
    setTenantCountry('JP');

    expect(timeFormat()).toBe('12');
    expect(formatTime(INSTANT)).toMatch(/6:30/);
  });
});
