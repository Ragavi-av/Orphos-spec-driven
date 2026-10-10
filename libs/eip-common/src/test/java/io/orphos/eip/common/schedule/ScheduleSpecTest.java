package io.orphos.eip.common.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleSpecTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime utc(String iso) {
        return ZonedDateTime.parse(iso);
    }

    /** Compare by instant — ZoneId "Z" vs "UTC" makes ZonedDateTime.equals too strict. */
    private static void assertSameInstant(String expectedIso, ZonedDateTime actual) {
        assertNotNull(actual);
        assertEquals(Instant.parse(expectedIso), actual.toInstant());
    }

    @Test
    void nullOrEmptyMapMeansNoSchedule() {
        assertNull(ScheduleSpec.fromMap(null));
        assertNull(ScheduleSpec.fromMap(Map.of()));
    }

    @Test
    void dailyMultipleTimes() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "DAILY", "times", List.of("08:00", "18:00"), "timezone", "UTC"));
        assertSameInstant("2026-07-07T08:00:00Z", s.nextRun(utc("2026-07-07T00:00:00Z")));
        assertSameInstant("2026-07-07T18:00:00Z", s.nextRun(utc("2026-07-07T08:00:00Z")));
        assertSameInstant("2026-07-08T08:00:00Z", s.nextRun(utc("2026-07-07T18:00:00Z")));
    }

    @Test
    void weekendsOnly() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "WEEKLY", "times", List.of("10:00"),
                "daysOfWeek", List.of("SAT", "SUN"), "timezone", "UTC"));
        // 2026-07-07 is a Tuesday; next weekend day is Saturday the 11th.
        assertSameInstant("2026-07-11T10:00:00Z", s.nextRun(utc("2026-07-07T12:00:00Z")));
    }

    @Test
    void monthlyDay31ClampsToLastDayOfShortMonths() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "MONTHLY", "times", List.of("06:00"),
                "dayOfMonth", 31, "timezone", "UTC"));
        // After Feb 1st 2026 → Feb 28th (2026 is not a leap year).
        assertSameInstant("2026-02-28T06:00:00Z", s.nextRun(utc("2026-02-01T00:00:00Z")));
    }

    @Test
    void quarterlySecondMonth() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "QUARTERLY", "times", List.of("07:30"),
                "monthOfQuarter", 2, "dayOfMonth", 15, "timezone", "UTC"));
        // Quarters' 2nd months: Feb, May, Aug, Nov. After 2026-07-07 → Aug 15.
        assertSameInstant("2026-08-15T07:30:00Z", s.nextRun(utc("2026-07-07T00:00:00Z")));
    }

    @Test
    void yearly() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "YEARLY", "times", List.of("00:15"),
                "month", 4, "dayOfMonth", 1, "timezone", "UTC"));
        assertSameInstant("2027-04-01T00:15:00Z", s.nextRun(utc("2026-07-07T00:00:00Z")));
    }

    @Test
    void respectsTimezone() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "DAILY", "times", List.of("09:00"), "timezone", "Asia/Kolkata"));
        // 09:00 IST == 03:30 UTC.
        ZonedDateTime next = s.nextRun(utc("2026-07-07T00:00:00Z"));
        assertEquals(Instant.parse("2026-07-07T03:30:00Z"), next.toInstant());
    }

    @Test
    void isDueContract() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "DAILY", "times", List.of("08:00"), "timezone", "UTC"));
        // Never run → due immediately (bootstrap).
        assertTrue(s.isDue(null, Instant.parse("2026-07-07T00:00:00Z")));
        // Ran at 07:00; 08:00 occurrence falls inside (07:00, 08:05] → due.
        assertTrue(s.isDue(Instant.parse("2026-07-07T07:00:00Z"),
                Instant.parse("2026-07-07T08:05:00Z")));
        // Ran at 08:01; next occurrence is tomorrow → not due at 09:00.
        assertFalse(s.isDue(Instant.parse("2026-07-07T08:01:00Z"),
                Instant.parse("2026-07-07T09:00:00Z")));
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.fromMap(Map.of("frequency", "HOURLY")));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.fromMap(Map.of("frequency", "DAILY", "times", List.of("25:00"))));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.fromMap(Map.of("frequency", "MONTHLY", "dayOfMonth", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleSpec.fromMap(Map.of("frequency", "DAILY", "timezone", "Mars/Olympus")));
    }

    @Test
    void mapRoundTrip() {
        ScheduleSpec s = ScheduleSpec.fromMap(Map.of(
                "frequency", "WEEKLY", "times", List.of("08:00", "17:30"),
                "daysOfWeek", List.of("MON", "FRI"), "timezone", "Asia/Kolkata"));
        assertEquals(s, ScheduleSpec.fromMap(s.toMap()));
    }
}
