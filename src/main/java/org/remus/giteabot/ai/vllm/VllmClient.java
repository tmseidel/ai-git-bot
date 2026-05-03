package org.remus.giteabot.ai.vllm;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for vLLM's OpenAI-compatible Chat Completions API.
 */
@Slf4j
public class VllmClient extends AbstractAiClient {

    private final RestClient restClient;

    public VllmClient(RestClient restClient, String model, int maxTokens,
                      int maxDiffCharsPerChunk, int maxDiffChunks,
                      int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        List<VllmRequest.Message> messages = new ArrayList<>();
        messages.add(VllmRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(VllmRequest.Message.builder().role("user").content(userMessage).build());

        return doRequest(effectiveModel, maxTokens, messages, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<VllmRequest.Message> messages = new ArrayList<>();
        messages.add(VllmRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            messages.add(VllmRequest.Message.builder()
                    .role(m.getRole())
                    .content(m.getContent())
                    .build());
        }

        return doRequest(effectiveModel, maxTokens, messages, "chat");
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("maximum context length")
                || normalized.contains("context length")
                || normalized.contains("too many tokens")
                || normalized.contains("too long")
                || normalized.contains("token limit")
                || normalized.contains("exceeds")
                || normalized.contains("prompt too long");
    }

    private String doRequest(String model, int maxTokens,
                             List<VllmRequest.Message> messages, String context) {
        VllmRequest request = VllmRequest.builder()
                .model(model)
                .maxTokens(maxTokens)
                .messages(messages)
                .build();

        VllmResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(VllmResponse.class);

        return extractText(response, context);
    }

    private String extractText(VllmResponse response, String context) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("Empty response from vLLM API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        VllmResponse.Choice firstChoice = response.getChoices().getFirst();
        if (firstChoice == null
                || firstChoice.getMessage() == null
                || firstChoice.getMessage().getContent() == null) {
            log.warn("Empty response from vLLM API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = firstChoice.getMessage().getContent();
        if (response.getUsage() != null) {
            log.info("vLLM {} response: {} prompt tokens, {} completion tokens",
                    context,
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }

        return result;
    }
}
