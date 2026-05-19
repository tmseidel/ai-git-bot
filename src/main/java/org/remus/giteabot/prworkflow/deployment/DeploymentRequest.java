package org.remus.giteabot.prworkflow.deployment;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;

/**
 * Everything a {@link DeploymentStrategy} needs in order to trigger one
 * preview deployment. Built by {@link DeploymentOrchestrator} from a
 * {@link PrWorkflowRun}, {@link Bot} and the inbound PR webhook payload.
 *
 * @param run         the persisted workflow run (carries {@code id} and
 *                    {@code callbackSecret} the strategy embeds in the request)
 * @param target      the deployment target with its decrypted config JSON
 * @param repoOwner   PR repository owner (e.g. {@code "acme"})
 * @param repoName    PR repository name  (e.g. {@code "web"})
 * @param prNumber    pull-request number
 * @param sha         head SHA the deployment should build from
 * @param branch      head branch name (fully qualified, e.g. {@code feature/x})
 * @param callbackUrl base URL of the bot's callback endpoint
 *                    (e.g. {@code https://bot.acme.io/api/workflow-callback/123/abcd}).
 *                    The strategy passes this verbatim to the CI side.
 */
public record DeploymentRequest(
        PrWorkflowRun run,
        DeploymentTarget target,
        String repoOwner,
        String repoName,
        long prNumber,
        String sha,
        String branch,
        String callbackUrl) {
}
