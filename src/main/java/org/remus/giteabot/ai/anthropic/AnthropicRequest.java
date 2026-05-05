package org.remus.giteabot.ai.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
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

    @JsonProperty("mcp_servers")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonRawValue
    private String mcpServers;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Tool> tools;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    public static class Tool {
        private String type;
        @JsonProperty("mcp_server_name")
        private String mcpServerName;
    }
}
