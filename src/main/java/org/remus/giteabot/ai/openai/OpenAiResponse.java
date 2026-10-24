package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.ai.UsageCounterDeserializer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiResponse {

    private String id;
    private String object;
    private String model;

    private List<Choice> choices;
    @JsonDeserialize(using = UsageDeserializer.class)
    private Usage usage;

    private Error error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private int index;
        private Message message;

        @JsonProperty("finish_reason")
        private String finishReason;

        private Error error;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;

        /** Tool calls emitted by the assistant (Step 6, native function calling). */
        @JsonProperty("tool_calls")
        private List<ToolCallResponse> toolCalls;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallResponse {
        private String id;
        private String type;
        private FunctionResponse function;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionResponse {
        private String name;
        /** Stringified JSON arguments, per OpenAI's spec. */
        private String arguments;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer totalTokens;

        @JsonProperty("prompt_tokens_details")
        @JsonDeserialize(using = PromptTokensDetailsDeserializer.class)
        private PromptTokensDetails promptTokensDetails;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class)
        private Long cachedTokens;

        @JsonProperty("cache_write_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class)
        private Long cacheWriteTokens;
    }

    /** Ignores malformed optional usage containers, not malformed responses. */
    public static final class UsageDeserializer extends ValueDeserializer<Usage> {
        @Override public Usage deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.START_OBJECT)) return context.readValue(parser, Usage.class);
            parser.skipChildren();
            return null;
        }
    }

    /** Keeps bad cache metadata from discarding valid prompt/completion counters. */
    public static final class PromptTokensDetailsDeserializer extends ValueDeserializer<PromptTokensDetails> {
        @Override public PromptTokensDetails deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.START_OBJECT)) return context.readValue(parser, PromptTokensDetails.class);
            parser.skipChildren();
            return null;
        }
    }

    /**
     * OpenRouter-specific error object describing a provider-side failure.
     * Kept intentionally loose (metadata is a map) so arbitrary provider
     * details are preserved in audit logs without requiring a typed model.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        private int code;
        private String message;
        private Map<String, Object> metadata;
    }
}
