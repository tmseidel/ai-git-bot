package org.remus.giteabot.prworkflow;

/**
 * Strategy contract for one pluggable follow-up workflow that runs on a pull
 * request after a webhook event.
 *
 * <p>This is the central SPI of milestone M1. Implementations are registered
 * automatically through Spring DI and discovered by
 * {@link PrWorkflowRegistry}. The legacy PR-review path is the first
 * implementation ({@code org.remus.giteabot.prworkflow.review.ReviewWorkflow})
 * and continues to be enabled by default for every bot.</p>
 *
 * <p>Implementations must be idempotent and safe to call multiple times for
 * the same PR — the orchestrator already deduplicates concurrent in-flight
 * runs for the same {@code (bot, repo, prNumber, workflowKey)} tuple, but
 * workflows may still be retried after transient failures.</p>
 */
public interface PrWorkflow {

    /**
     * Stable, lowercase, kebab-case identifier persisted on
     * {@link PrWorkflowRun#getWorkflowKey()} and referenced by the future
     * workflow-configuration whitelist. Must be unique across all registered
     * workflows; collisions are rejected on startup by
     * {@link PrWorkflowRegistry}.
     */
    String key();

    /**
     * Human-readable name shown in the admin UI.
     */
    String displayName();

    /**
     * Coarse category for grouping in UI and for default-enable decisions.
     */
    PrWorkflowCategory category();

    /**
     * Executes the workflow for the given context. Must not throw checked
     * exceptions; runtime exceptions are caught by
     * {@link PrWorkflowOrchestrator} and recorded as a {@code FAILED} run.
     *
     * @param context immutable run context, never {@code null}
     * @return a non-{@code null} {@link WorkflowResult}; never {@code null}
     */
    WorkflowResult run(PrWorkflowContext context);
}

