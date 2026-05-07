package org.remus.giteabot.ai.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

@Slf4j
public class AnthropicAiClient extends AbstractAiClient {

    private final RestClient restClient;

    public AnthropicAiClient(RestClient restClient, String model, int maxTokens,
                             int maxDiffCharsPerChunk, int maxDiffChunks,
                             int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ))
                .build();

        AnthropicResponse response = executeRequest(request);

        return extractText(response, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages) {
        List<AnthropicRequest.Message> anthropicMessages = messages.stream()
                .map(m -> AnthropicRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();

        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(anthropicMessages)
                .build();

        AnthropicResponse response = executeRequest(request);

        return extractText(response, "chat");
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("prompt is too long") || normalized.contains("maximum");
    }

    private String extractText(AnthropicResponse response, String context) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Empty response from Anthropic API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(AnthropicResponse.ContentBlock::getText)
                .reduce("", (a, b) -> a + b);

        if (response.getUsage() != null) {
            log.info("Anthropic {} response: {} input tokens, {} output tokens",
                    context,
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
        }

        return result;
    }

    private AnthropicResponse executeRequest(AnthropicRequest request) {
        return restClient.post()
                .uri("/v1/messages")
                .body(request)
                .retrieve()
                .body(AnthropicResponse.class);
    }

}
