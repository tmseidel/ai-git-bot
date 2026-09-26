package org.remus.giteabot.ai.openrouter;

import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenRouter adapter using the shared chat, audit and agent-history infrastructure. */
public final class OpenRouterClient extends AbstractAiClient {
    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final OpenRouterRequest.ProviderPreferences providerPreferences;

    /** Creates an adapter for an authenticated, official-host OpenRouter transport. */
    public OpenRouterClient(RestClient restClient, String model, int maxTokens, boolean nativeToolsEnabled) {
        this(restClient, model, maxTokens, nativeToolsEnabled,
                new OpenRouterRequest.ProviderPreferences(true, false, "deny", false));
    }

    OpenRouterClient(RestClient restClient, String model, int maxTokens, boolean nativeToolsEnabled,
                     OpenRouterRequest.ProviderPreferences providerPreferences) {
        super(model, maxTokens);
        this.restClient = restClient;
        this.nativeToolsEnabled = nativeToolsEnabled;
        this.providerPreferences = providerPreferences;
    }

    @Override public boolean supportsNativeTools() { return nativeToolsEnabled; }

    @Override
    protected String sendReviewRequest(String systemPrompt, String model, int maxTokens, String userMessage) {
        return sendChatRequest(systemPrompt, model, maxTokens,
                List.of(AiMessage.builder().role("user").content(userMessage).build()));
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String model, int maxTokens, List<AiMessage> messages) {
        ChatTurn turn = execute(OpenRouterRequest.create(model, maxTokens, systemPrompt, messages, List.of(), providerPreferences));
        if (turn.stopReason() != StopReason.END_TURN || turn.hasToolCalls() || turn.assistantText().isBlank()) {
            throw new RestClientException("OpenRouter returned incomplete text (" + turn.stopReason() + ")");
        }
        return turn.assistantText();
    }

    @Override
    public ChatTurn chatWithTools(List<AiMessage> history, String userMessage, List<ToolDescriptor> tools,
                                  String systemPrompt, String modelOverride, Integer maxTokensOverride) {
        boolean nativeTools = nativeToolsEnabled && tools != null && !tools.isEmpty();
        List<AiMessage> messages = new ArrayList<>(history);
        if (!nativeTools || userMessage != null && !userMessage.isBlank()) {
            messages.add(AiMessage.builder().role("user").content(userMessage == null ? "" : userMessage).build());
        }
        return execute(OpenRouterRequest.create(
                modelOverride == null || modelOverride.isBlank() ? getModel() : modelOverride,
                maxTokensOverride == null || maxTokensOverride <= 0 ? getMaxTokens() : maxTokensOverride,
                resolvePrompt(systemPrompt), messages, nativeTools ? tools : List.of(), providerPreferences));
    }

    private ChatTurn execute(OpenRouterRequest request) {
        OpenRouterResponse response;
        try {
            response = restClient.post().uri("/v1/chat/completions").body(request)
                    .retrieve().body(OpenRouterResponse.class);
        } catch (RestClientException e) {
            throw sanitizeError(e);
        }
        if (response == null) return new ChatTurn("", List.of(), StopReason.OTHER, 0, 0);
        if (response.error() != null) throw completionError(response.error());
        if (response.choices() != null) {
            for (var choice : response.choices()) {
                if (choice != null && (choice.error() != null || "error".equals(choice.finishReason()))) {
                    throw completionError(choice.error());
                }
            }
        }
        ChatTurn turn = response.toTurn();
        if (response.usage() != null) reportUsage(turn.inputTokens(), turn.outputTokens(), 0L, 0L, request, response);
        return turn;
    }

    private RestClientException completionError(OpenRouterResponse.Error error) {
        if (error != null && error.code() >= 400 && error.code() < 600) {
            return sanitizeError(httpError(HttpStatusCode.valueOf(error.code()), error.message()));
        }
        return new RestClientException("OpenRouter returned a completion error");
    }

    private RestClientException sanitizeError(RestClientException error) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(error);
        if (error instanceof ResourceAccessException || cause instanceof SocketTimeoutException || cause instanceof SocketException) {
            return new ResourceAccessException("OpenRouter transport failure");
        }
        if (error instanceof RestClientResponseException http) {
            // Only fixed categories reach retries, logs and audit storage; original bodies/causes stay private.
            String category = error instanceof HttpClientErrorException clientError && isPromptTooLongError(clientError)
                    ? "maximum context length" : isProviderUnavailableError(error) ? "provider overloaded" : "request failed";
            return httpError(http.getStatusCode(), category);
        }
        return new RestClientException("OpenRouter request failed");
    }

    private RestClientResponseException httpError(HttpStatusCode status, String description) {
        byte[] body = description == null ? new byte[0] : description.getBytes(StandardCharsets.UTF_8);
        String message = "OpenRouter request failed (HTTP " + status.value() + ")";
        return status.is4xxClientError()
                ? HttpClientErrorException.create(message, status, "", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8)
                : HttpServerErrorException.create(message, status, "", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
    }
}
