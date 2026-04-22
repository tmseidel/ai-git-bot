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

    /**
     * Default budget tokens for extended thinking.
     * Reserved for the model's internal reasoning process.
     * Must be less than max_tokens.
     */
    static final int DEFAULT_THINKING_BUDGET_TOKENS = 10000;

    /**
     * Minimum max_tokens when extended thinking is enabled.
     * Extended thinking requires max_tokens > budget_tokens.
     */
    static final int THINKING_MIN_MAX_TOKENS = DEFAULT_THINKING_BUDGET_TOKENS + 1000;

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
        return sendReviewRequest(systemPrompt, effectiveModel, maxTokens, userMessage, false);
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage,
                                       boolean thinkingEnabled) {
        AnthropicRequest.AnthropicRequestBuilder builder = AnthropicRequest.builder()
                .model(effectiveModel)
                .system(systemPrompt)
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ));

        if (thinkingEnabled) {
            int effectiveMaxTokens = Math.max(maxTokens, THINKING_MIN_MAX_TOKENS);
            builder.maxTokens(effectiveMaxTokens)
                    .thinking(AnthropicRequest.Thinking.builder()
                            .type("enabled")
                            .budgetTokens(DEFAULT_THINKING_BUDGET_TOKENS)
                            .build());
            log.info("Anthropic extended thinking enabled: budgetTokens={}, maxTokens={}",
                    DEFAULT_THINKING_BUDGET_TOKENS, effectiveMaxTokens);
        } else {
            builder.maxTokens(maxTokens);
        }

        AnthropicResponse response = restClient.post()
                .uri("/v1/messages")
                .body(builder.build())
                .retrieve()
                .body(AnthropicResponse.class);

        return extractText(response, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages) {
        return sendChatRequest(systemPrompt, effectiveModel, maxTokens, messages, false);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages,
                                     boolean thinkingEnabled) {
        List<AnthropicRequest.Message> anthropicMessages = messages.stream()
                .map(m -> AnthropicRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();

        AnthropicRequest.AnthropicRequestBuilder builder = AnthropicRequest.builder()
                .model(effectiveModel)
                .system(systemPrompt)
                .messages(anthropicMessages);

        if (thinkingEnabled) {
            int effectiveMaxTokens = Math.max(maxTokens, THINKING_MIN_MAX_TOKENS);
            builder.maxTokens(effectiveMaxTokens)
                    .thinking(AnthropicRequest.Thinking.builder()
                            .type("enabled")
                            .budgetTokens(DEFAULT_THINKING_BUDGET_TOKENS)
                            .build());
            log.info("Anthropic extended thinking enabled: budgetTokens={}, maxTokens={}",
                    DEFAULT_THINKING_BUDGET_TOKENS, effectiveMaxTokens);
        } else {
            builder.maxTokens(maxTokens);
        }

        AnthropicResponse response = restClient.post()
                .uri("/v1/messages")
                .body(builder.build())
                .retrieve()
                .body(AnthropicResponse.class);

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
}
