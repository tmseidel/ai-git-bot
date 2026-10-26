package org.remus.giteabot.issueworkflow;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;

import java.util.Map;

/**
 * Immutable context handed to an {@link IssueWorkflow} execution.
 *
 * @param bot     the persisted bot the webhook was routed to; never
 *                {@code null}
 * @param payload the raw webhook payload (issue-assigned or issue-comment
 *                event); never {@code null}
 * @param params  the workflow's persisted parameters for the bot's
 *                issue-assigned configuration, type-coerced according to the
 *                workflow's {@code paramsSchema()}; never {@code null}
 *                (empty when the workflow declares no parameters)
 */
public record IssueWorkflowContext(Bot bot, WebhookPayload payload, Map<String, Object> params) {

    public IssueWorkflowContext {
        if (bot == null) {
            throw new IllegalArgumentException("bot must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
