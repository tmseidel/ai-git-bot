package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractAiClientTest {

    @Test
    void submitReviewPrompt_delegatesToSendReviewRequest() {
        TestAiClient client = new TestAiClient("test-model", 1024) {
            @Override
            protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                               int maxTokens, String userMessage) {
                assertEquals("Custom prompt", systemPrompt);
                assertEquals("custom-model", effectiveModel);
                assertEquals("Hello, review this", userMessage);
                return "Review response";
            }
        };

        String result = client.submitReviewPrompt("Custom prompt", "custom-model", "Hello, review this");
        assertEquals("Review response", result);
    }

    @Test
    void submitReviewPrompt_withNullSystemPrompt_usesDefault() {
        TestAiClient client = new TestAiClient("test-model", 1024) {
            @Override
            protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                               int maxTokens, String userMessage) {
                assertEquals(AbstractAiClient.DEFAULT_SYSTEM_PROMPT, systemPrompt);
                assertEquals("test-model", effectiveModel);
                return "Review with default prompt";
            }
        };

        String result = client.submitReviewPrompt(null, null, "some message");
        assertEquals("Review with default prompt", result);
    }

    @Test
    void submitReviewPrompt_withNullModelOverride_usesConfiguredModel() {
        TestAiClient client = new TestAiClient("test-model", 1024) {
            @Override
            protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                               int maxTokens, String userMessage) {
                assertEquals("test-model", effectiveModel);
                return "Review";
            }
        };

        String result = client.submitReviewPrompt("System", null, "msg");
        assertEquals("Review", result);
    }

    @Test
    void reportUsage_doesNotSerializeRawPayloadsWhenDisabled() {
        RecordingRecorder recorder = new RecordingRecorder();
        TestAiClient client = new TestAiClient("test-model", 1024);
        client.setAuditRecorder(recorder);

        client.reportUsageForTest(100L, 50L, 0L, 0L, "request", "response");

        assertNull(recorder.rawRequest);
        assertNull(recorder.rawResponse);
    }

    @Test
    void reportUsage_serializesRawPayloadsWhenEnabled() {
        RecordingRecorder recorder = new RecordingRecorder();
        TestAiClient client = new TestAiClient("test-model", 1024);
        AiUsageProperties properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(true);
        client.setUsageProperties(properties);
        client.setAuditRecorder(recorder);

        client.reportUsageForTest(100L, 50L, 0L, 0L, "request", "response");

        assertNotNull(recorder.rawRequest);
        assertTrue(recorder.rawRequest.contains("request"));
        assertNotNull(recorder.rawResponse);
        assertTrue(recorder.rawResponse.contains("response"));
    }

    /**
     * Concrete test implementation of AbstractAiClient.
     */
    static class TestAiClient extends AbstractAiClient {

        TestAiClient(String model, int maxTokens) {
            super(model, maxTokens);
        }

        void reportUsageForTest(Number inputTokens, Number outputTokens,
                                Number cacheCreationInputTokens, Number cacheReadInputTokens,
                                Object rawRequest, Object rawResponse) {
            reportUsage(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
                    rawRequest, rawResponse);
        }

        @Override
        protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                           int maxTokens, String userMessage) {
            return "mock review response";
        }

        @Override
        protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                         int maxTokens, List<AiMessage> messages) {
            return "mock chat response";
        }

        @Override
        public boolean isPromptTooLongError(HttpClientErrorException e) {
            return false;
        }
    }

    private static class RecordingRecorder implements AiAuditRecorder {
        long inputTokens;
        long outputTokens;
        String rawRequest;
        String rawResponse;

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.rawRequest = rawRequest;
            this.rawResponse = rawResponse;
        }

    }
}
