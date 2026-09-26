package org.remus.giteabot.ai;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import tools.jackson.databind.JsonNode;
import java.util.List;
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
}
