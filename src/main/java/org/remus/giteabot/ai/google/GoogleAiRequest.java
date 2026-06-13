package org.remus.giteabot.ai.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Gemini {@code generateContent} request payload.
 *
 * <p>Step 6: when the agent runs in native-tool-calling mode the
 * {@link #tools} list carries one or more {@code functionDeclarations}; the
 * model may then emit {@link Part#functionCall} blocks that the loop
 * dispatches via {@link org.remus.giteabot.agent.tools.AgentToolRouter}. For
 * text-only legacy calls {@code tools} stays {@code null} and only
 * {@link Part#text} is populated.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GoogleAiRequest {

    @JsonProperty("systemInstruction")
    private Content systemInstruction;

    private List<Content> contents;

    @JsonProperty("generationConfig")
    private GenerationConfig generationConfig;

    /** Tool declarations (Step 6). */
    private List<Tool> tools;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Content {
        private String role;
        private List<Part> parts;
    }

    /**
     * Polymorphic part. Exactly one of {@link #text}, {@link #functionCall} or
     * {@link #functionResponse} should be populated.
     *
     * <p>{@link #thoughtSignature} is only set when replaying a Gemini 3.x
     * {@code functionCall} from history — Gemini requires the original
     * signature to be returned verbatim or it rejects the request with
     * {@code "Function call is missing a thought_signature"}.</p>
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Part {
        private String text;
        @JsonProperty("functionCall")
        private FunctionCall functionCall;
        @JsonProperty("functionResponse")
        private FunctionResponse functionResponse;
        @JsonProperty("thoughtSignature")
        private String thoughtSignature;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        /** Object whose fields are the function arguments. */
        private Map<String, Object> args;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionResponse {
        private String name;
        /** Free-form result; Gemini accepts arbitrary JSON. */
        private Map<String, Object> response;
    }

    @Data
    @Builder
    public static class GenerationConfig {
        @JsonProperty("maxOutputTokens")
        private int maxOutputTokens;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        @JsonProperty("functionDeclarations")
        private List<FunctionDeclaration> functionDeclarations;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionDeclaration {
        private String name;
        private String description;
        /** Draft-2020-12 JSON-Schema subset accepted by Gemini. */
        private Object parameters;
    }
}
