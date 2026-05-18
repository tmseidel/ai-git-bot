package org.remus.giteabot.prworkflow;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Immutable execution context handed to {@link PrWorkflow#run(PrWorkflowContext)}.
 *
 * <p>Contains everything a workflow implementation needs to act on a single
 * pull-request webhook event without reaching back into Spring or the bot
 * persistence layer:</p>
 * <ul>
 *     <li>{@link #bot()} — the persisted {@link Bot} with all its integrations
 *     eagerly loaded.</li>
 *     <li>{@link #payload()} — the normalised webhook payload that triggered
 *     the run.</li>
 *     <li>{@link #runId()} — the database id of the corresponding
 *     {@link PrWorkflowRun} row, useful for callback URLs and structured
 *     logging.</li>
 *     <li>{@link #appendStep(String, String)} — append-only step log; the
 *     workflow can use it to record progress without owning a repository
 *     reference itself.</li>
 * </ul>
 *
 * <p>In milestones M3+ this record gains accessors for the resolved
 * deployment target, per-workflow params and the callback secret. The
 * existing accessors stay backwards-compatible.</p>
 */
public record PrWorkflowContext(
        Bot bot,
        WebhookPayload payload,
        Long runId,
        BiConsumer<String, String> stepAppender) {

    public PrWorkflowContext {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepAppender, "stepAppender");
    }

    /**
     * Records an informational step on the current {@link PrWorkflowRun}.
     *
     * @param name short, human-readable step name (e.g. {@code "fetch-diff"})
     * @param logExcerpt up to a few KB of structured output; the orchestrator
     *                   truncates excessively long values
     */
    public void appendStep(String name, String logExcerpt) {
        stepAppender.accept(name, logExcerpt);
    }
}

