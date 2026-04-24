package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractAiClientTest {

    private TestAiClient createClient(int maxDiffCharsPerChunk, int maxDiffChunks, int retryTruncatedChunkChars) {
        return new TestAiClient("test-model", 1024,
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
    }

    @Test
    void splitDiffIntoChunks_smallDiff_singleChunk() {
        TestAiClient client = createClient(20, 3, 10);

        AbstractAiClient.ChunkingResult result = client.splitDiffIntoChunks("line1\nline2");

        assertEquals(1, result.chunks().size());
        assertFalse(result.wasTruncated());
        assertEquals("line1\nline2", result.chunks().getFirst());
    }

    @Test
    void splitDiffIntoChunks_largeDiff_truncatedAfterMaxChunks() {
        TestAiClient client = createClient(10, 2, 6);

        AbstractAiClient.ChunkingResult result = client.splitDiffIntoChunks("0123456789ABCDEFGHIJxyz");

        assertEquals(2, result.chunks().size());
        assertTrue(result.wasTruncated());
        assertTrue(result.chunks().get(0).length() <= 10);
        assertTrue(result.chunks().get(1).length() <= 10);
    }

    @Test
    void splitDiffIntoChunks_emptyDiff_singleEmptyChunk() {
        TestAiClient client = createClient(10, 2, 6);

        AbstractAiClient.ChunkingResult result = client.splitDiffIntoChunks("");

        assertEquals(1, result.chunks().size());
        assertFalse(result.wasTruncated());
    }

    @Test
    void buildUserMessage_includesChunkAndRetryHint() {
        TestAiClient client = createClient(10, 2, 6);

        String message = client.buildUserMessage("title", "body", "+line", 2, 3, true);

        assertTrue(message.contains("**Diff chunk:** 2/3"));
        assertTrue(message.contains("truncated"));
    }

    @Test
    void truncateDiff_appendsMarker() {
        TestAiClient client = createClient(10, 2, 6);

        String truncated = client.truncateDiff("0123456789ABCDEFGHIJ", 8);

        assertTrue(truncated.startsWith("01234567"));
        assertTrue(truncated.contains("truncated due to model input limit"));
    }

    @Test
    void truncateDiff_shortDiff_unchanged() {
        TestAiClient client = createClient(10, 2, 6);

        String result = client.truncateDiff("short", 100);

        assertEquals("short", result);
    }

    @Test
    void reviewDiff_bestEffort_partialFailure_returnsPartialResult() {
        // Diff "abcde\nabcde_end" (15 chars) → with maxDiffCharsPerChunk=10 splits into
        // chunk1 = "abcde" (split at newline pos 5), chunk2 = "\nabcde_end" (10 chars)
        String diff = "abcde\nabcde_end";

        TestAiClient client = new TestAiClient("test-model", 1024, 10, 4, 6) {
            private int callCount = 0;

            @Override
            String reviewSingleChunkInternal(String prTitle, String prBody, String diffChunk,
                                             int chunkNumber, int totalChunks, boolean isRetry,
                                             String systemPrompt, String effectiveModel,
                                             String additionalContext) {
                callCount++;
                if (callCount == 2) {
                    throw new RuntimeException("API error on chunk 2");
                }
                return "Looks good for chunk " + chunkNumber;
            }
        };

        String result = client.reviewDiff("Test PR", "body", diff);

        assertTrue(result.contains("Looks good for chunk 1"), "Should contain successful chunk review");
        assertTrue(result.contains("failed"), "Should mention the failed chunk");
        assertTrue(result.contains("1 of 2"), "Should note 1 of 2 chunks failed");
    }

    @Test
    void reviewDiff_bestEffort_allChunksFail_throwsException() {
        TestAiClient client = new TestAiClient("test-model", 1024, 10, 4, 6) {
            @Override
            String reviewSingleChunkInternal(String prTitle, String prBody, String diffChunk,
                                             int chunkNumber, int totalChunks, boolean isRetry,
                                             String systemPrompt, String effectiveModel,
                                             String additionalContext) {
                throw new RuntimeException("API error on chunk " + chunkNumber);
            }
        };

        // Same diff producing exactly 2 chunks
        String diff = "abcde\nabcde_end";

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.reviewDiff("Test PR", "body", diff));
        assertTrue(ex.getMessage().contains("All"));
        assertTrue(ex.getMessage().contains("failed"));
    }

    @Test
    void reviewDiff_withCustomSystemPromptAndModel() {
        TestAiClient client = new TestAiClient("test-model", 1024, 5000, 4, 2000) {
            @Override
            String reviewSingleChunkInternal(String prTitle, String prBody, String diffChunk,
                                             int chunkNumber, int totalChunks, boolean isRetry,
                                             String systemPrompt, String effectiveModel,
                                             String additionalContext) {
                assertEquals("Custom prompt", systemPrompt);
                assertEquals("custom-model", effectiveModel);
                return "Review with custom prompt";
            }
        };

        String result = client.reviewDiff("Title", "Body", "some diff", "Custom prompt", "custom-model");
        assertTrue(result.contains("Review with custom prompt"));
    }

    @Test
    void reviewDiff_withNullSystemPrompt_usesDefault() {
        TestAiClient client = new TestAiClient("test-model", 1024, 5000, 4, 2000) {
            @Override
            String reviewSingleChunkInternal(String prTitle, String prBody, String diffChunk,
                                             int chunkNumber, int totalChunks, boolean isRetry,
                                             String systemPrompt, String effectiveModel,
                                             String additionalContext) {
                assertEquals(AbstractAiClient.DEFAULT_SYSTEM_PROMPT, systemPrompt);
                assertEquals("test-model", effectiveModel);
                return "Review with default prompt";
            }
        };

        String result = client.reviewDiff("Title", "Body", "some diff", null, null);
        assertTrue(result.contains("Review with default prompt"));
    }

    /**
     * Concrete test implementation of AbstractAiClient that provides controllable behavior.
     */
    static class TestAiClient extends AbstractAiClient {

        TestAiClient(String model, int maxTokens, int maxDiffCharsPerChunk,
                     int maxDiffChunks, int retryTruncatedChunkChars) {
            super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
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
        protected boolean isPromptTooLongError(HttpClientErrorException e) {
            return false;
        }
    }
}
