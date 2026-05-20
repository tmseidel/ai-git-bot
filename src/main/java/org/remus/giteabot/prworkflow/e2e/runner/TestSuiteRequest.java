package org.remus.giteabot.prworkflow.e2e.runner;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;

import java.nio.file.Path;

/**
 * Everything a {@link TestSuiteRunner} needs to plan, generate and execute
 * one E2E test suite for a PR. Built by
 * {@code E2ETestWorkflow.run(...)} from the workflow context once the
 * deployment is ready.
 *
 * @param context     the active {@link PrWorkflowContext}
 * @param bot         the bot whose configuration drives prompts and tools
 * @param payload     the inbound PR webhook payload (head SHA / branch are read from here)
 * @param suite       the persisted, empty {@link PrTestSuite} the runner populates
 * @param workspace   sandboxed scratch directory allocated by
 *                    {@link org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager}
 * @param framework   chosen test framework (mirrors {@link PrTestSuite#getFramework()})
 * @param previewUrl  reachable preview URL produced by the deployment strategy
 * @param maxRetries  per-case retry budget (planner-decided, defaults from workflow params)
 * @param maxTestCases hard upper bound the runner must not exceed
 */
public record TestSuiteRequest(
        PrWorkflowContext context,
        Bot bot,
        WebhookPayload payload,
        PrTestSuite suite,
        Path workspace,
        E2eTestFramework framework,
        String previewUrl,
        int maxRetries,
        int maxTestCases) {
}
