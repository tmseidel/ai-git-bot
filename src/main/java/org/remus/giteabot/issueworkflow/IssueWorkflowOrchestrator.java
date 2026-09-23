package org.remus.giteabot.issueworkflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.ai.AiRetryContext;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.notification.IssueCommentAcknowledgement;
import org.remus.giteabot.notification.WorkflowRetryNotices;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration-driven entry point for issue-assigned and issue-comment
 * handling — the issue-side counterpart of
 * {@code org.remus.giteabot.prworkflow.PrWorkflowOrchestrator}. Resolves the
 * enabled {@link IssueWorkflow}(s) from the bot's issue-assigned
 * {@link WorkflowConfiguration} (kind {@code ISSUE}) and owns the run
 * lifecycle:
 *
 * <ul>
 *   <li>{@link #runAssigned} publishes {@code issueassignment.started} before
 *       each delegation (with the issue title), {@code issueassignment.completed}
 *       on success, and {@code issueassignment.failed} + a bot error record on
 *       exception — the exact lifecycle that used to be inlined in
 *       {@code BotWebhookService.handleIssueAssigned}.</li>
 *   <li>{@link #runComment} delegates follow-up comments through the same
 *       resolved workflows; failures only record a bot error (no outgoing
 *       events), matching the legacy behavior.</li>
 * </ul>
 *
 * <p>A bot without an issue-assigned configuration (or with one that enables
 * no workflows) is a logged no-op. Unlike the PR side there is no run/step
 * persistence for issue workflows — intentionally unchanged from the legacy
 * behavior.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueWorkflowOrchestrator {

    private final IssueWorkflowRegistry registry;
    private final WorkflowSelectionService workflowSelectionService;
    private final BotService botService;
    private final EventHookPublisher eventHookPublisher;
    private final WorkflowRetryNotices retryNotices;
    private final IssueCommentAcknowledgement commentAcknowledgement;

    /**
     * Runs every issue workflow enabled on the bot's issue-assigned
     * configuration for an issue-assigned event. Each workflow gets its own
     * started/completed/failed event cycle, and a failure in one workflow
     * does not prevent the remaining ones from running.
     */
    public void runAssigned(Bot bot, WebhookPayload payload) {
        IssueRef issue = issueRef(payload);
        for (IssueWorkflow workflow : resolveWorkflows(bot)) {
            try {
                retryNotices.installForIssue(bot, workflow.key(), issue.owner(), issue.repo(), issue.number());
                publishIssueEvent(EventHookEventType.ISSUE_ASSIGNMENT_STARTED, bot, payload, null, true);
                workflow.onIssueAssigned(context(bot, payload, workflow.key()));
                publishIssueEvent(EventHookEventType.ISSUE_ASSIGNMENT_COMPLETED, bot, payload, null, false);
            } catch (Exception e) {
                log.error("[Bot '{}'] Issue workflow '{}' failed on issue assignment: {}",
                        bot.getName(), workflow.key(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
                publishIssueEvent(EventHookEventType.ISSUE_ASSIGNMENT_FAILED, bot, payload,
                        e.getMessage(), false);
            } finally {
                AiRetryContext.clear();
            }
        }
    }

    /**
     * Routes a follow-up issue comment through every issue workflow enabled
     * on the bot's issue-assigned configuration. The comment is acknowledged
     * with a best-effort 👀 reaction (uniform for all issue workflows, the
     * same pattern the slash-command handlers use). Failures record a bot
     * error only (no outgoing events, matching the legacy behavior) and do
     * not prevent the remaining workflows from running.
     */
    public void runComment(Bot bot, WebhookPayload payload) {
        List<IssueWorkflow> workflows = resolveWorkflows(bot);
        if (workflows.isEmpty()) {
            return;
        }
        // Acknowledge immediately with 👀 so the operator knows the bot saw
        // the comment — the same pattern the slash-command handlers use.
        commentAcknowledgement.acknowledge(bot, payload);
        IssueRef issue = issueRef(payload);
        for (IssueWorkflow workflow : workflows) {
            try {
                // Installed inside the guarded region (see runAssigned): clearing it in the
                // finally below is what keeps the notice off the next task on this thread.
                retryNotices.installForIssue(bot, workflow.key(), issue.owner(), issue.repo(), issue.number());
                workflow.onIssueComment(context(bot, payload, workflow.key()));
            } catch (Exception e) {
                log.error("[Bot '{}'] Issue workflow '{}' failed on issue comment: {}",
                        bot.getName(), workflow.key(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            } finally {
                AiRetryContext.clear();
            }
        }
    }

    /**
     * Repository coordinates of the issue this payload refers to. Individual
     * fields stay {@code null} when the payload does not carry them; the
     * retry-notice component ignores an incomplete target.
     */
    private static IssueRef issueRef(WebhookPayload payload) {
        return new IssueRef(
                payload.getRepository() != null && payload.getRepository().getOwner() != null
                        ? payload.getRepository().getOwner().getLogin() : null,
                payload.getRepository() != null ? payload.getRepository().getName() : null,
                payload.getIssue() != null ? payload.getIssue().getNumber() : null);
    }

    /**
     * Resolves the enabled issue workflows for the bot in stable (key-ordered)
     * sequence. An empty list means "nothing to do": the bot has no
     * issue-assigned configuration, the configuration enables no workflows,
     * or none of the enabled keys are registered anymore.
     */
    private List<IssueWorkflow> resolveWorkflows(Bot bot) {
        WorkflowConfiguration configuration = bot.getIssueWorkflowConfiguration();
        if (configuration == null) {
            log.debug("[Bot '{}'] No issue-assigned workflow configuration — ignoring issue event",
                    bot.getName());
            return List.of();
        }
        List<String> keys = workflowSelectionService.enabledWorkflowKeys(configuration.getId());
        if (keys.isEmpty()) {
            log.debug("[Bot '{}'] Issue-assigned configuration '{}' enables no workflows",
                    bot.getName(), configuration.getName());
            return List.of();
        }
        List<IssueWorkflow> workflows = new ArrayList<>(keys.size());
        for (String key : keys) {
            var found = registry.find(key);
            if (found.isEmpty()) {
                log.warn("[Bot '{}'] Skipping unregistered issue workflow key '{}'", bot.getName(), key);
                continue;
            }
            workflows.add(found.get());
        }
        return workflows;
    }

    private IssueWorkflowContext context(Bot bot, WebhookPayload payload, String workflowKey) {
        Map<String, Object> params = workflowSelectionService.resolveParams(
                bot.getIssueWorkflowConfiguration().getId(), workflowKey);
        return new IssueWorkflowContext(bot, payload, params);
    }

    /**
     * Emits an outgoing-webhook event for an issue assignment: STARTED before
     * the delegation (with the issue title), COMPLETED on successful return,
     * FAILED in the catch (with the error). The issue number rides in the
     * envelope's issue slot; {@code pullRequest} stays null. The publisher
     * never throws.
     */
    private void publishIssueEvent(EventHookEventType type, Bot bot, WebhookPayload payload,
                                   String error, boolean includeTitle) {
        String owner = payload.getRepository() != null && payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository() != null ? payload.getRepository().getName() : null;
        Long issueNumber = payload.getIssue() != null ? payload.getIssue().getNumber() : null;
        Map<String, Object> data = new LinkedHashMap<>();
        if (issueNumber != null) {
            data.put("issueNumber", issueNumber);
        }
        if (includeTitle && payload.getIssue() != null && payload.getIssue().getTitle() != null) {
            data.put("issueTitle", payload.getIssue().getTitle());
        }
        if (error != null) {
            data.put("error", error);
        }
        eventHookPublisher.publish(type, bot, owner, repo, null, issueNumber, data);
    }

    /** Repository coordinates of a webhook payload's issue; any field may be {@code null}. */
    private record IssueRef(String owner, String repo, Long number) {
    }
}
