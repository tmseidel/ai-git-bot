package org.remus.giteabot.prworkflow.e2e.tools;

import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;

/**
 * Per-invocation context that {@link PrWorkflowToolExecutor} needs to execute
 * one of the five PR-workflow tools. Constructed by the
 * {@code PlaywrightTestSuiteRunner} from a {@code TestSuiteRequest} once the
 * deployment is ready.
 *
 * @param suite        the {@link PrTestSuite} the agents are populating
 * @param workspace    sandboxed scratch directory allocated by
 *                     {@code PrTestWorkspaceManager}
 * @param framework    chosen test framework (drives the {@code pr-test-run} command line)
 * @param previewUrl   reachable preview URL produced by the deployment strategy
 * @param owner        repository owner login (used by {@code attach-artifact})
 * @param repo         repository name
 * @param prNumber     pull request number this run belongs to
 * @param apiClient    bot's pre-configured {@link RepositoryApiClient} (used by
 *                     {@code attach-artifact})
 */
public record PrWorkflowToolContext(
        PrTestSuite suite,
        Path workspace,
        E2eTestFramework framework,
        String previewUrl,
        String owner,
        String repo,
        Long prNumber,
        RepositoryApiClient apiClient) {
}
