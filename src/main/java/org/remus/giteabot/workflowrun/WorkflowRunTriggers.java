package org.remus.giteabot.workflowrun;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.ai.AuditTrigger;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;

/** Builds the {@link AuditTrigger} of a workflow run from the bot and the webhook payload. */
public final class WorkflowRunTriggers {

    private WorkflowRunTriggers() {
    }

    /**
     * The trigger of a run for {@code bot} handling {@code payload}. The repository may be missing
     * from an issue payload ({@code IssueWorkflowOrchestrator} accepts such payloads), so its parts
     * stay {@code null} then.
     */
    public static AuditTrigger of(Bot bot, WebhookPayload payload) {
        WebhookPayload.Repository repository = payload.getRepository();
        String owner = repository != null && repository.getOwner() != null ? repository.getOwner().getLogin() : null;
        String repo = repository != null ? repository.getName() : null;
        return new AuditTrigger(bot.getId(), bot.getName(), bot.getAiIntegration().getName(),
                bot.getGitIntegration().getName(), owner, repo,
                kindOf(payload), PrPayloadHydrator.resolvePrNumber(payload));
    }

    private static TriggerKind kindOf(WebhookPayload payload) {
        if (payload.getPullRequest() != null
                || (payload.getIssue() != null && payload.getIssue().getPullRequest() != null)) {
            return TriggerKind.PULL_REQUEST;
        }
        return payload.getIssue() != null ? TriggerKind.ISSUE : null;
    }
}
