package org.remus.giteabot.ai;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Decorator around an {@link AiClient} that reports any exception thrown by a
 * provider interaction (e.g. an HTTP 401 from the AI API) to an
 * {@link AiAuditRecorder} before rethrowing it.
 *
 * <p>Token usage is reported by the concrete clients themselves (they are the
 * only place where the provider-specific usage payload is parsed); this
 * decorator complements that with centralized error auditing. Review errors
 * are already reported per diff chunk by {@link AbstractAiClient}, so the
 * {@code reviewDiff} methods delegate without additional auditing.</p>
 */
@RequiredArgsConstructor
public class AuditingAiClient implements AiClient {

    /**
     * -- GETTER --
     *  Returns the wrapped provider-specific client.
     */
    @Getter
    private final AiClient delegate;
    private final AiAuditRecorder recorder;

    @Override
    public String reviewDiff(String prTitle, String prBody, String diff) {
        return delegate.reviewDiff(prTitle, prBody, diff);
    }

    @Override
    public String reviewDiff(String prTitle, String prBody, String diff,
                             String systemPrompt, String modelOverride) {
        return delegate.reviewDiff(prTitle, prBody, diff, systemPrompt, modelOverride);
    }

    @Override
    public String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt,
                             String modelOverride, String additionalContext) {
        return delegate.reviewDiff(prTitle, prBody, diff, systemPrompt,
                modelOverride, additionalContext);
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride) {
        return audited(() -> delegate.chat(conversationHistory, newUserMessage,
                systemPrompt, modelOverride));
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride, Integer maxTokensOverride) {
        return audited(() -> delegate.chat(conversationHistory, newUserMessage,
                systemPrompt, modelOverride, maxTokensOverride));
    }

    @Override
    public boolean supportsNativeTools() {
        return delegate.supportsNativeTools();
    }

    @Override
    public ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                  String newUserMessage,
                                  List<ToolDescriptor> tools,
                                  String systemPrompt,
                                  String modelOverride,
                                  Integer maxTokensOverride) {
        return audited(() -> delegate.chatWithTools(conversationHistory, newUserMessage,
                tools, systemPrompt, modelOverride, maxTokensOverride));
    }

    private <T> T audited(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            recorder.recordError(e);
            throw e;
        }
    }
}
