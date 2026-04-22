package org.remus.giteabot.ai;

import java.util.List;

/**
 * Provider-agnostic interface for AI-powered code review.
 * Implementations exist for Anthropic, OpenAI, Ollama, and llama.cpp.
 */
public interface AiClient {

    String reviewDiff(String prTitle, String prBody, String diff);

    String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt, String modelOverride);

    /**
     * Reviews a diff with extended thinking / reasoning enabled.
     * Providers that do not support this feature will ignore the flag.
     *
     * @param thinkingEnabled Whether to enable provider-specific extended thinking
     */
    String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt,
                      String modelOverride, boolean thinkingEnabled);

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

    /**
     * Sends a multi-turn conversation with extended thinking / reasoning enabled.
     * Providers that do not support this feature will ignore the flag.
     *
     * @param thinkingEnabled Whether to enable provider-specific extended thinking
     */
    String chat(List<AiMessage> conversationHistory, String newUserMessage,
                String systemPrompt, String modelOverride, Integer maxTokensOverride,
                boolean thinkingEnabled);
}
