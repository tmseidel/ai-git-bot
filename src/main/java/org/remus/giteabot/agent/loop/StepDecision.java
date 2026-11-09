package org.remus.giteabot.agent.loop;

import java.util.List;

/**
 * Result of a single {@link AgentStrategy#step} invocation.
 *
 * <p>The decision is intentionally minimal: continue with another AI call —
 * either with a plain user message or with native tool results — or terminate
 * the loop with a final outcome. Strategies are responsible for executing
 * tools, posting comments, and tracking sub-budgets internally before
 * returning the next step.</p>
 */
public sealed interface StepDecision {

    /** Continue the loop. The given prompt will be sent as the next user turn. */
    record Continue(String nextUserMessage) implements StepDecision {}

    /**
     * Continue the loop after a native tool-call round. The {@link AgentLoop}
     * will append the previous assistant turn (carrying its {@code tool_calls})
     * and one {@code tool}-role message per {@link ToolCallResult} to the
     * conversation history, then call the AI again. The optional
     * {@code nextUserMessage} is added as a follow-up user turn after the tool
     * results when non-blank (rarely needed — the model usually drives the
     * next turn solely from the tool outputs).
     *
     * <p>Only emitted by strategies running in {@link ToolingMode#NATIVE}.</p>
     */
    record ContinueWithToolResults(List<ToolCallResult> results,
                                   String nextUserMessage) implements StepDecision {

        public ContinueWithToolResults {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** Pairing of a native tool-call id with its textual result for the model. */
    record ToolCallResult(String toolCallId, String resultText) {}

    /**
     * Terminate the loop with the given outcome (success, ask-user, fail, …).
     *
     * <p>{@code executedCalls} carries the results of the tool calls the terminal
     * round announced and ran. A strategy that executes tools and then decides to
     * stop — the coding agent does exactly that as soon as a mutation leaves a
     * clean diff — must hand them over, because the {@link AgentLoop} is the only
     * place that writes the round to the session. Without them the session ends on
     * an assistant turn whose {@code tool_calls} no tool row answers:
     * {@code toAiMessages} then has to drop the payload (providers reject a request
     * that breaks the pair contract), and the follow-up run sees the agent announce
     * an edit it never reports the outcome of — so it makes the same edit again.</p>
     *
     * <p>Rounds that announce calls without executing them (the give-up branches)
     * pass a synthetic "not executed" result per call for the same reason: every
     * announced id needs an answer to stay replayable.</p>
     */
    record Finish(LoopOutcome outcome, List<ToolCallResult> executedCalls) implements StepDecision {

        public Finish {
            executedCalls = executedCalls == null ? List.of() : List.copyOf(executedCalls);
        }

        /** Terminate without results — for rounds that announced no tool calls. */
        public Finish(LoopOutcome outcome) {
            this(outcome, List.of());
        }
    }
}

