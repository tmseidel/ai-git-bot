package org.remus.giteabot.ai;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

/**
 * Decorator around an {@link AiClient} that reports any exception thrown by a
 * provider interaction (e.g. an HTTP 401 from the AI API) to an
 * {@link AiAuditRecorder} before rethrowing it.
 *
 * <p>Token usage is reported by the concrete clients themselves (they are the
 * only place where the provider-specific usage payload is parsed); this
 * decorator complements that with centralized error auditing.</p>
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
    public String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage) {
        return audited(() -> delegate.submitReviewPrompt(systemPrompt, modelOverride, userMessage));
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
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        return delegate.isPromptTooLongError(e);
    }

    @Override
    public void reportError(Throwable error) {
        delegate.reportError(error);
    }

    @Override
    public String getModel() {
        return delegate.getModel();
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
