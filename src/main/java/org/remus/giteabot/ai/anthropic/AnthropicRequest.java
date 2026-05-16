package org.remus.giteabot.ai.anthropic;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
/**
 * Anthropic Messages-API request payload.
 *
 * <p>Step 6: when the agent runs in native-tool-calling mode, {@link #tools}
 * is populated and {@link Message#content} may be a list of content blocks
 * (text + {@code tool_use} / {@code tool_result}). For text-only legacy
 * calls {@code content} stays a {@code String}.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {
    private String model;
    @JsonProperty("max_tokens")
    private int maxTokens;
    private String system;
    private List<Message> messages;
    /** Tools advertised to the model (Step 6). */
    private List<Tool> tools;
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        /**
         * Either a plain {@code String} (legacy text turn) or a
         * {@code List<ContentBlock>}-shaped object (native tool calling).
         */
        private Object content;
    }
    /** Polymorphic content block: text / tool_use / tool_result. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentBlock {
        private String type;
        private String text;
        // tool_use
        private String id;
        private String name;
        private Object input;
        // tool_result
        @JsonProperty("tool_use_id")
        private String toolUseId;
        @JsonProperty("is_error")
        private Boolean isError;
        /**
         * Payload of a {@code tool_result} block. Per the Anthropic Messages
         * API this must be sent as {@code content} (string or list of nested
         * content blocks), NOT as {@code text} — {@code text} is reserved for
         * the {@code text} block type and is rejected with
         * "Extra inputs are not permitted" on {@code tool_result} blocks.
         */
        private Object content;
    }
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        private String name;
        private String description;
        @JsonProperty("input_schema")
        private Object inputSchema;
    }
}
