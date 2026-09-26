package org.remus.giteabot.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Result of a single round-trip to the AI provider, with or without native
 * tools. {@code assistantText} may be empty for tool-only or incomplete turns.
 *
 * <p>{@code inputTokens} and {@code outputTokens} are populated when the
 * provider returns usage data; they default to 0 when unknown.</p>
 */
public record ChatTurn(String assistantText,
                       List<ToolCall> toolCalls,
                       StopReason stopReason,
                       long inputTokens,
                       long outputTokens,
                       @JsonIgnore List<JsonNode> reasoningDetails) {

    /** Compatibility constructor for providers without opaque reasoning metadata. */
    public ChatTurn(String assistantText, List<ToolCall> toolCalls, StopReason stopReason,
                    long inputTokens, long outputTokens) {
        this(assistantText, toolCalls, stopReason, inputTokens, outputTokens, null);
    }

    public ChatTurn {
        if (reasoningDetails != null) {
            reasoningDetails = List.copyOf(reasoningDetails);
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

    /** Preserves the complete assistant turn for the next in-memory provider request. */
    public AiMessage toAssistantMessage() {
        return AiMessage.builder().role("assistant").content(assistantText)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .reasoningDetails(reasoningDetails).build();
    }

    /** Diagnostics deliberately exclude opaque provider reasoning. */
    @Override
    public String toString() {
        return "ChatTurn[stopReason=%s, inputTokens=%d, outputTokens=%d, toolCalls=%d]"
                .formatted(stopReason, inputTokens, outputTokens, toolCalls.size());
    }
}
