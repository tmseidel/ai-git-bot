package org.remus.giteabot.prworkflow;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

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
 *     <li>{@link #isCancelled()} / {@link #requireActive(String)} —
 *     cooperative cancellation checks; workflows <strong>must</strong>
 *     consult them before any external side effect (PR comments, status
 *     checks, deployment triggers, …) so a superseded run cannot race
 *     against the freshly started one.</li>
 * </ul>
 *
 * <p>In milestones M3+ this record gains accessors for the resolved
 * deployment target, per-workflow params and the callback secret.</p>
 */
public record PrWorkflowContext(
        Bot bot,
        WebhookPayload payload,
        Long runId,
        BiConsumer<String, String> stepAppender,
        BooleanSupplier cancellationCheck,
        Map<String, String> hints) {

    /**
     * Conventional key under which {@link org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler}
     * threads the free-text from {@code @bot regenerate-tests <feedback>} so
     * {@link org.remus.giteabot.prworkflow.e2e.E2ETestWorkflow} can hand it
     * to the planner.
     */
    public static final String HINT_E2E_FEEDBACK = "e2e.feedback";

    public PrWorkflowContext {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepAppender, "stepAppender");
        Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        hints = hints == null ? Map.of() : Map.copyOf(hints);
    }

    /**
     * Backwards-compatible constructor that omits the optional
     * {@code hints} map. New call sites should prefer the canonical
     * constructor and pass an explicit hints map (use {@link Map#of()}
     * for "no hints").
     */
    public PrWorkflowContext(Bot bot,
                             WebhookPayload payload,
                             Long runId,
                             BiConsumer<String, String> stepAppender,
                             BooleanSupplier cancellationCheck) {
        this(bot, payload, runId, stepAppender, cancellationCheck, Map.of());
    }

    /**
     * Returns the value of {@code key} from the optional {@link #hints()}
     * map, or {@code null} when absent. Convenience wrapper so workflows
     * don't have to null-check the map twice.
     */
    public String hint(String key) {
        return key == null ? null : hints.get(key);
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

    /**
     * Returns {@code true} when the orchestrator has marked the current run
     * as superseded by a newer one for the same pull request. The check
     * involves a database read and is intended for use at strategic points
     * inside long-running workflows (between LLM calls, before external
     * side-effects), not in a tight loop.
     */
    public boolean isCancelled() {
        return cancellationCheck.getAsBoolean();
    }

    /**
     * Convenience wrapper around {@link #isCancelled()} that throws a
     * {@link WorkflowCancelledException} (caught and recorded as
     * {@link PrWorkflowRunStatus#CANCELLED} by the orchestrator) when the
     * run has been superseded. Use this before any irreversible external
     * action, for example:
     * <pre>{@code
     * context.requireActive("before posting PR comment");
     * repoClient.postReviewComment(...);
     * }</pre>
     */
    public void requireActive(String location) {
        if (isCancelled()) {
            throw new WorkflowCancelledException(
                    "Run " + runId + " was superseded before: "
                            + (location == null ? "(unspecified location)" : location));
        }
    }
}

