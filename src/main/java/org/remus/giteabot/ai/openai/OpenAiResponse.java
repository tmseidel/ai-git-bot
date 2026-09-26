package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiResponse {

    private String id;
    private String object;
    private String model;

    private List<Choice> choices;
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
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        private int totalTokens;
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
