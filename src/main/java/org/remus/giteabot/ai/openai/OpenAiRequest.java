package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OpenAiRequest {

    private String model;

    @JsonProperty("max_completion_tokens")
    private int maxTokens;

    private List<Message> messages;


    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
