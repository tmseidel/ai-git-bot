package org.remus.giteabot.prworkflow.deployment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunService;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * High-level entry point used by workflows that need a per-PR preview
 * deployment. Looks up the bot's configured {@link DeploymentTarget},
 * dispatches to the matching {@link DeploymentStrategy} and — for strategies
 * that {@linkplain DeploymentStrategy#awaitsCallback() await a callback} —
 * blocks until the inbound HTTP callback wakes
 * {@link DeploymentCallbackNotifier}.
 *
 * <p>Typical usage from a workflow (M4+):</p>
 * <pre>{@code
 * DeploymentResult deployment = deploymentOrchestrator.requestDeployment(context);
 * if (deployment.status() != DeploymentStatus.READY) {
 *     return WorkflowResult.failed(...);
 * }
 * String previewUrl = deployment.previewUrl();
 * }</pre>
 */
@Slf4j
@Service
public class DeploymentOrchestrator {

    private final DeploymentStrategyRegistry strategyRegistry;
    private final DeploymentCallbackNotifier callbackNotifier;
    private final PrWorkflowRunService runService;
    private final DeploymentTargetService deploymentTargetService;
    private final String callbackBaseUrl;

    public DeploymentOrchestrator(DeploymentStrategyRegistry strategyRegistry,
                                  DeploymentCallbackNotifier callbackNotifier,
                                  PrWorkflowRunService runService,
                                  DeploymentTargetService deploymentTargetService,
                                  @Value("${app.public-url:http://localhost:8080}") String callbackBaseUrl) {
        this.strategyRegistry = strategyRegistry;
        this.callbackNotifier = callbackNotifier;
        this.runService = runService;
        this.deploymentTargetService = deploymentTargetService;
        this.callbackBaseUrl = callbackBaseUrl == null ? "" : callbackBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Requests a deployment for the PR the workflow context is running on.
     * Returns {@link DeploymentResult#rejected(String)} when the bot has no
     * deployment target configured or the strategy refuses the request.
     */
    public DeploymentResult requestDeployment(PrWorkflowContext context) {
        Bot bot = context.bot();
        DeploymentTarget assigned = bot.getDeploymentTarget();
        if (assigned == null) {
            return DeploymentResult.rejected(
                    "Bot '" + bot.getName() + "' has no deployment target configured");
        }
        // Re-fetch through the service so configJson is decrypted AND the
        // returned entity is detached from the persistence context — the
        // strategy must operate on plaintext, and we must never let a stray
        // flush write the cleartext back to the database.
        DeploymentTarget target = deploymentTargetService.findById(assigned.getId()).orElse(null);
        if (target == null) {
            return DeploymentResult.rejected(
                    "Deployment target id=" + assigned.getId() + " no longer exists");
        }
        DeploymentStrategy strategy = strategyRegistry.find(target.getStrategyType()).orElse(null);
        if (strategy == null) {
            return DeploymentResult.rejected("No deployment strategy registered for type "
                    + target.getStrategyType());
        }
        PrWorkflowRun run = runService.getById(context.runId());
        WebhookPayload payload = context.payload();
        long prNumber = payload.getPullRequest() == null ? run.getPrNumber()
                : payload.getPullRequest().getNumber();
        String sha = payload.getPullRequest() == null || payload.getPullRequest().getHead() == null
                ? null : payload.getPullRequest().getHead().getSha();
        String branch = payload.getPullRequest() == null || payload.getPullRequest().getHead() == null
                ? null : payload.getPullRequest().getHead().getRef();

        DeploymentRequest request = new DeploymentRequest(
                run, target,
                run.getRepoOwner(), run.getRepoName(),
                prNumber, sha, branch,
                buildCallbackUrl(run));

        DeploymentResult triggered = strategy.trigger(request);

        if (triggered.status() == DeploymentStatus.READY) {
            // Synchronous success: record the preview URL but keep the run in
            // RUNNING — never flip through WAITING_DEPLOY for an instant.
            runService.setPreviewUrl(run.getId(), triggered.previewUrl());
            return triggered;
        }
        if (triggered.status() != DeploymentStatus.PENDING) {
            // FAILED / REJECTED — surface to the workflow without persisting
            // WAITING_DEPLOY (the run stays RUNNING and the workflow will
            // complete it with the appropriate failure status).
            return triggered;
        }

        // PENDING — persist the handle and the WAITING_DEPLOY transition
        // before we start awaiting the inbound callback.
        runService.markWaitingDeploy(run.getId(), triggered.handleJson());

        if (!strategy.awaitsCallback()) {
            return triggered;
        }

        long timeoutMillis = Math.max(1_000L, target.getTimeoutSeconds() * 1000L);
        try {
            DeploymentCallbackNotifier.CallbackResult callback =
                    callbackNotifier.awaitResult(run.getId(), timeoutMillis);
            if (callback == null) {
                return DeploymentResult.failed(
                        "Timed out after " + target.getTimeoutSeconds() + "s waiting for deployment callback",
                        triggered.handleJson());
            }
            if (callback.status() == DeploymentStatus.READY) {
                runService.resumeFromDeploy(run.getId(), callback.previewUrl());
                return DeploymentResult.ready(callback.previewUrl(), triggered.handleJson());
            }
            return new DeploymentResult(callback.status(), callback.previewUrl(),
                    triggered.handleJson(), callback.errorMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeploymentResult.failed("Interrupted while awaiting deployment callback",
                    triggered.handleJson());
        }
    }

    private String buildCallbackUrl(PrWorkflowRun run) {
        return callbackBaseUrl + "/api/workflow-callback/" + run.getId() + "/" + run.getCallbackSecret();
    }
}
