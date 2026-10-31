package org.remus.giteabot.prworkflow.unittest.runner;

import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.prworkflow.unittest.UnitTestFramework;
import org.remus.giteabot.prworkflow.unittest.UnitTestSuite;

/**
 * Everything a {@link UnitTestRunner} needs to execute one generated unit-test
 * suite. Built by {@code UnitTestService} once the author agent has written
 * the test files into the repository checkout.
 *
 * @param suite        the persisted suite whose cases were just authored
 * @param workspace    the repository checkout (PR head branch) the tests live in
 * @param framework    the resolved build/test toolchain
 * @param maxRetries   per-suite retry budget for a failing run
 * @param baseline     coverage measured before the new tests were added
 *                     ({@code null} when no baseline could be computed)
 */
public record UnitTestRunRequest(
        UnitTestSuite suite,
        Workspace workspace,
        UnitTestFramework framework,
        int maxRetries,
        org.remus.giteabot.prworkflow.unittest.coverage.CoverageResult baseline) {
}

