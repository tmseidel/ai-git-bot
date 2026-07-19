package org.remus.giteabot.prworkflow;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Immutable execution context handed to {@link PrWorkflow#run(PrWorkflowContext)}.
 */
public record PrWorkflowContext(
        Bot bot,
        WebhookPayload payload,
        Long runId,
        BiConsumer<String, String> stepAppender,
        BooleanSupplier cancellationCheck,
        Map<String, String> hints,
        Consumer<AgentRunContext.ToolCallRecord> auditToolCallConsumer) {

    public static final String HINT_E2E_FEEDBACK = "e2e.feedback";
    public static final String HINT_RERUN_ONLY = "e2e.rerun-only";
    public static final String HINT_README_SYNC_GUIDANCE = "readme-sync.guidance";
    public static final String HINT_I18N_COVERAGE_GUIDANCE = "i18n-coverage.guidance";
    public static final String HINT_AGENTIC_REVIEW_CLARIFICATION = "agentic-review.clarification";

    public PrWorkflowContext {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepAppender, "stepAppender");
        Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        hints = hints == null ? Map.of() : Map.copyOf(hints);
    }

    /** Backward-compatible: no hints, no tool-call consumer. */
    public PrWorkflowContext(Bot bot,
                             WebhookPayload payload,
                             Long runId,
                             BiConsumer<String, String> stepAppender,
                             BooleanSupplier cancellationCheck) {
        this(bot, payload, runId, stepAppender, cancellationCheck, Map.of(), null);
    }

    /** Backward-compatible: hints but no tool-call consumer (test code). */
    public PrWorkflowContext(Bot bot,
                             WebhookPayload payload,
                             Long runId,
                             BiConsumer<String, String> stepAppender,
                             BooleanSupplier cancellationCheck,
                             Map<String, String> hints) {
        this(bot, payload, runId, stepAppender, cancellationCheck, hints, null);
    }

    public String hint(String key) {
        return key == null ? null : hints.get(key);
    }

    public void appendStep(String name, String logExcerpt) {
        stepAppender.accept(name, logExcerpt);
    }

    public boolean isCancelled() {
        return cancellationCheck.getAsBoolean();
    }

    public void requireActive(String location) {
        if (isCancelled()) {
            throw new WorkflowCancelledException(
                    "Run " + runId + " was superseded before: "
                            + (location == null ? "(unspecified location)" : location));
        }
    }
}
