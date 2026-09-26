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
 *                               strategy (e.g. final {@code ImplementationPlan},
 *                               {@code WriterPlan}, or {@link AgentAnswer} when
 *                               the run produced no repository change). May be
 *                               {@code null}.
 */
public record LoopOutcome(boolean success, String selectedBranch, Object payload) {

    /**
     * Payload of a run that completed <em>without</em> repository changes: the
     * model answered the issue instead of implementing it. The caller posts the
     * text as an issue comment and opens no pull request.
     *
     * @param text the model's final answer, posted verbatim
     */
    public record AgentAnswer(String text) {}

    public static LoopOutcome success(String selectedBranch, Object payload) {
        return new LoopOutcome(true, selectedBranch, payload);
    }

    /**
     * Successful run whose outcome is an answer rather than a change set. The
     * caller posts {@code text} as an issue comment instead of committing,
     * pushing and opening a pull request.
     */
    public static LoopOutcome answered(String selectedBranch, String text) {
        return new LoopOutcome(true, selectedBranch, new AgentAnswer(text));
    }

    public static LoopOutcome fail(String selectedBranch) {
        return new LoopOutcome(false, selectedBranch, null);
    }

    public static LoopOutcome fail(String selectedBranch, Object payload) {
        return new LoopOutcome(false, selectedBranch, payload);
    }
}

