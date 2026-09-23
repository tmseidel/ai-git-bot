package org.remus.giteabot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiRetryProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRetryCommentNotifierTest {

    private final List<String> posted = new ArrayList<>();

    private final ProviderRetryCommentNotifier notifier =
            new ProviderRetryCommentNotifier(new AiRetryProperties());

    @AfterEach
    void clearContext() {
        AiRetryContext.clear();
    }

    private void install(String label) {
        AiRetryContext.install(new AiRetryContext.Notice(label, posted::add));
    }

    private static ProviderRetryNotifier.Event scheduledEvent(int attempt) {
        Instant next = Instant.now().plusSeconds(20);
        return new ProviderRetryNotifier.Event(attempt, 5, Duration.ofSeconds(20), next,
                List.of(Duration.ofSeconds(10), Duration.ofSeconds(20),
                        Duration.ofSeconds(40), Duration.ofSeconds(60)),
                new IllegalStateException("503 Service Unavailable: high demand"));
    }

    private static ProviderRetryNotifier.Event exhaustedEvent() {
        return new ProviderRetryNotifier.Event(5, 5, null, null,
                List.of(Duration.ofSeconds(10), Duration.ofSeconds(20),
                        Duration.ofSeconds(40), Duration.ofSeconds(60)),
                new IllegalStateException("503 Service Unavailable: high demand"));
    }

    @Test
    void scheduledNoticeAnnouncesTheNextAttempt() {
        install("agentic-review");

        notifier.retryScheduled(scheduledEvent(2));

        assertEquals(1, posted.size());
        String comment = posted.getFirst();
        assertTrue(comment.contains("automatic retry scheduled"), comment);
        assertTrue(comment.contains("Next attempt **3 of 5**"), comment);
        assertTrue(comment.contains("UTC"), comment);
        assertTrue(comment.contains("10s → 20s → 40s → 1m"), comment);
        assertTrue(comment.contains("high demand"), comment);
        assertTrue(comment.contains("`agentic-review`"), comment);
        assertTrue(comment.contains("(in 20s)"), comment);
    }

    @Test
    void scheduledNoticeReportsTheScheduledDelayNotAFreshClockReading() {
        install("agentic-review");
        // nextAttemptAt was taken when the retry was scheduled; a notice posted seconds
        // later must still report the wait that was actually scheduled.
        Instant next = Instant.now().plusSeconds(2);

        notifier.retryScheduled(new ProviderRetryNotifier.Event(1, 5, Duration.ofSeconds(20), next,
                List.of(Duration.ofSeconds(20)),
                new IllegalStateException("503 Service Unavailable: high demand")));

        assertEquals(1, posted.size());
        assertTrue(posted.getFirst().contains("(in 20s)"), posted.getFirst());
    }

    @Test
    void repeatedScheduledNoticesInsideTheCooldownAreSuppressed() {
        install("agentic-review");

        notifier.retryScheduled(scheduledEvent(1));
        notifier.retryScheduled(scheduledEvent(2));
        notifier.retryScheduled(scheduledEvent(3));

        assertEquals(1, posted.size());
    }

    @Test
    void scheduledNoticeIsPostedAgainAfterTheCooldownElapsed() {
        AiRetryProperties noCooldown = new AiRetryProperties();
        noCooldown.setNoticeCooldown(Duration.ZERO);
        ProviderRetryCommentNotifier noCooldownNotifier =
                new ProviderRetryCommentNotifier(noCooldown);
        install("agentic-review");

        noCooldownNotifier.retryScheduled(scheduledEvent(1));
        noCooldownNotifier.retryScheduled(scheduledEvent(2));

        assertEquals(2, posted.size());
    }

    @Test
    void exhaustedNoticeReportsTheGivingUp() {
        install("unit-test-author");

        notifier.retriesExhausted(exhaustedEvent());

        assertEquals(1, posted.size());
        String comment = posted.getFirst();
        assertTrue(comment.contains("automatic retries exhausted"), comment);
        assertTrue(comment.contains("**5 attempts**"), comment);
        assertTrue(comment.contains("No further retry is scheduled"), comment);
        assertTrue(comment.contains("`unit-test-author`"), comment);
    }

    @Test
    void exhaustedNoticeIsPostedOnlyOnce() {
        install("agentic-review");

        notifier.retriesExhausted(exhaustedEvent());
        notifier.retriesExhausted(exhaustedEvent());

        assertEquals(1, posted.size());
    }

    @Test
    void nothingIsPostedWithoutAWorkflowContext() {
        notifier.retryScheduled(scheduledEvent(1));
        notifier.retriesExhausted(exhaustedEvent());

        assertTrue(posted.isEmpty());
    }

    @Test
    void failingCommentApiNeverPropagates() {
        AiRetryContext.install(new AiRetryContext.Notice("agentic-review", body -> {
            throw new IllegalStateException("comment API down");
        }));

        notifier.retryScheduled(scheduledEvent(1));

        assertTrue(posted.isEmpty());
    }

    @Test
    void scheduleRendersMinutesForWholeMinuteWaits() {
        assertEquals("(single attempt)", ProviderRetryCommentNotifier.schedule(List.of()));
        assertEquals("5s → 1m → 2m", ProviderRetryCommentNotifier.schedule(
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(120))));
    }

    @Test
    void errorSnippetIsFlattenedAndTruncated() {
        Throwable error = new IllegalStateException("line one\nline two `quoted`");

        assertEquals("line one line two 'quoted'", ProviderRetryCommentNotifier.snippet(error));
        assertEquals("unknown error", ProviderRetryCommentNotifier.snippet(null));

        String longMessage = "x".repeat(900);
        assertEquals(500,
                ProviderRetryCommentNotifier.snippet(new IllegalStateException(longMessage)).length());
    }
}
