package org.remus.giteabot.prworkflow.e2e.runner;

/**
 * Aggregate outcome of one {@link TestSuiteRunner} invocation. The per-case
 * details live on the persisted {@link org.remus.giteabot.prworkflow.e2e.PrTestCase}
 * rows — this record is just the workflow-level rollup that the {@code
 * E2ETestWorkflow} maps onto {@link org.remus.giteabot.prworkflow.WorkflowResult}.
 *
 * @param status     overall outcome
 * @param summary    one-line human-readable summary (≤ ~200 chars)
 * @param attempted  number of test cases the runner actually executed
 * @param failed     number of cases that ended in FAILED or ERROR
 */
public record TestSuiteOutcome(
        TestSuiteOutcomeStatus status,
        String summary,
        int attempted,
        int failed) {

    public static TestSuiteOutcome skipped(String summary) {
        return new TestSuiteOutcome(TestSuiteOutcomeStatus.SKIPPED, summary, 0, 0);
    }

    public static TestSuiteOutcome error(String summary) {
        return new TestSuiteOutcome(TestSuiteOutcomeStatus.ERROR, summary, 0, 0);
    }

    public static TestSuiteOutcome passed(String summary, int attempted) {
        return new TestSuiteOutcome(TestSuiteOutcomeStatus.PASSED, summary, attempted, 0);
    }

    public static TestSuiteOutcome failed(String summary, int attempted, int failed) {
        return new TestSuiteOutcome(TestSuiteOutcomeStatus.FAILED, summary, attempted, failed);
    }
}
