package org.remus.giteabot.prworkflow;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Coordinates one invocation of a registered {@link PrWorkflow}.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *     <li>Resolve the workflow by key via {@link PrWorkflowRegistry}.</li>
 *     <li>Open a {@link PrWorkflowRun} row (cancelling any superseded
 *     in-flight runs for the same PR).</li>
 *     <li>Build the immutable {@link PrWorkflowContext} and invoke
 *     {@link PrWorkflow#run(PrWorkflowContext)}.</li>
 *     <li>Translate the returned {@link WorkflowResult} (or any thrown
 *     exception) into a terminal {@link PrWorkflowRunStatus}, persist it and
 *     emit metrics.</li>
 * </ol>
 *
 * <p>The orchestrator is deliberately the <em>only</em> caller of the
 * {@link PrWorkflow} contract — callers must always go through
 * {@link #run(Bot, WebhookPayload, String)} so that lifecycle management,
 * error capture and telemetry stay centralised.</p>
 */
@Slf4j
@Service
public class PrWorkflowOrchestrator {

    private final PrWorkflowRegistry registry;
    private final PrWorkflowRunService runService;
    private final PrWorkflowMetrics metrics;

    public PrWorkflowOrchestrator(PrWorkflowRegistry registry,
                                  PrWorkflowRunService runService,
                                  PrWorkflowMetrics metrics) {
        this.registry = registry;
        this.runService = runService;
        this.metrics = metrics;
    }

    /**
     * Runs the workflow identified by {@code workflowKey} for the given bot
     * and webhook payload. Any {@link RuntimeException} thrown by the
     * workflow is caught, recorded as a {@code FAILED} run and rethrown so
     * the calling {@code @Async} handler in
     * {@link org.remus.giteabot.admin.BotWebhookService} can still record the
     * error against the bot via {@code BotService.recordError(...)}.
     *
     * @return the terminal {@link PrWorkflowRun} (status {@code SUCCESS},
     *         {@code FAILED} or {@code WAITING_DEPLOY})
     */
    public PrWorkflowRun run(Bot bot, WebhookPayload payload, String workflowKey) {
        if (bot == null) {
            throw new IllegalArgumentException("bot must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        PrWorkflow workflow = registry.require(workflowKey);

        String owner = resolveOwner(payload);
        String repoName = resolveRepoName(payload);
        Long prNumber = resolvePrNumber(payload);
        if (owner == null || repoName == null || prNumber == null) {
            throw new IllegalArgumentException("Webhook payload is missing repository/PR identity (" +
                    "owner=" + owner + ", repo=" + repoName + ", pr=" + prNumber + ")");
        }

        PrWorkflowRun run = runService.start(bot.getId(), owner, repoName, prNumber, workflow.key());
        log.debug("[Workflow '{}'] Started run id={} for bot={} repo={}/{} pr=#{}",
                workflow.key(), run.getId(), bot.getName(), owner, repoName, prNumber);
        Instant startInstant = run.getStartedAt() == null ? Instant.now() : run.getStartedAt();

        PrWorkflowContext context = new PrWorkflowContext(
                bot, payload, run.getId(),
                (name, log) -> runService.appendStep(run.getId(), name, "INFO", log));

        try {
            WorkflowResult result = workflow.run(context);
            if (result == null) {
                throw new IllegalStateException("PrWorkflow '" + workflow.key()
                        + "' returned a null WorkflowResult");
            }
            PrWorkflowRunStatus status = mapTerminalStatus(result.status());
            PrWorkflowRun completed = runService.complete(run.getId(), status, result.summary());
            metrics.recordRun(workflow.key(), status, Duration.between(startInstant, Instant.now()));
            log.info("[Workflow '{}'] Finished run id={} status={} summary={}",
                    workflow.key(), completed.getId(), completed.getStatus(), completed.getSummary());
            return completed;
        } catch (RuntimeException e) {
            String summary = "Workflow failed: " + truncateForSummary(e.getMessage());
            try {
                runService.appendStep(run.getId(), "exception", "ERROR", stackTraceSnippet(e));
                runService.complete(run.getId(), PrWorkflowRunStatus.FAILED, summary);
            } catch (RuntimeException persistErr) {
                log.error("Failed to persist FAILED status for run id={} (original error: {})",
                        run.getId(), e.getMessage(), persistErr);
            }
            metrics.recordRun(workflow.key(), PrWorkflowRunStatus.FAILED,
                    Duration.between(startInstant, Instant.now()));
            log.error("[Workflow '{}'] Run id={} FAILED: {}", workflow.key(), run.getId(),
                    e.getMessage(), e);
            throw e;
        }
    }

    private PrWorkflowRunStatus mapTerminalStatus(WorkflowResultStatus status) {
        return switch (status) {
            case SUCCESS, SKIPPED -> PrWorkflowRunStatus.SUCCESS;
            case FAILED -> PrWorkflowRunStatus.FAILED;
            case WAITING_DEPLOY -> PrWorkflowRunStatus.WAITING_DEPLOY;
        };
    }

    private String stackTraceSnippet(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        StackTraceElement[] trace = t.getStackTrace();
        int limit = Math.min(20, trace.length);
        for (int i = 0; i < limit; i++) {
            sb.append("\tat ").append(trace[i]).append('\n');
        }
        return sb.toString();
    }

    private String truncateForSummary(String value) {
        if (value == null) {
            return "(no message)";
        }
        return value.length() > 256 ? value.substring(0, 253) + "..." : value;
    }

    private String resolveOwner(WebhookPayload payload) {
        if (payload.getRepository() != null && payload.getRepository().getOwner() != null) {
            return payload.getRepository().getOwner().getLogin();
        }
        return null;
    }

    private String resolveRepoName(WebhookPayload payload) {
        if (payload.getRepository() != null) {
            return payload.getRepository().getName();
        }
        return null;
    }

    private Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null) {
            return payload.getPullRequest().getNumber();
        }
        return null;
    }
}

