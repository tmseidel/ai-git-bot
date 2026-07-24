package org.remus.giteabot.prworkflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.audit.ActorType;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrWorkflowOrchestrator {

    private final PrWorkflowRegistry registry;
    private final PrWorkflowRunService runService;
    private final PrWorkflowMetrics metrics;
    private final PrWorkflowRunLockManager lockManager;
    private final WorkflowSelectionService workflowSelectionService;
    private final PrAuditEventService auditService;

    public List<PrWorkflowRun> runAll(Bot bot, WebhookPayload payload) {
        if (bot == null) throw new IllegalArgumentException("bot must not be null");
        List<String> workflowKeys;
        if (bot.getWorkflowConfiguration() != null) {
            workflowKeys = workflowSelectionService.enabledWorkflowKeys(
                    bot.getWorkflowConfiguration().getId());
        } else {
            workflowKeys = List.of(ReviewWorkflow.KEY);
        }
        if (workflowKeys.isEmpty()) {
            log.debug("[Bot '{}'] No workflows enabled", bot.getName());
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
            }
        }
        return runs;
    }

    public PrWorkflowRun run(Bot bot, WebhookPayload payload, String workflowKey) {
        return run(bot, payload, workflowKey, Map.of());
    }

    public PrWorkflowRun run(Bot bot, WebhookPayload payload, String workflowKey,
                             Map<String, String> hints) {
        if (bot == null) throw new IllegalArgumentException("bot must not be null");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        PrWorkflow workflow = registry.require(workflowKey);

        String owner = resolveOwner(payload);
        String repoName = resolveRepoName(payload);
        Long prNumber = resolvePrNumber(payload);
        if (owner == null || repoName == null || prNumber == null) {
            throw new IllegalArgumentException("Webhook payload is missing repository/PR identity ("
                    + "owner=" + owner + ", repo=" + repoName + ", pr=" + prNumber + ")");
        }

        PrWorkflowRun run = lockManager.withLock(bot.getId(), owner, repoName, prNumber, workflow.key(),
                () -> runService.start(bot.getId(), owner, repoName, prNumber, workflow.key()));
        log.debug("[Workflow '{}'] Started run id={}", workflow.key(), run.getId());

        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .eventTimestamp(run.getStartedAt())
                .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                .runId(run.getId())
                .actorType(ActorType.SYSTEM.name()).actorId("orchestrator")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                        "workflow_key", workflow.key(), "trigger", "webhook")))
                .build());

        Instant startInstant = run.getStartedAt() == null ? Instant.now() : run.getStartedAt();

        Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer = record ->
            auditService.emitToolCall(bot.getId(), owner, repoName, prNumber, run.getId(),
                    null, null,
                    record.round(), record.toolName(), record.arguments(),
                    record.resultExcerpt(), record.success(),
                    record.durationMs(), record.inputTokens(), record.outputTokens());

        PrWorkflowContext context = new PrWorkflowContext(bot, payload, run.getId(),
                (name, log) -> {
                    PrWorkflowRun r = runService.appendStep(run.getId(), name, "INFO", log);
                    if (!r.getSteps().isEmpty()) {
                        PrWorkflowStep last = r.getSteps().getLast();
                        auditService.record(PrAuditEvent.builder()
                                .eventType(AuditEventType.PR_WORKFLOW_STEP_APPENDED)
                                .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                                .runId(run.getId())
                                .actorType(ActorType.SYSTEM.name()).actorId("workflow-step")
                                .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                                        "run_id", run.getId(), "step_order", last.getStepOrder())))
                                .stepIndex(last.getStepOrder()).stepName(last.getName()).stepStatus(last.getStatus())
                                .build());
                    }
                },
                () -> !runService.isActive(run.getId()),
                hints == null ? Map.of() : hints,
                toolCallConsumer);

        try {
            WorkflowResult result = workflow.run(context);
            if (result == null) {
                throw new IllegalStateException("PrWorkflow '" + workflow.key() + "' returned null");
            }
            PrWorkflowRunStatus desired = mapTerminalStatus(result.status());
            PrWorkflowRun completed = runService.complete(run.getId(), desired, result.summary());
            PrWorkflowRunStatus effective = completed.getStatus() != null ? completed.getStatus() : desired;
            metrics.recordRun(workflow.key(), effective, Duration.between(startInstant, Instant.now()));

            auditService.record(PrAuditEvent.builder()
                    .eventType(AuditEventType.PR_WORKFLOW_RUN_COMPLETED)
                    .eventTimestamp(Instant.now())
                    .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                    .runId(run.getId())
                    .actorType(ActorType.SYSTEM.name()).actorId("orchestrator")
                    .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                            "run_status", effective.name(), "workflow_key", workflow.key(),
                            "summary", completed.getSummary() != null ? completed.getSummary() : "")))
                    .durationMs(Duration.between(startInstant, Instant.now()).toMillis())
                    .build());

            if (workflow.category() == PrWorkflowCategory.REVIEW) {
                auditService.record(PrAuditEvent.builder()
                        .eventType(AuditEventType.REVIEW_COMPLETED)
                        .eventTimestamp(Instant.now())
                        .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                        .runId(run.getId())
                        .actorType(ActorType.BOT.name()).actorId(bot.getName())
                        .eventPayloadJson(PrAuditEventService.toJson(Map.of("workflow_key", workflow.key())))
                        .build());

                auditService.record(PrAuditEvent.builder()
                        .eventType(AuditEventType.FINDING_POSTED)
                        .eventTimestamp(Instant.now())
                        .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                        .runId(run.getId())
                        .actorType(ActorType.BOT.name()).actorId(bot.getName())
                        .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                                "workflow_key", workflow.key(),
                                "note", "v1 proxy: one event per posted review")))
                        .build());
            }

            log.info("[Workflow '{}'] Finished run id={} status={}", workflow.key(), completed.getId(), effective);
            return completed;
        } catch (WorkflowCancelledException cancelled) {
            try {
                runService.appendStep(run.getId(), "cancelled", "INFO", cancelled.getMessage());
            } catch (RuntimeException persistErr) {
                log.warn("Failed to append cancellation step: {}", persistErr.getMessage());
            }
            metrics.recordRun(workflow.key(), PrWorkflowRunStatus.CANCELLED,
                    Duration.between(startInstant, Instant.now()));
            auditService.record(PrAuditEvent.builder()
                    .eventType(AuditEventType.PR_WORKFLOW_RUN_COMPLETED)
                    .eventTimestamp(Instant.now())
                    .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                    .runId(run.getId())
                    .actorType(ActorType.SYSTEM.name()).actorId("orchestrator")
                    .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                            "run_status", "CANCELLED", "workflow_key", workflow.key(),
                            "reason", cancelled.getMessage())))
                    .build());
            log.info("[Workflow '{}'] Run id={} CANCELLED", workflow.key(), run.getId());
            return runService.getById(run.getId());
        } catch (RuntimeException e) {
            String summary = "Workflow failed: " + truncateForSummary(e.getMessage());
            try {
                runService.appendStep(run.getId(), "exception", "ERROR", stackTraceSnippet(e));
                runService.complete(run.getId(), PrWorkflowRunStatus.FAILED, summary);
            } catch (RuntimeException persistErr) {
                log.error("Failed to persist FAILED status for run id={}", run.getId(), persistErr);
            }
            metrics.recordRun(workflow.key(), PrWorkflowRunStatus.FAILED,
                    Duration.between(startInstant, Instant.now()));
            auditService.record(PrAuditEvent.builder()
                    .eventType(AuditEventType.PR_WORKFLOW_RUN_COMPLETED)
                    .eventTimestamp(Instant.now())
                    .botId(bot.getId()).repoOwner(owner).repoName(repoName).prNumber(prNumber)
                    .runId(context.runId())
                    .actorType(ActorType.SYSTEM.name()).actorId("orchestrator")
                    .eventPayloadJson(PrAuditEventService.toJson(Map.of(
                            "run_status", "FAILED", "workflow_key", workflow.key(),
                            "error", truncateForSummary(e.getMessage()))))
                    .build());
            log.error("[Workflow '{}'] Run id={} FAILED", workflow.key(), run.getId(), e);
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
        for (int i = 0; i < limit; i++) sb.append("\tat ").append(trace[i]).append('\n');
        return sb.toString();
    }

    private String truncateForSummary(String value) {
        if (value == null) return "(no message)";
        return value.length() > 256 ? value.substring(0, 253) + "..." : value;
    }

    private String resolveOwner(WebhookPayload payload) {
        if (payload.getRepository() != null && payload.getRepository().getOwner() != null)
            return payload.getRepository().getOwner().getLogin();
        return null;
    }

    private String resolveRepoName(WebhookPayload payload) {
        if (payload.getRepository() != null) return payload.getRepository().getName();
        return null;
    }

    private Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null)
            return payload.getPullRequest().getNumber();
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null
                && payload.getIssue().getPullRequest() != null)
            return payload.getIssue().getNumber();
        if (payload.getNumber() != null) return payload.getNumber();
        return null;
    }
}
