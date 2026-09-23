package org.remus.giteabot.ai;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;

/**
 * Provider-agnostic interface for AI-powered code review and agent chat.
 *
 * <p>{@link #chatWithTools(List, String, List, String, String, Integer)} exposes
 * completion metadata for both native tools and text-only conversations.
 * Providers without native tools keep {@link #supportsNativeTools()} false but
 * can still override the typed API. Its default textual fallback cannot verify
 * completion and reports {@link StopReason#OTHER}.</p>
 */
public interface AiClient {

    /**
     * Sends a single review prompt to the AI provider and returns the response.
     * This is the primitive operation that the code-review service uses for each
     * diff chunk. The provider resolves its own model, token budget, and default
     * prompt internally.
     */
    String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage);

    /**
     * Sends a multi-turn conversation to the AI provider and returns the assistant's response.
     */
    String chat(List<AiMessage> conversationHistory, String newUserMessage,
                String systemPrompt, String modelOverride);

    /**
     * Sends a multi-turn conversation to the AI provider with a custom max tokens limit.
     *
     * @param maxTokensOverride Custom max tokens limit (if null, uses the default)
     */
    String chat(List<AiMessage> conversationHistory, String newUserMessage,
                String systemPrompt, String modelOverride, Integer maxTokensOverride);

    // ---------------------------------------------------------------------
    // Native function/tool calling (Step 6)
    // ---------------------------------------------------------------------

    /**
     * Capability flag: true when the implementation can advertise tools to
     * the underlying provider and parse {@code tool_use}/{@code tool_calls}
     * responses. Defaults to {@code false}; override in providers that
     * implement {@link #chatWithTools(List, String, List, String, String, Integer)}
     * natively.
     *
     * <p>Per-integration overrides (e.g. the {@code use_legacy_tool_calling}
     * column on {@code AiIntegration}) are applied by the
     * {@code AiClientFactory} when constructing the client.</p>
     */
    default boolean supportsNativeTools() {
        return false;
    }

    /**
     * Sends a typed chat turn with optional native tool descriptors. The default
     * implementation falls back to {@link #chat(List, String, String, String, Integer)}
     * with unknown completion status ({@link StopReason#OTHER}) and usage.
     * Providers should override it to retain their actual response metadata.
     *
     * @param conversationHistory the conversation up to (but not including) the
     *                            new user message
     * @param newUserMessage      the next user prompt (may be empty when the
     *                            previous turn already produced tool calls and
     *                            the caller is now feeding back tool results)
     * @param tools               the tools the model may invoke; an empty list
     *                            forces a text-only turn
     * @param systemPrompt        the system prompt
     * @param modelOverride       optional model override
     * @param maxTokensOverride   optional token budget
     */
    default ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                   String newUserMessage,
                                   List<ToolDescriptor> tools,
                                   String systemPrompt,
                                   String modelOverride,
                                   Integer maxTokensOverride) {
        String text = chat(conversationHistory, newUserMessage, systemPrompt,
                modelOverride, maxTokensOverride);
        return new ChatTurn(text, List.of(), StopReason.OTHER, 0L, 0L);
    }

    // ---------------------------------------------------------------------
    // Error classification
    // ---------------------------------------------------------------------

    /**
     * Heuristic check whether an HTTP client error indicates the prompt
     * exceeded the model's context window. The default implementation matches
     * common provider error patterns; concrete providers should override with
     * their own, more specific patterns.
     */
    default boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        String status = String.valueOf(e.getStatusCode().value());
        return normalized.contains("prompt is too long")
                || normalized.contains("maximum context length")
                || normalized.contains("request too large")
                || normalized.contains("input too long")
                || normalized.contains("too many tokens")
                || normalized.contains("context_length_exceeded")
                || normalized.contains("context length")
                || normalized.contains("token limit")
                || ("400".equals(status) && normalized.contains("too large"));
    }

    /**
     * Heuristic check whether a failure means the provider itself is
     * temporarily overloaded — HTTP 503/529, {@code "status": "UNAVAILABLE"},
     * {@code overloaded_error} or "high demand". Those spikes clear on their
     * own, so {@link RetryAiClient} repeats the call with backoff.
     *
     * <p>Body markers are only trusted on 5xx, and a bare
     * {@code "unavailable"} is not a marker at all: on every other status it
     * describes something a backoff cannot fix — "model unavailable for this
     * account", a disabled feature, an unknown model — so retrying would burn
     * the attempt budget and bury the real error. Rate limits (HTTP 429 /
     * {@code RESOURCE_EXHAUSTED}) are excluded for the same reason: a quota
     * refusal is not a transient capacity spike and must surface to the
     * operator.</p>
     */
    default boolean isProviderUnavailableError(Throwable error) {
        if (error == null) {
            return false;
        }
        RestClientResponseException httpError = findHttpError(error);
        if (httpError == null) {
            // Providers that surface overload without an HTTP status (gRPC-style
            // messages). The numeric status must appear in the message, so a bare
            // "unavailable" is an acceptable confirming marker here.
            String message = compact(error.getMessage());
            return (message.contains("503") || message.contains("529"))
                    && (containsOverloadMarker(message) || message.contains("unavailable"));
        }
        int status = httpError.getStatusCode().value();
        if (isOverloadStatus(status)) {
            return true;
        }
        if (status < 500) {
            // A 4xx describes the request or the account (quota, auth, unknown
            // model, bad payload) — never a capacity spike.
            return false;
        }
        return containsOverloadMarker(compact(httpError.getResponseBodyAsString()));
    }

    private static RestClientResponseException findHttpError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RestClientResponseException httpError) {
                return httpError;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    /** Statuses providers use for a temporary capacity spike; Anthropic uses 529. */
    private static boolean isOverloadStatus(int status) {
        return status == 503 || status == 529;
    }

    /**
     * Matches the phrases that mean "the provider is out of capacity" in
     * whitespace-stripped, lower-cased text — plus the gRPC-style
     * {@code "status":"UNAVAILABLE"} token Google sends with a transient
     * outage. Deliberately narrow: those markers decide whether a failed call is
     * repeated for up to two minutes, so anything a persistent error could also
     * carry ("unavailable", "not found", "capacity exceeded for your plan")
     * must not match.
     */
    private static boolean containsOverloadMarker(String compactedText) {
        return compactedText.contains("overloaded")
                || compactedText.contains("highdemand")
                || compactedText.contains("overcapacity")
                || compactedText.contains("temporarilyunavailable")
                || compactedText.contains("\"status\":\"unavailable\"");
    }

    /** Lower-cased text without whitespace, so pretty-printed JSON bodies still match. */
    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * Reports a failed provider interaction to the attached audit recorder
     * (no-op when no recorder is attached).
     */
    void reportError(Throwable error);

    /**
     * Returns the AiClients model-name
     * @return the model name
     */
    String getModel();

}
