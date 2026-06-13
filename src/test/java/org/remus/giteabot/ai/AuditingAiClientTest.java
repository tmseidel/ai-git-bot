package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditingAiClientTest {

    private static class RecordingRecorder implements AiAuditRecorder {
        final AtomicReference<Throwable> lastError = new AtomicReference<>();

        @Override
        public void recordUsage(long inputTokens, long outputTokens) {
        }

        @Override
        public void recordError(Throwable error) {
            lastError.set(error);
        }
    }

    private static class FailingClient implements AiClient {
        @Override
        public String reviewDiff(String prTitle, String prBody, String diff) {
            throw new IllegalStateException("review failed");
        }

        @Override
        public String reviewDiff(String prTitle, String prBody, String diff,
                                 String systemPrompt, String modelOverride) {
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
    void reviewDiff_isNotDoubleAudited() {
        // Chunk-level review errors are reported by AbstractAiClient itself,
        // so the decorator must delegate reviewDiff without recording again.
        RecordingRecorder recorder = new RecordingRecorder();
        AuditingAiClient client = new AuditingAiClient(new FailingClient(), recorder);

        assertThrows(IllegalStateException.class,
                () -> client.reviewDiff("t", "b", "diff"));

        assertNull(recorder.lastError.get());
    }
}
