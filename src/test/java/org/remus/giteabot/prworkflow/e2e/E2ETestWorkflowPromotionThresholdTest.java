package org.remus.giteabot.prworkflow.e2e;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the package-private pass-rate helpers on
 * {@link E2ETestWorkflow} that decide whether a suite qualifies for
 * promotion. These cover the boundary cases that the higher-level
 * workflow / close-handler tests would otherwise have to enumerate
 * with mocks.
 */
class E2ETestWorkflowPromotionThresholdTest {

    @Test
    void passedOutcomeAlwaysQualifies() {
        TestSuiteOutcome passed = TestSuiteOutcome.passed("all good", 10);

        assertThat(E2ETestWorkflow.meetsPromotionThreshold(passed, 100)).isTrue();
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(passed, 0)).isTrue();
    }

    @Test
    void errorAndSkippedNeverQualify() {
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(
                TestSuiteOutcome.error("framework explosion"), 0)).isFalse();
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(
                TestSuiteOutcome.skipped("no runner"), 0)).isFalse();
    }

    @Test
    void failedOutcomeQualifiesWhenPassRateMeetsThreshold() {
        // 8/10 = 80% → meets 80, meets 70, fails 90
        TestSuiteOutcome failed = TestSuiteOutcome.failed("2 failed", 10, 2);

        assertThat(E2ETestWorkflow.meetsPromotionThreshold(failed, 80)).isTrue();
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(failed, 70)).isTrue();
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(failed, 90)).isFalse();
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(failed, 100)).isFalse();
    }

    @Test
    void failedOutcomeWithZeroAttemptedNeverQualifies() {
        TestSuiteOutcome failed = TestSuiteOutcome.failed("no cases", 0, 0);

        assertThat(E2ETestWorkflow.meetsPromotionThreshold(failed, 0)).isFalse();
    }

    @Test
    void passRatePercentRoundsDown() {
        // 2/3 = 66.66% → floors to 66 so threshold=66 passes, 67 fails
        assertThat(E2ETestWorkflow.passRatePercent(3, 1)).isEqualTo(66);
        // 1/3 = 33.33% → 33
        assertThat(E2ETestWorkflow.passRatePercent(3, 2)).isEqualTo(33);
        // 10/10 = 100%
        assertThat(E2ETestWorkflow.passRatePercent(10, 0)).isEqualTo(100);
        // 0/10 = 0%
        assertThat(E2ETestWorkflow.passRatePercent(10, 10)).isEqualTo(0);
        // attempted=0 → 0% (safe default)
        assertThat(E2ETestWorkflow.passRatePercent(0, 0)).isEqualTo(0);
    }

    @Test
    void passRatePercentClampsNegativeFailedToZero() {
        // Defensive: even if failed > attempted (bad data) we must not return negative.
        assertThat(E2ETestWorkflow.passRatePercent(5, 10)).isEqualTo(0);
    }

    @Test
    void nullOutcomeIsRejected() {
        assertThat(E2ETestWorkflow.meetsPromotionThreshold(null, 0)).isFalse();
    }
}

