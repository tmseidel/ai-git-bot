package org.remus.giteabot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiRetryProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryAiClientTest {

    /** AI client whose calls fail with the scripted errors before succeeding. */
    private static class ScriptedAiClient implements AiClient {

        private final Deque<RuntimeException> failures = new ArrayDeque<>();
        private final String answer;
        int calls;

        ScriptedAiClient(String answer, RuntimeException... failures) {
            this.answer = answer;
            this.failures.addAll(List.of(failures));
        }

        @Override
        public String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage) {
            return next();
        }

        @Override
        public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                           String systemPrompt, String modelOverride) {
            return next();
        }

        @Override
        public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                           String systemPrompt, String modelOverride, Integer maxTokensOverride) {
            return next();
        }

        @Override
        public ChatTurn chatWithTools(List<AiMessage> conversationHistory, String newUserMessage,
                                      List<ToolDescriptor> tools, String systemPrompt,
                                      String modelOverride, Integer maxTokensOverride) {
            return ChatTurn.text(next());
        }

        @Override
        public void reportError(Throwable error) {
        }

        @Override
        public String getModel() {
            return "test-model";
        }

        private String next() {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            return answer;
        }
    }

    private static class RecordingNotifier implements ProviderRetryNotifier {

        final List<Event> scheduled = new ArrayList<>();
        final List<Event> exhausted = new ArrayList<>();

        @Override
        public void retryScheduled(Event event) {
            scheduled.add(event);
        }

        @Override
        public void retriesExhausted(Event event) {
            exhausted.add(event);
        }
    }

    private final List<Long> sleeps = new ArrayList<>();

    @AfterEach
    void clearContext() {
        AiRetryContext.clear();
    }

    private RetryAiClient clientFor(ScriptedAiClient delegate, AiRetryProperties properties,
                                    ProviderRetryNotifier notifier) {
        return new RetryAiClient(delegate, properties, notifier, sleeps::add, new Random(42));
    }

    private static AiRetryProperties properties() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setInitialDelay(Duration.ofSeconds(10));
        properties.setMultiplier(2.0);
        properties.setMaxDelay(Duration.ofSeconds(60));
        properties.setJitter(0.0);
        return properties;
    }

    /** The exact provider payload from the reported issue. */
    private static HttpServerErrorException highDemand() {
        String body = """
                { "error": { "code": 503, "message": "This model is currently experiencing high \
                demand. Spikes in demand are usually temporary. Please try again later.", \
                "status": "UNAVAILABLE" }}""";
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException unavailable() {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                HttpHeaders.EMPTY, "upstream unavailable".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void chat_retriesProviderOverloadAndReturnsTheAnswer() {
        ScriptedAiClient delegate = new ScriptedAiClient("review text", highDemand(), highDemand());
        RecordingNotifier notifier = new RecordingNotifier();

        String result = clientFor(delegate, properties(), notifier)
                .chat(List.of(), "review this", "system", null);

        assertEquals("review text", result);
        assertEquals(3, delegate.calls);
        assertEquals(List.of(10_000L, 20_000L), sleeps);
        assertEquals(2, notifier.scheduled.size());
        assertEquals(1, notifier.scheduled.getFirst().attempt());
        assertEquals(5, notifier.scheduled.getFirst().maxAttempts());
        assertEquals(Duration.ofSeconds(10), notifier.scheduled.getFirst().delay());
        assertTrue(notifier.exhausted.isEmpty());
    }

    @Test
    void chat_givesUpAfterTheAttemptBudgetAndRethrowsTheProviderError() {
        ScriptedAiClient delegate = new ScriptedAiClient("never",
                unavailable(), unavailable(), unavailable(), unavailable(), unavailable());
        RecordingNotifier notifier = new RecordingNotifier();

        HttpServerErrorException thrown = assertThrows(HttpServerErrorException.class,
                () -> clientFor(delegate, properties(), notifier).chat(List.of(), "hi", null, null));

        assertEquals(503, thrown.getStatusCode().value());
        assertEquals(5, delegate.calls);
        assertEquals(List.of(10_000L, 20_000L, 40_000L, 60_000L), sleeps);
        assertEquals(4, notifier.scheduled.size());
        assertEquals(1, notifier.exhausted.size());
        assertEquals(5, notifier.exhausted.getFirst().attempt());
        assertNull(notifier.exhausted.getFirst().delay());
        assertNull(notifier.exhausted.getFirst().nextAttemptAt());
    }

    @Test
    void submitReviewPrompt_isRetriedToo() {
        ScriptedAiClient delegate = new ScriptedAiClient("review", unavailable());
        RecordingNotifier notifier = new RecordingNotifier();

        String result = clientFor(delegate, properties(), notifier)
                .submitReviewPrompt("system", null, "user");

        assertEquals("review", result);
        assertEquals(2, delegate.calls);
        assertEquals(List.of(10_000L), sleeps);
    }

    @Test
    void chatWithTools_isRetriedToo() {
        ScriptedAiClient delegate = new ScriptedAiClient("answer", unavailable());
        RecordingNotifier notifier = new RecordingNotifier();

        ChatTurn turn = clientFor(delegate, properties(), notifier)
                .chatWithTools(List.of(), "hi", List.of(), "system", null, null);

        assertEquals("answer", turn.assistantText());
        assertEquals(2, delegate.calls);
        assertEquals(List.of(10_000L), sleeps);
    }

    @Test
    void chat_doesNotRetryFailuresThatAreNotProviderOverloads() {
        ScriptedAiClient delegate = new ScriptedAiClient("never",
                new IllegalStateException("401 Unauthorized"));
        RecordingNotifier notifier = new RecordingNotifier();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> clientFor(delegate, properties(), notifier).chat(List.of(), "hi", null, null));

        assertEquals("401 Unauthorized", thrown.getMessage());
        assertEquals(1, delegate.calls);
        assertTrue(sleeps.isEmpty());
        assertTrue(notifier.scheduled.isEmpty());
    }

    @Test
    void chat_doesNotRetryRateLimits() {
        ScriptedAiClient delegate = new ScriptedAiClient("never",
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
        RecordingNotifier notifier = new RecordingNotifier();

        assertThrows(HttpClientErrorException.class,
                () -> clientFor(delegate, properties(), notifier).chat(List.of(), "hi", null, null));

        assertEquals(1, delegate.calls);
        assertTrue(sleeps.isEmpty());
        assertTrue(notifier.scheduled.isEmpty());
    }

    @Test
    void chat_doesNotRetryWhenTheRetryIsDisabled() {
        AiRetryProperties disabled = properties();
        disabled.setEnabled(false);
        ScriptedAiClient delegate = new ScriptedAiClient("never", unavailable());
        RecordingNotifier notifier = new RecordingNotifier();

        assertThrows(HttpServerErrorException.class,
                () -> clientFor(delegate, disabled, notifier).chat(List.of(), "hi", null, null));

        assertEquals(1, delegate.calls);
        assertTrue(notifier.exhausted.isEmpty());
    }

    @Test
    void chat_abortsTheRetryWhenTheWaitIsInterrupted() {
        ScriptedAiClient delegate = new ScriptedAiClient("never", unavailable());
        RecordingNotifier notifier = new RecordingNotifier();
        RetryAiClient.Sleeper interrupting = millis -> {
            throw new InterruptedException("stop");
        };
        RetryAiClient client = new RetryAiClient(delegate, properties(), notifier, interrupting,
                new Random(42));

        try {
            HttpServerErrorException thrown = assertThrows(HttpServerErrorException.class,
                    () -> client.chat(List.of(), "hi", null, null));

            assertEquals(503, thrown.getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void plannedDelays_growExponentiallyAndCapAtMaxDelay() {
        RetryAiClient client = clientFor(new ScriptedAiClient("ok"), properties(),
                new RecordingNotifier());

        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(20),
                        Duration.ofSeconds(40), Duration.ofSeconds(60)),
                client.plannedDelays(5));
        assertEquals(List.of(), client.plannedDelays(1));
    }

    @Test
    void jitter_keepsTheWaitInsideTheConfiguredSpread() {
        AiRetryProperties jittered = properties();
        jittered.setJitter(0.2);
        ScriptedAiClient delegate = new ScriptedAiClient("answer", unavailable());

        clientFor(delegate, jittered, new RecordingNotifier()).chat(List.of(), "hi", null, null);

        assertEquals(1, sleeps.size());
        assertTrue(sleeps.getFirst() >= 8_000 && sleeps.getFirst() <= 12_000,
                "expected 10s ±20% but waited " + sleeps.getFirst() + "ms");
    }

    @Test
    void successfulCallDoesNotSleepOrNotify() {
        ScriptedAiClient delegate = new ScriptedAiClient("answer");
        RecordingNotifier notifier = new RecordingNotifier();

        String result = clientFor(delegate, properties(), notifier).chat(List.of(), "hi", null, null);

        assertEquals("answer", result);
        assertEquals(1, delegate.calls);
        assertTrue(sleeps.isEmpty());
        assertTrue(notifier.scheduled.isEmpty());
    }
}
