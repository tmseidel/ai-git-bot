package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditingAiClientTest {

    private static class RecordingRecorder implements AiAuditRecorder {
        final AtomicReference<Throwable> lastError = new AtomicReference<>();

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
        }

        @Override
        public void recordError(Throwable error) {
            lastError.set(error);
        }
    }

    private static class FailingClient implements AiClient {
        @Override
        public String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage) {
            throw new IllegalStateException("review failed");
        }

        @Override
        public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                           String systemPrompt, String modelOverride) {
            return chat(conversationHistory, newUserMessage, systemPrompt, modelOverride, null);
        }

        @Override
        public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                           String systemPrompt, String modelOverride, Integer maxTokensOverride) {
            throw new IllegalStateException("401 Unauthorized");
        }

        @Override
        public void reportError(Throwable error) {

        }

        @Override
        public String getModel() {
            return "";
        }
    }

    @Test
    void chat_errorIsRecordedAndRethrown() {
        RecordingRecorder recorder = new RecordingRecorder();
        AuditingAiClient client = new AuditingAiClient(new FailingClient(), recorder);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.chat(List.of(), "hi", null, null));

        assertEquals("401 Unauthorized", e.getMessage());
        assertSame(e, recorder.lastError.get());
    }

    @Test
    void chatWithTools_fallbackErrorIsRecorded() {
        RecordingRecorder recorder = new RecordingRecorder();
        AuditingAiClient client = new AuditingAiClient(new FailingClient(), recorder);

        assertThrows(IllegalStateException.class,
                () -> client.chatWithTools(List.of(), "hi", List.of(), null, null, null));

        assertEquals("401 Unauthorized", recorder.lastError.get().getMessage());
    }

    @Test
    void textOnlyFallbackCannotClaimACompletedTurn() {
        AiClient textOnly = new FailingClient() {
            @Override
            public String chat(List<AiMessage> history, String message, String system, String model, Integer maxTokens) {
                return "Text without completion metadata";
            }
        };
        AuditingAiClient client = new AuditingAiClient(textOnly, new RecordingRecorder());

        ChatTurn turn = client.chatWithTools(List.of(), "hi", List.of(), "sys", null, 32);

        assertEquals("Text without completion metadata", turn.assistantText());
        assertEquals(StopReason.OTHER, turn.stopReason());
        assertEquals(0L, turn.totalTokens());
    }

    @Test
    void submitReviewPrompt_delegatesAndDoesNotDoubleAudit() {
        RecordingRecorder recorder = new RecordingRecorder();
        AuditingAiClient client = new AuditingAiClient(new FailingClient(), recorder);

        assertThrows(IllegalStateException.class,
                () -> client.submitReviewPrompt("sys", "model", "msg"));

        assertEquals("review failed", recorder.lastError.get().getMessage());
    }
}
