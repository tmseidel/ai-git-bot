package org.remus.giteabot.ai;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.Collectors;
/**
 * Provider-agnostic chat message. The optional tool-related fields are only
 * populated when an agent runs in native-tool-calling mode (Step 6):
 *
 * <ul>
 *     <li>{@code toolCalls} on an {@code assistant}-role message preserves
 *     the tool invocations the model emitted in that turn so they can be
 *     replayed in the next request.</li>
 *     <li>{@code toolCallId} + {@code toolResult} on a {@code tool}-role
 *     message report the result of one such invocation back to the model.</li>
 * </ul>
 *
 * Legacy callers that only set {@code role} and {@code content} continue to
 * work unchanged.
 */
@Data
@Builder
public class AiMessage {
    private String role;
    private String content;
    /** Populated when an assistant turn emitted native tool calls. */
    private List<ToolCall> toolCalls;
    /** Populated on a {@code tool}-role message: the call this result belongs to. */
    private String toolCallId;
    /** Populated on a {@code tool}-role message: the textual result. */
    private String toolResult;
    /** Opaque provider reasoning blocks, retained in-memory for exact tool-roundtrip replay. */
    @JsonIgnore
    @ToString.Exclude
    private List<JsonNode> reasoningDetails;

    /**
     * Plain-text stand-in for a turn whose only content was its tool calls, e.g.
     * {@code "[called cat, rg]"}; an empty string when the turn announced none.
     *
     * <p>Providers whose text-only shape cannot carry a tool exchange reject an empty
     * message, and a native session replayed in legacy mode — the integration was
     * switched, or the provider advertises no native tools — contains exactly those
     * turns, so a converter has to name their calls instead of sending nothing.</p>
     */
    public String toolCallSummary() {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        return "[called " + toolCalls.stream().map(ToolCall::name)
                .collect(Collectors.joining(", ")) + "]";
    }
}
