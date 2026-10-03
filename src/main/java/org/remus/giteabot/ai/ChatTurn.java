package org.remus.giteabot.ai;

import java.util.List;

/**
 * Result of a single round-trip to the AI provider, with or without native
 * tools. {@code assistantText} may be empty for tool-only or incomplete turns.
 *
 * <p>{@code inputTokens} and {@code outputTokens} are populated when the
 * provider returns usage data; they default to 0 when unknown. The corresponding
 * reported flags distinguish explicit zero from unavailable counters.</p>
 */
public record ChatTurn(String assistantText,
                       List<ToolCall> toolCalls,
                       StopReason stopReason,
                       long inputTokens,
                       long outputTokens,
                       boolean inputTokensReported,
                       boolean outputTokensReported) {

    /** Legacy adapters cannot distinguish missing usage from zero; retain their positive-count semantics. */
    public ChatTurn(String assistantText, List<ToolCall> toolCalls, StopReason stopReason,
                    long inputTokens, long outputTokens) {
        this(assistantText, toolCalls, stopReason, Math.max(0, inputTokens), Math.max(0, outputTokens),
                inputTokens > 0, outputTokens > 0);
    }

    /** Preserves independently present provider counters, including explicit zero. Invalid counters are unavailable. */
    public static ChatTurn withReportedUsage(String text, List<ToolCall> calls, StopReason reason,
                                             Long input, Long output) {
        boolean hasInput = input != null && input >= 0;
        boolean hasOutput = output != null && output >= 0;
        return new ChatTurn(text, calls, reason, hasInput ? input : 0, hasOutput ? output : 0,
                hasInput, hasOutput);
    }

    /** Whether both counters are available; presence alone does not establish billing completeness. */
    public boolean hasReportedUsage() {
        return inputTokensReported && outputTokensReported;
    }

    public ChatTurn {
        if (inputTokens < 0) {
            inputTokens = 0;
            inputTokensReported = false;
        }
        if (outputTokens < 0) {
            outputTokens = 0;
            outputTokensReported = false;
        }
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        if (stopReason == null) {
            stopReason = StopReason.OTHER;
        }
        if (assistantText == null) {
            assistantText = "";
        }
    }

    /** Convenience factory for a pure-text (no tool-use) turn. */
    public static ChatTurn text(String assistantText) {
        return new ChatTurn(assistantText, List.of(), StopReason.END_TURN, 0L, 0L);
    }

    /** True when the model wants the caller to dispatch one or more tools. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** Total tokens (input + output) for this turn. */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
