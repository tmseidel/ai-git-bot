package org.remus.giteabot.ai.anthropic;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {
    private String id;
    private String type;
    private String role;
    private List<ContentBlock> content;
    private String model;
    @JsonProperty("stop_reason")
    private String stopReason;
    private Usage usage;
    /**
     * Polymorphic content block. {@code type} is one of {@code text} or
     * {@code tool_use} (Step 6). For tool_use blocks, {@link #id},
     * {@link #name} and {@link #input} are populated.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        private String type;
        private String text;
        // tool_use
        private String id;
        private String name;
        /** Map representation; the client converts it to a JsonNode. */
        private Map<String, Object> input;
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("input_tokens")
        private Integer inputTokens;
        @JsonProperty("output_tokens")
        private Integer outputTokens;
        @JsonProperty("cache_creation_input_tokens")
        private int cacheCreationInputTokens;
        @JsonProperty("cache_read_input_tokens")
        private int cacheReadInputTokens;

        /** Anthropic excludes cache reads/writes from input_tokens; add each exactly once. */
        public Long totalInputTokens() {
            if (inputTokens == null || inputTokens < 0 || cacheCreationInputTokens < 0 || cacheReadInputTokens < 0) {
                return null;
            }
            return (long) inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        }
    }
}
