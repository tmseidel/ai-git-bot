package org.remus.giteabot.ai;

import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Locale;

/**
 * Provider-agnostic interface for AI-powered code review and agent chat.
 *
 * <p>As of Step 6 the contract additionally exposes native function/tool
 * calling via {@link #chatWithTools(List, List, String, String, Integer)}.
 * Implementations that do not yet support native tools should leave
 * {@link #supportsNativeTools()} returning {@code false}; the default
 * delegation falls back to the textual {@link #chat(List, String, String, String, Integer)}
 * path, which preserves the historical JSON-in-prompt behaviour.</p>
 */
public interface AiClient {

    String reviewDiff(String prTitle, String prBody, String diff);

    String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt, String modelOverride);

    /**
     * Reviews a pull request diff with additional context about the repository.
     *
     * @param additionalContext extra context (repo tree, file contents, commit messages, etc.)
     */
    default String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt,
                              String modelOverride, String additionalContext) {
        return reviewDiff(prTitle, prBody, diff, systemPrompt, modelOverride);
    }

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
     * implement {@link #chatWithTools(List, List, String, String, Integer)}
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
     * Sends a chat turn with native tool descriptors. The default implementation
     * falls back to {@link #chat(List, String, String, String, Integer)} so
     * agents that only expect textual responses keep working without
     * provider-side tool support.
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
        return ChatTurn.text(text);
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
}
