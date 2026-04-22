package org.remus.giteabot.ai.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    private String model;

    @JsonProperty("max_tokens")
    private int maxTokens;

    private String system;

    private List<Message> messages;

    /**
     * Extended thinking configuration for Anthropic Claude.
     * When set, the model allocates additional tokens for internal reasoning
     * before generating the response.
     * Requires claude-3-7-sonnet or later.
     */
    private Thinking thinking;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private String content;
    }

    /**
     * Extended thinking configuration block.
     * See: https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking
     */
    @Data
    @Builder
    public static class Thinking {
        /**
         * Must be "enabled" to activate extended thinking.
         */
        private String type;

        /**
         * Minimum number of tokens reserved for the internal thinking process.
         * Must be at least 1024. Recommended: 5000-10000 for complex tasks.
         */
        @JsonProperty("budget_tokens")
        private int budgetTokens;
    }
}
