package io.orphos.eip.common.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reusable, storage-agnostic schedule definition shared by any module that
 * needs "run at these times" semantics (mail scanning today; briefings,
 * ingestion, report generation tomorrow).
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code frequency} — DAILY, WEEKLY, MONTHLY, QUARTERLY or YEARLY.</li>
 *   <li>{@code times} — one or more times of day ("HH:mm"); every matching day
 *       fires once per time ("multiple times a day").</li>
 *   <li>{@code daysOfWeek} — DAILY/WEEKLY filter (MON..SUN). Empty = all days,
 *       so weekdays-only or weekends-only is just a subset.</li>
 *   <li>{@code dayOfMonth} — MONTHLY/QUARTERLY/YEARLY anchor day (1–31,
 *       clamped to the month length, so 31 acts as "last day").</li>
 *   <li>{@code monthOfQuarter} — QUARTERLY: 1st/2nd/3rd month of each quarter.</li>
 *   <li>{@code month} — YEARLY: calendar month 1–12.</li>
 *   <li>{@code timezone} — IANA zone the times are interpreted in.</li>
 * </ul>
 *
 * <p>The canonical wire/storage form is the {@link #toMap() map} shape (JSON
 * object), matching the {@code ScheduleEditor} React component:
 * <pre>{"frequency":"WEEKLY","times":["08:00","17:30"],"daysOfWeek":["MON","FRI"],
 * "dayOfMonth":1,"monthOfQuarter":1,"month":1,"timezone":"Asia/Kolkata"}</pre>
 */
public final class ScheduleSpec {

    public enum Frequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    /** Hard bound for next-occurrence search — beyond this the spec is treated as never firing. */
    private static final int MAX_LOOKAHEAD_DAYS = 1500;

    private final Frequency frequency;
    private final List<LocalTime> times;
    private final Set<DayOfWeek> daysOfWeek;
    private final int dayOfMonth;
    private final int monthOfQuarter;
    private final int month;
    private final ZoneId zone;

    private ScheduleSpec(Frequency frequency, List<LocalTime> times, Set<DayOfWeek> daysOfWeek,
                         int dayOfMonth, int monthOfQuarter, int month, ZoneId zone) {
        this.frequency = frequency;
        this.times = List.copyOf(new TreeSet<>(times));
        this.daysOfWeek = daysOfWeek.isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(daysOfWeek);
        this.dayOfMonth = dayOfMonth;
        this.monthOfQuarter = monthOfQuarter;
        this.month = month;
        this.zone = zone;
    }

    /**
     * Lenient parse of the canonical map shape. Returns {@code null} for a
     * null/empty map (callers treat that as "no schedule configured").
     *
     * @throws IllegalArgumentException when the map is present but invalid
     */
    public static ScheduleSpec fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;

        Frequency freq;
        try {
            freq = Frequency.valueOf(str(m.get("frequency"), "DAILY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown schedule frequency: " + m.get("frequency"));
        }

        List<LocalTime> times = new ArrayList<>();
        Object rawTimes = m.get("times");
        if (rawTimes instanceof Collection<?> c) {
            for (Object t : c) {
                try {
                    times.add(LocalTime.parse(str(t, "").trim()));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid time of day: " + t + " (expected HH:mm)");
                }
            }
        }
        if (times.isEmpty()) times.add(LocalTime.of(9, 0));
        if (times.size() > 24) {
            throw new IllegalArgumentException("At most 24 times per day are supported");
        }

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Object rawDays = m.get("daysOfWeek");
        if (rawDays instanceof Collection<?> c) {
            for (Object d : c) days.add(parseDay(str(d, "")));
        }

        int dayOfMonth = intVal(m.get("dayOfMonth"), 1);
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("dayOfMonth must be 1..31");
        }
        int monthOfQuarter = intVal(m.get("monthOfQuarter"), 1);
        if (monthOfQuarter < 1 || monthOfQuarter > 3) {
            throw new IllegalArgumentException("monthOfQuarter must be 1..3");
        }
        int month = intVal(m.get("month"), 1);
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be 1..12");
        }

        ZoneId zone;
        try {
            String tz = str(m.get("timezone"), "UTC");
            zone = ZoneId.of(tz.isBlank() ? "UTC" : tz);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown timezone: " + m.get("timezone"));
        }

        return new ScheduleSpec(freq, times, days, dayOfMonth, monthOfQuarter, month, zone);
    }

    /** Canonical map shape (JSON-object friendly) — the inverse of {@link #fromMap}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frequency", frequency.name());
        m.put("times", times.stream().map(LocalTime::toString).toList());
        m.put("daysOfWeek", daysOfWeek.stream().sorted()
                .map(d -> d.name().substring(0, 3)).toList());
        m.put("dayOfMonth", dayOfMonth);
        m.put("monthOfQuarter", monthOfQuarter);
        m.put("month", month);
        m.put("timezone", zone.getId());
        return m;
    }

    /**
     * First occurrence strictly after {@code after}, or {@code null} when none
     * exists within the lookahead bound (cannot normally happen for a valid spec).
     */
    public ZonedDateTime nextRun(ZonedDateTime after) {
        ZonedDateTime anchor = after.withZoneSameInstant(zone);
        LocalDate day = anchor.toLocalDate();
        for (int i = 0; i <= MAX_LOOKAHEAD_DAYS; i++, day = day.plusDays(1)) {
            if (!dayMatches(day)) continue;
            for (LocalTime t : times) {
                ZonedDateTime candidate = day.atTime(t).atZone(zone);
                if (candidate.isAfter(anchor)) return candidate;
            }
        }
        return null;
    }

    /**
     * Whether a scheduled occurrence falls in {@code (lastRun, now]} — the tick
     * contract: callers poll at a fixed interval, remember the last completed
     * run, and fire when this returns true. A never-run schedule
     * ({@code lastRun == null}) is due immediately, so enabling a schedule
     * produces a bootstrap run on the next tick.
     */
    public boolean isDue(Instant lastRun, Instant now) {
        if (lastRun == null) return true;
        if (!now.isAfter(lastRun)) return false;
        ZonedDateTime next = nextRun(lastRun.atZone(zone));
        return next != null && !next.toInstant().isAfter(now);
    }

    private boolean dayMatches(LocalDate day) {
        return switch (frequency) {
            case DAILY, WEEKLY -> daysOfWeek.isEmpty() || daysOfWeek.contains(day.getDayOfWeek());
            case MONTHLY -> day.getDayOfMonth() == clampedDay(day);
            case QUARTERLY -> ((day.getMonthValue() - 1) % 3) + 1 == monthOfQuarter
                    && day.getDayOfMonth() == clampedDay(day);
            case YEARLY -> day.getMonthValue() == month && day.getDayOfMonth() == clampedDay(day);
        };
    }

    /** dayOfMonth clamped to the target month's length — 31 acts as "last day". */
    private int clampedDay(LocalDate day) {
        return Math.min(dayOfMonth, day.lengthOfMonth());
    }

    private static DayOfWeek parseDay(String s) {
        String v = s.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(v) || d.name().startsWith(v) && v.length() >= 3) return d;
        }
        throw new IllegalArgumentException("Unknown day of week: " + s);
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : o.toString();
    }

    private static int intVal(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    public Frequency frequency() { return frequency; }
    public List<LocalTime> times() { return times; }
    public Set<DayOfWeek> daysOfWeek() { return daysOfWeek; }
    public int dayOfMonth() { return dayOfMonth; }
    public int monthOfQuarter() { return monthOfQuarter; }
    public int month() { return month; }
    public ZoneId zone() { return zone; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScheduleSpec s)) return false;
        return frequency == s.frequency && times.equals(s.times) && daysOfWeek.equals(s.daysOfWeek)
                && dayOfMonth == s.dayOfMonth && monthOfQuarter == s.monthOfQuarter
                && month == s.month && zone.equals(s.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frequency, times, daysOfWeek, dayOfMonth, monthOfQuarter, month, zone);
    }

    @Override
    public String toString() {
        return "ScheduleSpec" + toMap();
    }
}
