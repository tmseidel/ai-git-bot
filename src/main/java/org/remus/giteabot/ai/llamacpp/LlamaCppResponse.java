package org.remus.giteabot.ai.llamacpp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Response model for llama.cpp server's native /completion endpoint.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlamaCppResponse {

    /**
     * The generated completion text.
     */
    private String content;

    /**
     * The model used for generation.
     */
    private String model;

    /**
     * Reason for stopping generation.
     */
    private String stop;

    /**
     * Whether generation was stopped due to hitting a stop sequence.
     */
    @JsonProperty("stopped_eos")
    private Boolean stoppedEos;

    /**
     * Whether generation was stopped due to hitting max tokens.
     */
    @JsonProperty("stopped_limit")
    private Boolean stoppedLimit;

    /**
     * Whether generation was stopped due to hitting a stop word.
     */
    @JsonProperty("stopped_word")
    private Boolean stoppedWord;

    /**
     * Number of tokens in the prompt.
     */
    @JsonProperty("tokens_evaluated")
    private Integer tokensEvaluated;

    /**
     * Number of tokens generated.
     */
    @JsonProperty("tokens_predicted")
    private Integer tokensPredicted;

    /**
     * Whether the response was truncated.
     */
    private Boolean truncated;

    /**
     * Generation timings.
     */
    private Timings timings;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Timings {
        @JsonProperty("prompt_n")
        private Integer promptN;

        @JsonProperty("prompt_ms")
        private Double promptMs;

        @JsonProperty("predicted_n")
        private Integer predictedN;

        @JsonProperty("predicted_ms")
        private Double predictedMs;
    }
}
