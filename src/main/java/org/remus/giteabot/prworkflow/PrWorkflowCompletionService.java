package org.remus.giteabot.prworkflow;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.audit.ActorType;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Atomic, replay-safe generic lifecycle completion; does not infer review/finding semantics. */
@Service
@RequiredArgsConstructor
public class PrWorkflowCompletionService {

    private final PrWorkflowRunRepository runRepository;
    private final PrAuditEventService auditService;
    private final EventHookPublisher eventHookPublisher;
    private final EntityManager entityManager;

    /**
     * Completes the current run model under a DB run lock. May join a domain owner's transaction
     * containing additional required audits/deliveries, but must precede its run-entity mutations.
     * Identical terminal replay returns {@code applied=false} before any required audit/outbox helper,
     * even after their ordinary retention. A terminal run without a durable receipt fails closed.
     * Domain owners must return on replay before adding events; applied domain events must join
     * the same outer transaction. RR17 must first bind the full immutable PublicationCommand/ledger
     * and enforce its publication/fence checks; this receipt proves only generic completion intent.
     * Conflicting requests and any failed insert abort.
     * No HTTP operation is performed while holding the lock.
     */
    @Transactional
    public PrWorkflowCompletionResult complete(Long runId, PrWorkflowRunStatus expectedStatus, PrWorkflowRunStatus status,
                                                String summary, Bot bot) {
        if (expectedStatus == null || !expectedStatus.isActive() || status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("Completion requires an expected active and a terminal target status");
        }
        if (summary != null && summary.length() > PrWorkflowRunService.MAX_SUMMARY_CHARS) {
            throw new IllegalArgumentException("Completion summary exceeds the run summary limit");
        }
        Objects.requireNonNull(bot, "bot");
        PrWorkflowRun run = runRepository.findByIdForUpdate(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        // A query lock alone can return an entity cached before another transaction completed it.
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(run.getBotId(), bot.getId())) {
            throw new IllegalArgumentException("Completion does not belong to the bot");
        }
        String completionHash = completionHash(run, status, summary);
        if (run.getStatus().isTerminal()) {
            if (run.getStatus() != status || !Objects.equals(run.getSummary(), summary)
                    || !completionHash.equals(run.getRequiredCompletionHash())) {
                throw new IllegalStateException("Conflicting or non-required terminal completion for run " + runId);
            }
            return new PrWorkflowCompletionResult(run, false);
        }
        if (run.getRequiredCompletionHash() != null) {
            throw new IllegalStateException("Nonterminal run already has a required completion receipt: " + runId);
        }
        if (run.getStatus() != expectedStatus) {
            throw new OptimisticLockingFailureException("Unexpected workflow run status: " + run.getStatus());
        }
        // shortcut: status-only admission; RR04/RR17 must add control/lane/version/fence checks before activation.
        PrWorkflowEventKey key = new PrWorkflowEventKey(AuditEventType.PR_WORKFLOW_RUN_COMPLETED, runId, null);
        Instant finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        auditService.recordRequired(key, PrAuditEvent.builder()
                .eventType(key.eventType()).eventTimestamp(finishedAt)
                .botId(run.getBotId()).repoOwner(run.getRepoOwner()).repoName(run.getRepoName())
                .prNumber(run.getPrNumber()).runId(runId)
                .actorType(ActorType.SYSTEM.name()).actorId("workflow-completion")
                .eventPayloadJson(PrAuditEventService.toJsonRequired(Map.of(
                        "workflow_key", run.getWorkflowKey(),
                        "run_status", status.name(), "summary", summary == null ? "" : summary)))
                .build());
        if (status != PrWorkflowRunStatus.CANCELLED) {
            eventHookPublisher.enqueueRequired(key, status == PrWorkflowRunStatus.SUCCESS
                            ? EventHookEventType.PR_WORKFLOW_COMPLETED : EventHookEventType.PR_WORKFLOW_FAILED,
                    bot, Map.of("workflowKey", run.getWorkflowKey(), "runId", runId,
                            "status", status.name(), "summary", summary == null ? "" : summary));
        }
        run.setStatus(status);
        run.setSummary(summary);
        run.setFinishedAt(finishedAt);
        run.setRequiredCompletionHash(completionHash);
        return new PrWorkflowCompletionResult(runRepository.saveAndFlush(run), true);
    }

    private static String completionHash(PrWorkflowRun run, PrWorkflowRunStatus status, String summary) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("runId", run.getId());
        intent.put("botId", run.getBotId());
        intent.put("repoOwner", run.getRepoOwner());
        intent.put("repoName", run.getRepoName());
        intent.put("prNumber", run.getPrNumber());
        intent.put("workflowKey", run.getWorkflowKey());
        intent.put("status", status.name());
        intent.put("summary", summary);
        try {
            byte[] bytes = PrAuditEventService.toJsonRequired(intent).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
