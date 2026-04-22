package org.remus.giteabot.ai.openai;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for OpenAI-compatible APIs.
 * Uses the Chat Completions endpoint with system prompt as a role message.
 */
@Slf4j
public class OpenAiClient extends AbstractAiClient {

    /**
     * Reasoning effort level applied when deep thinking is enabled.
     * "high" instructs the model to spend more tokens on internal reasoning.
     */
    static final String DEEP_THINKING_REASONING_EFFORT = "high";

    private final RestClient restClient;

    public OpenAiClient(RestClient restClient, String model, int maxTokens,
                        int maxDiffCharsPerChunk, int maxDiffChunks,
                        int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        return sendReviewRequest(systemPrompt, effectiveModel, maxTokens, userMessage, false);
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage,
                                       boolean thinkingEnabled) {
        List<OpenAiRequest.Message> messages = new ArrayList<>();
        messages.add(OpenAiRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(OpenAiRequest.Message.builder().role("user").content(userMessage).build());

        return doRequest(effectiveModel, maxTokens, messages, "review", thinkingEnabled);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        return sendChatRequest(systemPrompt, effectiveModel, maxTokens, conversationMessages, false);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages,
                                     boolean thinkingEnabled) {
        List<OpenAiRequest.Message> messages = new ArrayList<>();
        messages.add(OpenAiRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            messages.add(OpenAiRequest.Message.builder()
                    .role(m.getRole())
                    .content(m.getContent())
                    .build());
        }

        return doRequest(effectiveModel, maxTokens, messages, "chat", thinkingEnabled);
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("maximum context length")
                || normalized.contains("too many tokens")
                || normalized.contains("max_completion_tokens");
    }

    private String doRequest(String model, int maxTokens,
                             List<OpenAiRequest.Message> messages, String context) {
        return doRequest(model, maxTokens, messages, context, false);
    }

    private String doRequest(String model, int maxTokens,
                             List<OpenAiRequest.Message> messages, String context,
                             boolean thinkingEnabled) {
        OpenAiRequest.OpenAiRequestBuilder builder = OpenAiRequest.builder()
                .model(model)
                .maxTokens(maxTokens)
                .messages(messages);

        if (thinkingEnabled) {
            builder.reasoningEffort(DEEP_THINKING_REASONING_EFFORT);
            log.info("OpenAI reasoning tokens enabled: effort={}", DEEP_THINKING_REASONING_EFFORT);
        }

        OpenAiResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .body(builder.build())
                .retrieve()
                .body(OpenAiResponse.class);

        return extractText(response, context);
    }

    private String extractText(OpenAiResponse response, String context) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("Empty response from OpenAI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        OpenAiResponse.Choice firstChoice = response.getChoices().getFirst();
        if (firstChoice == null
                || firstChoice.getMessage() == null
                || firstChoice.getMessage().getContent() == null) {
            log.warn("Empty response from OpenAI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = firstChoice.getMessage().getContent();
        if (response.getUsage() != null) {
            log.info("OpenAI {} response: {} prompt tokens, {} completion tokens",
                    context,
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }

        return result;
    }
}
