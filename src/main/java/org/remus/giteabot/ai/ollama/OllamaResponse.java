package org.remus.giteabot.ai.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaResponse {

    private String model;

    @JsonProperty("created_at")
    private String createdAt;

    private Message message;

    private boolean done;

    @JsonProperty("done_reason")
    private String doneReason;

    @JsonProperty("total_duration")
    private Long totalDuration;

    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    @JsonProperty("eval_count")
    private Integer evalCount;

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
        private FunctionResponse function;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionResponse {
        private String name;
        /** Ollama emits {@code arguments} as a JSON object (not a string). */
        private Map<String, Object> arguments;
    }
}
