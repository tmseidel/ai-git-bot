package org.remus.giteabot.ai.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ollama {@code /api/chat} request payload.
 *
 * <p>Step 6: when the agent runs in native-tool-calling mode {@link #tools}
 * is populated and the model may emit
 * {@link Message#toolCalls tool_calls}. Tool results come back as messages
 * with {@code role="tool"}. Mirrors the OpenAI Chat-Completions schema, in
 * line with Ollama's tool-calling docs.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaRequest {

    private String model;

    private List<Message> messages;

    private boolean stream;

    private Options options;

    /**
     * Output format. Set to {@code "json"} to force JSON output from the model.
     */
    private String format;

    /** Tool definitions advertised to the model (Step 6). */
    private List<Tool> tools;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private String content;

        @JsonProperty("tool_calls")
        private List<ToolCallPayload> toolCalls;

        /** Required on tool-role messages; Ollama uses the function name as id. */
        @JsonProperty("tool_call_id")
        private String toolCallId;
    }

    @Data
    @Builder
    public static class Options {
        private Integer numPredict;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        @Builder.Default
        private String type = "function";
        private Function function;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {
        private String name;
        private String description;
        private Object parameters;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallPayload {
        private FunctionCall function;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        /** Ollama returns {@code arguments} as a JSON object, not a stringified payload. */
        private Map<String, Object> arguments;
    }
}
