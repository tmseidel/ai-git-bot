package org.remus.giteabot.ai.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolNameSanitizer;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** OpenRouter response and usage fields are independent of the OpenAI response model. */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenRouterResponse(String id, String model, List<Choice> choices, Usage usage, Error error) {

    ChatTurn toTurn() {
        Choice choice = choices == null || choices.isEmpty() ? null : choices.getFirst();
        Message message = choice == null ? null : choice.message();
        StopReason reason = switch (choice == null || choice.finishReason() == null ? "" : choice.finishReason()) {
            case "stop" -> StopReason.END_TURN;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
        List<ToolCall> calls = new ArrayList<>();
        if (message != null && message.toolCalls() != null) {
            for (Call call : message.toolCalls()) {
                if (call == null || call.function() == null || call.function().name() == null) continue;
                JsonNode args;
                try {
                    String raw = call.function().arguments();
                    args = raw == null || raw.isBlank() ? AgentJackson.mapper().createObjectNode() : AgentJackson.mapper().readTree(raw);
                } catch (Exception e) {
                    args = AgentJackson.mapper().createObjectNode();
                }
                calls.add(new ToolCall(call.id(), ToolNameSanitizer.desanitize(call.function().name()), args));
            }
        }
        if (!calls.isEmpty() && reason == StopReason.END_TURN) reason = StopReason.TOOL_USE;
        return new ChatTurn(message == null ? "" : message.content(), calls, reason,
                usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens(),
                message == null ? null : message.reasoningDetails());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonProperty("finish_reason") String finishReason, Message message, Error error) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content, @JsonProperty("tool_calls") List<Call> toolCalls,
                   @JsonProperty("reasoning_details") List<JsonNode> reasoningDetails) {
        @Override public String toString() { return "Message[toolCalls=" + (toolCalls == null ? 0 : toolCalls.size()) + "]"; }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Call(String id, Function function) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Function(String name, String arguments) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") long promptTokens, @JsonProperty("completion_tokens") long completionTokens) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(int code, String message) {
        @Override public String toString() { return "Error[code=" + code + "]"; }
    }
}
