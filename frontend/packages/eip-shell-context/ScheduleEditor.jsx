import {
  Box, Typography, TextField, Select, MenuItem, FormControl, InputLabel,
  Checkbox, FormControlLabel, IconButton, Button, Chip, Tooltip,
} from '@mui/material';

/**
 * ScheduleEditor — shared, controlled editor for the platform's common
 * schedule shape (mirrors {@code io.orphos.eip.common.schedule.ScheduleSpec}).
 * Mail scanning uses it today; any module that needs "run at these times"
 * (briefings, ingestion, reports) can reuse it as-is.
 *
 * Value shape (JSON-object, what the backend stores/validates):
 *   {
 *     frequency:      'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY',
 *     times:          ['08:00', '17:30'],      // 1..n times of day
 *     daysOfWeek:     ['MON','TUE',...],       // DAILY/WEEKLY; empty = all days
 *     dayOfMonth:     1..31,                   // MONTHLY/QUARTERLY/YEARLY (31 = last day)
 *     monthOfQuarter: 1..3,                    // QUARTERLY
 *     month:          1..12,                   // YEARLY
 *     timezone:       'Asia/Kolkata',
 *   }
 *
 * Usage: <ScheduleEditor value={schedule} onChange={setSchedule} />
 * `value` may be null — call `defaultSchedule()` to seed one.
 */

export const FREQUENCIES = [
  { value: 'DAILY',     label: 'Daily'     },
  { value: 'WEEKLY',    label: 'Weekly'    },
  { value: 'MONTHLY',   label: 'Monthly'   },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'YEARLY',    label: 'Yearly'    },
];

const DOW = [
  { value: 'MON', label: 'Mon' },
  { value: 'TUE', label: 'Tue' },
  { value: 'WED', label: 'Wed' },
  { value: 'THU', label: 'Thu' },
  { value: 'FRI', label: 'Fri' },
  { value: 'SAT', label: 'Sat' },
  { value: 'SUN', label: 'Sun' },
];
const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI'];
const WEEKENDS = ['SAT', 'SUN'];

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'];

export function browserTimezone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
  catch { return 'UTC'; }
}

// Tenant-wide time zone chosen in the Appearance/Branding admin tab. Cached at
// module scope so every schedule (and its human-readable summary) resolves the
// same zone without threading it through props. BrandingProvider pushes the
// active tenant's value via setTenantTimezone() whenever the branding bundle
// loads or the tenant switches; blank/unset falls back to the browser zone.
let _tenantTimezone = null;
export function setTenantTimezone(tz) {
  _tenantTimezone = (typeof tz === 'string' && tz.trim()) ? tz.trim() : null;
}

/** The effective default zone: the tenant's chosen zone, else the browser's. */
export function tenantTimezone() {
  return _tenantTimezone || browserTimezone();
}

/** Current "GMT±H:MM" offset for a zone (honours DST via today's date). */
function gmtOffset(tz) {
  try {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'shortOffset' })
      .formatToParts(new Date());
    return parts.find(p => p.type === 'timeZoneName')?.value || 'GMT';
  } catch { return 'GMT'; }
}

/**
 * International-standard display label for a zone, e.g. "Kolkata/Asia — GMT+5:30".
 * Matches the format used in the Appearance time-zone picker. Display only — the
 * stored/persisted schedule still carries the canonical IANA id.
 */
export function formatZone(tz) {
  if (!tz) return '';
  const off = gmtOffset(tz);
  if (tz === 'UTC') return `UTC — ${off}`;
  const parts = tz.split('/');
  const region = parts[0].replace(/_/g, ' ');
  const city = parts.slice(1).join(' / ').replace(/_/g, ' ');
  return city ? `${city}/${region} — ${off}` : `${tz} — ${off}`;
}

// Offset (ms) between a UTC instant and its wall-clock reading in `tz`:
// (wall clock interpreted as if it were UTC) − instant. Used to convert
// between a zone's local wall clock and an absolute instant without a date
// library, via Intl's zone-aware formatter.
function zoneOffsetMs(instant, tz) {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const p = {};
  for (const part of dtf.formatToParts(instant)) p[part.type] = part.value;
  const hour = p.hour === '24' ? 0 : Number(p.hour); // Intl can emit '24' at midnight
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, hour, +p.minute, +p.second);
  return asUtc - instant.getTime();
}

/**
 * Convert a `datetime-local` wall-clock string ("2026-07-25T09:00") to a UTC
 * ISO instant, interpreting the wall clock in `tz` (default: the tenant zone)
 * rather than the browser zone — so a time the user picks means the same
 * absolute moment regardless of their own browser zone. Returns '' for blank.
 */
export function zonedWallClockToInstant(localStr, tz = tenantTimezone()) {
  if (!localStr) return '';
  const [datePart, timePart = '00:00'] = String(localStr).split('T');
  const [y, mo, d] = datePart.split('-').map(Number);
  const [h, mi] = timePart.split(':').map(Number);
  if (!y || !mo || !d) return '';
  const utcGuess = Date.UTC(y, mo - 1, d, h || 0, mi || 0);
  // Two passes settle DST boundaries: the offset at the guessed instant may
  // differ from the offset at the resolved instant.
  const offset = zoneOffsetMs(new Date(utcGuess), tz);
  let instant = utcGuess - offset;
  const offset2 = zoneOffsetMs(new Date(instant), tz);
  if (offset2 !== offset) instant = utcGuess - offset2;
  return new Date(instant).toISOString();
}

/**
 * Inverse of {@link zonedWallClockToInstant}: render a UTC ISO instant as the
 * "YYYY-MM-DDTHH:mm" wall clock in `tz`, suitable as a `datetime-local` input
 * value. Returns '' for blank/invalid input.
 */
export function instantToZonedWallClock(iso, tz = tenantTimezone()) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
  const p = {};
  for (const part of dtf.formatToParts(date)) p[part.type] = part.value;
  const hour = p.hour === '24' ? '00' : p.hour;
  return `${p.year}-${p.month}-${p.day}T${hour}:${p.minute}`;
}

/** A sensible starting value: every day at 09:00 in the tenant's timezone. */
export function defaultSchedule() {
  return {
    frequency: 'DAILY',
    times: ['09:00'],
    daysOfWeek: [],
    dayOfMonth: 1,
    monthOfQuarter: 1,
    month: 1,
    timezone: tenantTimezone(),
  };
}

/** One-line human summary, e.g. "Weekly on Mon, Fri at 08:00, 17:30 (Asia/Kolkata)". */
export function describeSchedule(s) {
  if (!s) return '';
  const times = (s.times || []).join(', ');
  const freq = FREQUENCIES.find(f => f.value === s.frequency)?.label || s.frequency;
  let when = '';
  if (s.frequency === 'DAILY' || s.frequency === 'WEEKLY') {
    const days = s.daysOfWeek || [];
    if (days.length === 0 || days.length === 7) when = 'every day';
    else if (WEEKDAYS.every(d => days.includes(d)) && days.length === 5) when = 'on weekdays';
    else if (WEEKENDS.every(d => days.includes(d)) && days.length === 2) when = 'on weekends';
    else when = 'on ' + days.map(d => DOW.find(x => x.value === d)?.label || d).join(', ');
  } else if (s.frequency === 'MONTHLY') {
    when = `on day ${s.dayOfMonth}`;
  } else if (s.frequency === 'QUARTERLY') {
    when = `on day ${s.dayOfMonth} of quarter month ${s.monthOfQuarter}`;
  } else if (s.frequency === 'YEARLY') {
    when = `on ${MONTHS[(s.month || 1) - 1]} ${s.dayOfMonth}`;
  }
  return `${freq} ${when} at ${times} (${formatZone(s.timezone || tenantTimezone())})`;
}

export default function ScheduleEditor({ value, onChange, disabled = false }) {
  const s = value || defaultSchedule();
  const set = patch => onChange({ ...s, ...patch });

  const days = s.daysOfWeek || [];
  const toggleDay = d => {
    if (days.length === 0) {
      // Empty = all days (all boxes shown checked); unticking one keeps the other six.
      set({ daysOfWeek: DOW.map(x => x.value).filter(x => x !== d) });
      return;
    }
    const next = days.includes(d) ? days.filter(x => x !== d) : [...days, d];
    // Re-normalise "all seven picked" back to the canonical empty form.
    set({ daysOfWeek: next.length === 7 ? [] : next });
  };

  const setTime = (i, v) => set({ times: s.times.map((t, idx) => (idx === i ? v : t)) });
  const addTime = () => set({ times: [...s.times, '12:00'] });
  const removeTime = i => set({ times: s.times.filter((_, idx) => idx !== i) });

  const showDays = s.frequency === 'DAILY' || s.frequency === 'WEEKLY';
  const showDayOfMonth = ['MONTHLY', 'QUARTERLY', 'YEARLY'].includes(s.frequency);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25 }}>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
        <FormControl size='small' sx={{ minWidth: 130 }} disabled={disabled}>
          <InputLabel sx={{ fontSize: 12 }}>Frequency</InputLabel>
          <Select label='Frequency' value={s.frequency} sx={{ fontSize: 12 }}
            onChange={e => set({ frequency: e.target.value })}>
            {FREQUENCIES.map(f => (
              <MenuItem key={f.value} value={f.value} sx={{ fontSize: 12 }}>{f.label}</MenuItem>
            ))}
          </Select>
        </FormControl>

        {s.frequency === 'QUARTERLY' && (
          <FormControl size='small' sx={{ minWidth: 160 }} disabled={disabled}>
            <InputLabel sx={{ fontSize: 12 }}>Month of quarter</InputLabel>
            <Select label='Month of quarter' value={s.monthOfQuarter || 1} sx={{ fontSize: 12 }}
              onChange={e => set({ monthOfQuarter: e.target.value })}>
              {[1, 2, 3].map(m => (
                <MenuItem key={m} value={m} sx={{ fontSize: 12 }}>
                  {['1st', '2nd', '3rd'][m - 1]} month (e.g. {MONTHS[m - 1].slice(0, 3)}/{MONTHS[m + 2].slice(0, 3)}/…)
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        )}

        {s.frequency === 'YEARLY' && (
          <FormControl size='small' sx={{ minWidth: 130 }} disabled={disabled}>
            <InputLabel sx={{ fontSize: 12 }}>Month</InputLabel>
            <Select label='Month' value={s.month || 1} sx={{ fontSize: 12 }}
              onChange={e => set({ month: e.target.value })}>
              {MONTHS.map((m, i) => (
                <MenuItem key={m} value={i + 1} sx={{ fontSize: 12 }}>{m}</MenuItem>
              ))}
            </Select>
          </FormControl>
        )}

        {showDayOfMonth && (
          <FormControl size='small' sx={{ minWidth: 130 }} disabled={disabled}>
            <InputLabel sx={{ fontSize: 12 }}>Day of month</InputLabel>
            <Select label='Day of month' value={s.dayOfMonth || 1} sx={{ fontSize: 12 }}
              onChange={e => set({ dayOfMonth: e.target.value })}>
              {Array.from({ length: 31 }, (_, i) => i + 1).map(d => (
                <MenuItem key={d} value={d} sx={{ fontSize: 12 }}>
                  {d === 31 ? '31 (or last day)' : d}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        )}
      </Box>

      {showDays && (
        <Box>
          <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center', flexWrap: 'wrap', mb: 0.25 }}>
            <Typography fontSize={11} color='#C3D3F2' sx={{ mr: 0.5 }}>Days:</Typography>
            <Chip label='All days' size='small' onClick={() => !disabled && set({ daysOfWeek: [] })}
              sx={{ fontSize: 10 }} variant={days.length === 0 ? 'filled' : 'outlined'} />
            <Chip label='Weekdays' size='small' onClick={() => !disabled && set({ daysOfWeek: [...WEEKDAYS] })}
              sx={{ fontSize: 10 }}
              variant={WEEKDAYS.every(d => days.includes(d)) && days.length === 5 ? 'filled' : 'outlined'} />
            <Chip label='Weekends' size='small' onClick={() => !disabled && set({ daysOfWeek: [...WEEKENDS] })}
              sx={{ fontSize: 10 }}
              variant={WEEKENDS.every(d => days.includes(d)) && days.length === 2 ? 'filled' : 'outlined'} />
          </Box>
          <Box sx={{ display: 'flex', flexWrap: 'wrap' }}>
            {DOW.map(d => (
              <FormControlLabel key={d.value} disabled={disabled}
                control={<Checkbox size='small' sx={{ py: 0.25 }}
                  checked={days.length === 0 || days.includes(d.value)}
                  onChange={() => toggleDay(d.value)} />}
                label={<Typography fontSize={11}>{d.label}</Typography>} />
            ))}
          </Box>
          {days.length === 0 && (
            <Typography fontSize={10} color='#7D92C4'>All days selected — untick days to narrow it down.</Typography>
          )}
        </Box>
      )}

      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography fontSize={11} color='#C3D3F2'>Time(s) of day:</Typography>
        {s.times.map((t, i) => (
          <Box key={i} sx={{ display: 'flex', alignItems: 'center' }}>
            <TextField size='small' type='time' value={t} disabled={disabled}
              onChange={e => e.target.value && setTime(i, e.target.value)}
              sx={{ width: 110, '& input': { fontSize: 12, py: 0.5 } }} />
            {s.times.length > 1 && (
              <Tooltip title='Remove this time'>
                <IconButton size='small' disabled={disabled} onClick={() => removeTime(i)}
                  sx={{ color: '#7D92C4', '&:hover': { color: '#FF6B81' } }}>
                  <Typography fontSize={14} lineHeight={1}>×</Typography>
                </IconButton>
              </Tooltip>
            )}
          </Box>
        ))}
        {s.times.length < 24 && (
          <Button size='small' disabled={disabled} onClick={addTime}
            sx={{ textTransform: 'none', fontSize: 11 }}>+ Add time</Button>
        )}
      </Box>

      <Typography fontSize={10} color='#7D92C4'>
        {describeSchedule(s)}
      </Typography>
    </Box>
  );
}
