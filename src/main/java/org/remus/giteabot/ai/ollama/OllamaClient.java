package org.remus.giteabot.ai.ollama;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for Ollama (local LLM inference).
 * Uses the /api/chat endpoint with system prompt as a role message and streaming disabled.
 * <p>
 * Automatically enables JSON mode when the system prompt requests JSON output,
 * which significantly improves reliability for structured responses (e.g., agent feature).
 */
@Slf4j
public class OllamaClient extends AbstractAiClient {

    private final RestClient restClient;

    public OllamaClient(RestClient restClient, String model, int maxTokens,
                        int maxDiffCharsPerChunk, int maxDiffChunks,
                        int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        List<OllamaRequest.Message> messages = new ArrayList<>();
        messages.add(OllamaRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(OllamaRequest.Message.builder().role("user").content(userMessage).build());

        boolean useJsonMode = shouldUseJsonMode(systemPrompt);
        return doRequest(effectiveModel, messages, maxTokens, "review", useJsonMode);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<OllamaRequest.Message> messages = new ArrayList<>();
        messages.add(OllamaRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            messages.add(OllamaRequest.Message.builder()
                    .role(m.getRole())
                    .content(m.getContent())
                    .build());
        }

        boolean useJsonMode = shouldUseJsonMode(systemPrompt);
        return doRequest(effectiveModel, messages, maxTokens, "chat", useJsonMode);
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("too long") || normalized.contains("context length");
    }

    /**
     * Detects whether the system prompt is requesting JSON output.
     * If so, we enable Ollama's JSON mode for more reliable structured responses.
     */
    private boolean shouldUseJsonMode(String systemPrompt) {
        if (systemPrompt == null) {
            return false;
        }
        String lower = systemPrompt.toLowerCase(Locale.ROOT);
        // Detect if the prompt asks for JSON output
        return lower.contains("respond with a json")
                || lower.contains("output json")
                || lower.contains("output format") && lower.contains("json")
                || lower.contains("```json");
    }


    private String doRequest(String model, List<OllamaRequest.Message> messages,
                             int maxTokens, String context, boolean useJsonMode) {
        OllamaRequest.OllamaRequestBuilder requestBuilder = OllamaRequest.builder()
                .model(model)
                .messages(messages)
                .stream(false)
                .options(OllamaRequest.Options.builder()
                        .numPredict(maxTokens)
                        .build());

        if (useJsonMode) {
            requestBuilder.format("json");
            log.info("Ollama {} request: JSON mode enabled for structured output", context);
        }

        OllamaRequest request = requestBuilder.build();

        OllamaResponse response = executeRequest(request);

        return extractText(response, context);
    }

    private OllamaResponse executeRequest(OllamaRequest request) {
        return restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaResponse.class);
    }

    private String extractText(OllamaResponse response, String context) {
        if (response == null || response.getMessage() == null
                || response.getMessage().getContent() == null) {
            log.warn("Empty response from Ollama API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getMessage().getContent();

        if (response.getPromptEvalCount() != null && response.getEvalCount() != null) {
            log.info("Ollama {} response: {} prompt tokens, {} eval tokens",
                    context,
                    response.getPromptEvalCount(),
                    response.getEvalCount());
        }

        return result;
    }
}
