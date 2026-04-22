package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiRequest {

    private String model;

    @JsonProperty("max_completion_tokens")
    private int maxTokens;

    private List<Message> messages;

    /**
     * Reasoning effort for OpenAI o-series models.
     * Accepted values: "low", "medium", "high".
     * When set, the model spends more tokens on internal reasoning before responding.
     * Ignored by models that do not support reasoning tokens (e.g. gpt-4o).
     * See: https://platform.openai.com/docs/guides/reasoning
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private String content;
    }
}
