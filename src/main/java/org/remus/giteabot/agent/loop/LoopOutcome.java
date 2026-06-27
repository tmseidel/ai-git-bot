package org.remus.giteabot.agent.loop;

/**
 * Final outcome of an {@link AgentLoop} run, returned to the orchestrating
 * service so it can perform the agent-specific final action (commit + PR for
 * the coding agent, post comment / create issue for the writer agent, …).
 *
 * @param success                {@code true} when the strategy considers the
 *                               run successful and the caller may perform the
 *                               final domain action (commit + push, create
 *                               issue, …).
 * @param selectedBranch         branch ultimately used by context lookups; may
 *                               differ from the initial branch when the AI
 *                               requested a {@code branch-switcher} call.
 * @param payload                opaque domain payload returned by the
 *                               strategy (e.g. final {@code ImplementationPlan}
 *                               or {@code WriterPlan}). May be {@code null}.
 */
public record LoopOutcome(boolean success, String selectedBranch, Object payload) {

    public static LoopOutcome success(String selectedBranch, Object payload) {
        return new LoopOutcome(true, selectedBranch, payload);
    }

    public static LoopOutcome fail(String selectedBranch) {
        return new LoopOutcome(false, selectedBranch, null);
    }

    public static LoopOutcome fail(String selectedBranch, Object payload) {
        return new LoopOutcome(false, selectedBranch, payload);
    }
}

