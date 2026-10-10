package io.orphos.eip.dataingestion.service;

import io.orphos.eip.dataingestion.vo.JobDtos.ScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 4.1/4.2 acceptance checks for schedule computation. */
class JobServiceTest {

    @Test
    void cronNextRunAtIsComputedInUtc() {
        // Daily at 06:00 — from mid-morning the next occurrence is tomorrow.
        assertThat(JobService.computeNextRunAt(ScheduleType.CRON, "0 0 6 * * *", "",
                Instant.parse("2026-07-10T10:15:30Z")))
                .isEqualTo("2026-07-11T06:00:00Z");

        // Every 15 minutes — next boundary within the same hour.
        assertThat(JobService.computeNextRunAt(ScheduleType.CRON, "0 */15 * * * *", "",
                Instant.parse("2026-07-10T10:16:00Z")))
                .isEqualTo("2026-07-10T10:30:00Z");

        // A cron occurrence is strictly AFTER 'from' — no immediate re-fire when
        // the tick lands exactly on the boundary just executed.
        assertThat(JobService.computeNextRunAt(ScheduleType.CRON, "0 0 * * * *", "",
                Instant.parse("2026-07-10T11:00:00Z")))
                .isEqualTo("2026-07-10T12:00:00Z");
    }

    @Test
    void oneTimeScheduleEchoesTheNormalisedInstant() {
        assertThat(JobService.computeNextRunAt(ScheduleType.ONE_TIME, "",
                "2026-08-01T02:00:00Z", Instant.parse("2026-07-10T10:00:00Z")))
                .isEqualTo("2026-08-01T02:00:00Z");
    }

    @Test
    void scheduleLessJobsHaveNoNextRun() {
        assertThat(JobService.computeNextRunAt(ScheduleType.NONE, "", "",
                Instant.parse("2026-07-10T10:00:00Z"))).isEmpty();
    }

    @Test
    void invalidCronIsRejectedBySpringValidation() {
        // The five-field Unix format is NOT accepted — save-time validation
        // (JobService.validateAndResolve) relies on this returning false.
        assertThat(CronExpression.isValidExpression("0 2 * * *")).isFalse();
        assertThat(CronExpression.isValidExpression("not a cron")).isFalse();
        assertThat(CronExpression.isValidExpression("0 0 2 * * *")).isTrue();
        assertThatThrownBy(() -> CronExpression.parse("not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
