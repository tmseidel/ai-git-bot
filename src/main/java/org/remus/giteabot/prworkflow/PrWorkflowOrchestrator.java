package org.remus.giteabot.prworkflow;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final PrWorkflowRunLockManager lockManager;
    private final WorkflowSelectionService workflowSelectionService;

    public PrWorkflowOrchestrator(PrWorkflowRegistry registry,
                                  PrWorkflowRunService runService,
                                  PrWorkflowMetrics metrics,
                                  PrWorkflowRunLockManager lockManager,
                                  WorkflowSelectionService workflowSelectionService) {
        this.registry = registry;
        this.runService = runService;
        this.metrics = metrics;
        this.lockManager = lockManager;
        this.workflowSelectionService = workflowSelectionService;
    }

    /**
     * Runs <em>all</em> workflows enabled for the given bot in stable order
     * (lexicographic by {@code PrWorkflow.key()}). Bots without an explicit
     * {@link org.remus.giteabot.prworkflow.config.WorkflowConfiguration} fall
     * back to the legacy single-workflow behaviour ({@link ReviewWorkflow}).
     *
     * <p>Workflows are invoked sequentially; one failing workflow does not
     * abort the remaining ones (each call is wrapped by {@link #run}).</p>
     *
     * @return the list of terminal {@link PrWorkflowRun} rows, one per
     *         invoked workflow (skipping unregistered keys with a warning)
     */
    public List<PrWorkflowRun> runAll(Bot bot, WebhookPayload payload) {
        if (bot == null) {
            throw new IllegalArgumentException("bot must not be null");
        }
        List<String> workflowKeys;
        if (bot.getWorkflowConfiguration() != null) {
            workflowKeys = workflowSelectionService.enabledWorkflowKeys(
                    bot.getWorkflowConfiguration().getId());
        } else {
            workflowKeys = List.of(ReviewWorkflow.KEY);
        }
        if (workflowKeys.isEmpty()) {
            log.debug("[Bot '{}'] No workflows enabled — skipping orchestrator", bot.getName());
            return List.of();
        }
        List<PrWorkflowRun> runs = new ArrayList<>(workflowKeys.size());
        for (String key : workflowKeys) {
            if (registry.find(key).isEmpty()) {
                log.warn("[Bot '{}'] Skipping unregistered workflow key '{}'", bot.getName(), key);
                continue;
            }
            try {
                runs.add(run(bot, payload, key));
            } catch (RuntimeException e) {
                log.error("[Bot '{}'] Workflow '{}' failed: {}", bot.getName(), key, e.getMessage(), e);
                // Don't abort remaining workflows — the per-workflow run row already
                // carries the FAILED status thanks to run(...).
            }
        }
        return runs;
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
        return run(bot, payload, workflowKey, Map.of());
    }

    /**
     * Runs the workflow identified by {@code workflowKey}, threading the
     * given {@code hints} map into the {@link PrWorkflowContext}. Hints are
     * an opt-in side-channel used by slash commands (e.g. the E2E
     * {@code @bot regenerate-tests <feedback>} dispatcher) to pass
     * free-form operator input to the workflow without polluting the
     * webhook payload model.
     */
    public PrWorkflowRun run(Bot bot, WebhookPayload payload, String workflowKey,
                             Map<String, String> hints) {
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

        PrWorkflowRun run = lockManager.withLock(bot.getId(), owner, repoName, prNumber, workflow.key(),
                () -> runService.start(bot.getId(), owner, repoName, prNumber, workflow.key()));
        log.debug("[Workflow '{}'] Started run id={} for bot={} repo={}/{} pr=#{}",
                workflow.key(), run.getId(), bot.getName(), owner, repoName, prNumber);
        Instant startInstant = run.getStartedAt() == null ? Instant.now() : run.getStartedAt();

        PrWorkflowContext context = new PrWorkflowContext(
                bot, payload, run.getId(),
                (name, log) -> runService.appendStep(run.getId(), name, "INFO", log),
                () -> !runService.isActive(run.getId()),
                hints == null ? Map.of() : hints);

        try {
            WorkflowResult result = workflow.run(context);
            if (result == null) {
                throw new IllegalStateException("PrWorkflow '" + workflow.key()
                        + "' returned a null WorkflowResult");
            }
            PrWorkflowRunStatus desired = mapTerminalStatus(result.status());
            PrWorkflowRun completed = runService.complete(run.getId(), desired, result.summary());
            // If a concurrent supersession marked the run CANCELLED while the workflow body
            // was still executing, complete() is a no-op and `completed.getStatus()` reflects
            // the persisted CANCELLED status — use that for metrics/logs, not the desired one.
            PrWorkflowRunStatus effective = completed.getStatus() != null ? completed.getStatus() : desired;
            metrics.recordRun(workflow.key(), effective, Duration.between(startInstant, Instant.now()));
            log.info("[Workflow '{}'] Finished run id={} status={} summary={}",
                    workflow.key(), completed.getId(), effective, completed.getSummary());
            return completed;
        } catch (WorkflowCancelledException cancelled) {
            // Cooperative cancellation — not a failure. The run row is already CANCELLED
            // (set by the superseding start() call); complete() is a no-op for terminal rows.
            try {
                runService.appendStep(run.getId(), "cancelled", "INFO", cancelled.getMessage());
            } catch (RuntimeException persistErr) {
                log.warn("Failed to append cancellation step for run id={}: {}",
                        run.getId(), persistErr.getMessage());
            }
            metrics.recordRun(workflow.key(), PrWorkflowRunStatus.CANCELLED,
                    Duration.between(startInstant, Instant.now()));
            log.info("[Workflow '{}'] Run id={} CANCELLED: {}",
                    workflow.key(), run.getId(), cancelled.getMessage());
            return runService.getById(run.getId());
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
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        // GitHub `issue_comment` events do not carry a top-level `pull_request`
        // object — the PR number lives on `issue.number`, and `issue.pull_request`
        // is non-null iff the issue is actually a pull request (vs. a plain issue).
        // This is what unblocks `@bot rerun-tests` / `regenerate-tests` slash commands
        // on GitHub (see E2eTestSlashCommandHandler).
        if (payload.getIssue() != null
                && payload.getIssue().getNumber() != null
                && payload.getIssue().getPullRequest() != null) {
            return payload.getIssue().getNumber();
        }
        // Final fallback: some translators set the top-level `number` field
        // directly (e.g. pull_request events).
        if (payload.getNumber() != null) {
            return payload.getNumber();
        }
        return null;
    }
}

