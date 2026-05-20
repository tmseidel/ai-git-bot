package org.remus.giteabot.prworkflow.e2e.runner;

/**
 * SPI that plans, generates and executes one E2E test suite.
 *
 * <p>The {@code E2ETestWorkflow} dispatches to whatever
 * {@link TestSuiteRunner} bean is registered for the requested framework.
 * The MVP ships only {@link NoopTestSuiteRunner}, which records a clear
 * "agentic runner not enabled" outcome on the suite; the real LLM-driven
 * implementation (TestPlannerAgent → TestAuthorAgent → TestRunnerAgent)
 * lands in the second M4 wave as a {@code PlaywrightTestSuiteRunner}.</p>
 *
 * <p>Implementations are responsible for:</p>
 * <ul>
 *     <li>Producing zero or more {@link org.remus.giteabot.prworkflow.e2e.PrTestCase}
 *         rows on the provided {@link org.remus.giteabot.prworkflow.e2e.PrTestSuite}
 *         and persisting them.</li>
 *     <li>Executing the cases against the request's {@code previewUrl} and
 *         updating each case's {@code lastStatus} / {@code lastLog} /
 *         {@code lastDurationMs}.</li>
 *     <li>Returning a non-{@code null} {@link TestSuiteOutcome} summarising
 *         the run.</li>
 * </ul>
 *
 * <p>Implementations must not throw checked exceptions. Runtime exceptions
 * are caught by the workflow and surfaced as
 * {@link TestSuiteOutcomeStatus#ERROR}.</p>
 */
public interface TestSuiteRunner {

    /**
     * The framework this runner handles. The workflow picks the first
     * registered runner whose {@code framework()} matches the request.
     */
    org.remus.giteabot.prworkflow.e2e.E2eTestFramework framework();

    TestSuiteOutcome run(TestSuiteRequest request);
}
