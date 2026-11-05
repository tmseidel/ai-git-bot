package org.remus.giteabot.ai.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolNameSanitizer;
import org.remus.giteabot.ai.UsageCounterDeserializer;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.List;

/** OpenRouter response and usage fields are independent of the OpenAI response model. */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenRouterResponse(String id, String model, List<Choice> choices,
                          @JsonDeserialize(using = UsageDeserializer.class) Usage usage, Error error) {

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
                    args = AgentJackson.mapper().readTree(raw);
                    if (!args.isObject()) {
                        throw new IllegalArgumentException("Tool arguments must be an object");
                    }
                } catch (Exception e) {
                    // Never execute a tool with fabricated empty arguments after a malformed response.
                    throw new RestClientException("OpenRouter returned invalid tool arguments");
                }
                calls.add(new ToolCall(call.id(), ToolNameSanitizer.desanitize(call.function().name()), args));
            }
        }
        if (!calls.isEmpty() && reason == StopReason.END_TURN) reason = StopReason.TOOL_USE;
        return ChatTurn.withReportedUsage(message == null ? "" : message.content(), calls, reason,
                usage == null ? null : usage.promptTokens(), usage == null ? null : usage.completionTokens(),
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
    record Usage(@JsonProperty("prompt_tokens")
                 @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class) Long promptTokens,
                 @JsonProperty("completion_tokens")
                 @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class) Long completionTokens,
                 @JsonProperty("prompt_tokens_details")
                 @JsonDeserialize(using = PromptTokensDetailsDeserializer.class) PromptTokensDetails promptTokensDetails) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptTokensDetails(@JsonProperty("cached_tokens")
                               @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class) Long cachedTokens,
                               @JsonProperty("cache_write_tokens")
                               @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class) Long cacheWriteTokens) {}

    static final class UsageDeserializer extends ValueDeserializer<Usage> {
        @Override public Usage deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.START_OBJECT)) return context.readValue(parser, Usage.class);
            parser.skipChildren();
            return null;
        }
    }

    static final class PromptTokensDetailsDeserializer extends ValueDeserializer<PromptTokensDetails> {
        @Override public PromptTokensDetails deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.START_OBJECT)) return context.readValue(parser, PromptTokensDetails.class);
            parser.skipChildren();
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(JsonNode code, String message) {
        int httpStatus() {
            return code != null && code.isIntegralNumber() ? code.asInt() : 0;
        }
        @Override public String toString() { return "Error[redacted]"; }
    }
}
