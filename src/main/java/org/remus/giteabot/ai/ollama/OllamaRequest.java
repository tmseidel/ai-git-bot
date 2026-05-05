package org.remus.giteabot.ai.ollama;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OllamaRequest {

    private String model;

    private List<Message> messages;

    private boolean stream;

    private Options options;

    /**
     * Output format. Set to "json" to force JSON output from the model.
     * This significantly improves reliability when structured output is required.
     * See: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
     */
    private String format;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    public static class Options {
        /**
         * Maximum number of tokens to generate (num_predict in Ollama API).
         */
        private Integer numPredict;
    }
}
