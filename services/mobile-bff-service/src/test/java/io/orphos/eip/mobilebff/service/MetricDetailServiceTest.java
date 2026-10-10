package io.orphos.eip.mobilebff.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Key-result progress runs from baseline to target, whichever way the target points. */
class MetricDetailServiceTest {

    @Test
    void progressIsTheShareOfTheWayFromBaselineToTarget() {
        assertThat(MetricDetailService.progress(1_200_000d, 1_350_000d, 1_500_000d)).isEqualTo(50);
    }

    @Test
    void fallingBelowTheBaselineIsZeroNotNegative() {
        // Net cash flow −3.8 L against a 12 L baseline and a 15 L target.
        assertThat(MetricDetailService.progress(1_200_000d, -380_000d, 1_500_000d)).isZero();
    }

    @Test
    void aLowerIsBetterTargetCountsDownwardProgress() {
        // Degraded systems: baseline 7, now 5, target 3 → half way.
        assertThat(MetricDetailService.progress(7d, 5d, 3d)).isEqualTo(50);
    }

    @Test
    void withoutABaselineItIsCurrentOverTargetCapped() {
        assertThat(MetricDetailService.progress(null, 26d, 28d)).isEqualTo(93);
        assertThat(MetricDetailService.progress(null, 40d, 28d)).isEqualTo(100);
        assertThat(MetricDetailService.progress(null, null, 28d)).isZero();
    }
}
