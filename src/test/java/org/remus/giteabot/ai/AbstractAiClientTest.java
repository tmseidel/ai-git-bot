package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
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

    /**
     * Concrete test implementation of AbstractAiClient.
     */
    static class TestAiClient extends AbstractAiClient {

        TestAiClient(String model, int maxTokens) {
            super(model, maxTokens);
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
}
