package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
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

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mcp_servers")
    @JsonRawValue
    private String mcpServers;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
