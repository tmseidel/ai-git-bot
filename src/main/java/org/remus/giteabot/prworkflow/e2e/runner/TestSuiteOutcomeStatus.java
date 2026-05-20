package org.remus.giteabot.prworkflow.e2e.runner;

/**
 * Overall status of one {@link TestSuiteRunner} execution. Mapped onto
 * {@link org.remus.giteabot.prworkflow.WorkflowResult} by {@code E2ETestWorkflow}.
 */
public enum TestSuiteOutcomeStatus {
    /** Every case passed (FLAKY allowed unless the operator opts into stricter mode). */
    PASSED,
    /** At least one case ended in FAILED or ERROR. */
    FAILED,
    /** Runner refused to run (no implementation available, configuration error, …). */
    SKIPPED,
    /** Runner aborted before completion (timeout, cancelled, infrastructure error). */
    ERROR
}
