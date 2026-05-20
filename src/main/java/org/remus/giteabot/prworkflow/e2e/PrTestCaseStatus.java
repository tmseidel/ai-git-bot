package org.remus.giteabot.prworkflow.e2e;

/**
 * Outcome of one {@link PrTestCase}'s last execution. Persisted on
 * {@code pr_test_cases.last_status}. The string {@link #name()} is what hits
 * the database — renaming a value requires a Flyway migration.
 *
 * <p>{@link #FLAKY} is reserved for the {@code TestRunnerAgent}'s retry
 * heuristic (test fails, then passes within {@code maxRetries}); reported as
 * a soft failure in the PR summary so reviewers can act on it.</p>
 */
public enum PrTestCaseStatus {
    /** Generated but not yet executed (initial value). */
    PENDING,
    /** All assertions passed. */
    PASSED,
    /** At least one assertion failed. */
    FAILED,
    /** Passed only after one or more retries. */
    FLAKY,
    /** Skipped by the runner (e.g. preview env was unreachable). */
    SKIPPED,
    /** Could not be executed at all (framework error, missing dependency). */
    ERROR
}
