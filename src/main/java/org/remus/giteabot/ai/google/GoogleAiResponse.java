package org.remus.giteabot.ai.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleAiResponse {

    private List<Candidate> candidates;

    @JsonProperty("usageMetadata")
    private UsageMetadata usageMetadata;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candidate {
        private Content content;

        @JsonProperty("finishReason")
        private String finishReason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private String role;
        private List<Part> parts;
    }

    /**
     * Polymorphic response part. May carry plain {@code text} or, when the
     * model wants to invoke a tool (Step 6), a {@link #functionCall} block.
     *
     * <p>Gemini 3.x additionally returns a {@code thoughtSignature} alongside
     * each function call. The API requires this signature to be echoed back
     * verbatim when the call is replayed in the conversation history
     * (otherwise the request fails with
     * {@code "Function call is missing a thought_signature"}). The field is
     * absent on older models.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Part {
        private String text;
        @JsonProperty("functionCall")
        private FunctionCall functionCall;
        @JsonProperty("thoughtSignature")
        private String thoughtSignature;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        private String name;
        private Map<String, Object> args;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UsageMetadata {
        @JsonProperty("promptTokenCount")
        private int promptTokenCount;

        @JsonProperty("candidatesTokenCount")
        private int candidatesTokenCount;

        @JsonProperty("totalTokenCount")
        private int totalTokenCount;
    }
}
