package org.remus.giteabot.ai.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.McpConfigurationData;
import org.remus.giteabot.ai.McpConfigurationMapper;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        return sendReviewRequest(systemPrompt, effectiveModel, maxTokens, userMessage, null);
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage,
                                       McpConfigurationData mcpConfiguration) {
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
                .mcpServers(McpConfigurationMapper.toMcpServers(mcpConfiguration, "Anthropic"))
                .build();

        AnthropicResponse response = executeRequest(request, mcpConfiguration, "review");

        return extractText(response, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages) {
        return sendChatRequest(systemPrompt, effectiveModel, maxTokens, messages, null);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages,
                                     McpConfigurationData mcpConfiguration) {
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
                .mcpServers(McpConfigurationMapper.toMcpServers(mcpConfiguration, "Anthropic"))
                .build();

        AnthropicResponse response = executeRequest(request, mcpConfiguration, "chat");

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

    private AnthropicResponse executeRequest(AnthropicRequest request, McpConfigurationData mcpConfiguration,
                                             String context) {
        try {
            return restClient.post()
                    .uri("/v1/messages")
                    .body(checkMcpTools(request))
                    .retrieve()
                    .body(AnthropicResponse.class);
        } catch (RestClientException e) {
            if (mcpConfiguration == null) {
                throw e;
            }
            log.error("MCP configuration '{}' could not be applied to Anthropic {} request; retrying without MCP: {}",
                    mcpConfiguration.name(), context, e.getMessage(), e);
            request.setMcpServers(null);
            request.setTools(null);
            return restClient.post()
                    .uri("/v1/messages")
                    .body(request)
                    .retrieve()
                    .body(AnthropicResponse.class);
        }
    }

    private AnthropicRequest checkMcpTools(AnthropicRequest request) {
        if (request.getMcpServers() != null) {
            request.setTools(McpConfigurationMapper.extractNamesFromMcpJson(
                    request.getMcpServers()).stream().map(
                            name -> AnthropicRequest.Tool.builder()
                                    .mcpServerName(name)
                                    .type("mcp_toolset").build()).toList());
        }
        return request;
    }

}
