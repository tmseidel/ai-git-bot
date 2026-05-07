package org.remus.giteabot.ai.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AnthropicRequest {

    private String model;

    @JsonProperty("max_tokens")
    private int maxTokens;

    private String system;

    private List<Message> messages;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }

}
