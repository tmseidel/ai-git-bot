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

    /** Terminate the loop with the given outcome (success, ask-user, fail, …). */
    record Finish(LoopOutcome outcome) implements StepDecision {}
}

