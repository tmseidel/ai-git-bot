package org.remus.giteabot.ai.llamacpp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request model for llama.cpp server's native /completion endpoint.
 * Supports the grammar field for structured JSON output constraints.
 * See: <a href="https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md">...</a>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlamaCppRequest {

    /**
     * The prompt to generate completion for.
     * For chat, this should be formatted with the model's chat template.
     */
    private String prompt;

    /**
     * Maximum number of tokens to generate.
     */
    @JsonProperty("n_predict")
    private Integer nPredict;

    /**
     * Temperature for response generation (0.0 = deterministic, higher = more creative).
     */
    private Double temperature;

    /**
     * Top-p sampling (nucleus sampling).
     */
    @JsonProperty("top_p")
    private Double topP;

    /**
     * Top-k sampling.
     */
    @JsonProperty("top_k")
    private Integer topK;

    /**
     * Repetition penalty to prevent the model from repeating tokens.
     * Values > 1.0 discourage repetition. Recommended: 1.1 - 1.2
     */
    @JsonProperty("repeat_penalty")
    private Double repeatPenalty;

    /**
     * Frequency penalty - reduces likelihood of repeated tokens.
     */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /**
     * Presence penalty - reduces likelihood of any token that has appeared.
     */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /**
     * GBNF grammar string for constraining output format.
     * See: <a href="https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md">...</a>
     */
    private String grammar;

    /**
     * Stop sequences - generation stops when any of these strings is encountered.
     */
    private List<String> stop;

    /**
     * Whether to stream the response. We always set this to false.
     */
    private Boolean stream;

    /**
     * Enable caching of the prompt for faster subsequent requests.
     */
    @JsonProperty("cache_prompt")
    private Boolean cachePrompt;

}
