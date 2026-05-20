package org.remus.giteabot.prworkflow.e2e.runner;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;

/**
 * Wave-1 placeholder {@link TestSuiteRunner} for
 * {@link E2eTestFramework#PLAYWRIGHT}. Kept in the codebase for two reasons:
 *
 * <ol>
 *     <li>It documents the minimal SPI contract a runner has to implement.</li>
 *     <li>Tests can instantiate it directly as a deterministic stand-in
 *         when they need a Playwright-flavoured runner that does not spawn
 *         any agents or processes.</li>
 * </ol>
 *
 * <p>Starting with M4 wave 2 this class is <strong>not</strong> a Spring
 * bean any more — the {@link PlaywrightTestSuiteRunner} took over as the
 * registered Playwright handler. Re-registering this class as a
 * {@code @Component} would trip
 * {@link TestSuiteRunnerRegistry}'s duplicate-framework guard at startup.</p>
 */
@Slf4j
public class NoopTestSuiteRunner implements TestSuiteRunner {

    static final String SKIPPED_SUMMARY =
            "E2E test runner not yet enabled (M4 wave 1 placeholder). "
                    + "Preview deployment was reached successfully; no tests were generated.";

    @Override
    public E2eTestFramework framework() {
        return E2eTestFramework.PLAYWRIGHT;
    }

    @Override
    public TestSuiteOutcome run(TestSuiteRequest request) {
        log.info("NoopTestSuiteRunner: skipping suite generation for run id={} pr=#{} previewUrl={}",
                request.suite().getRunId(), request.suite().getPrNumber(), request.previewUrl());
        return TestSuiteOutcome.skipped(SKIPPED_SUMMARY);
    }
}

