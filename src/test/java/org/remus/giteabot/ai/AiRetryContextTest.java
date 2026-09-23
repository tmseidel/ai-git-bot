package org.remus.giteabot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiRetryProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Nested install semantics: a flow that starts inside another orchestrated flow
 * on the same thread must be able to clear its own notice without dropping the
 * enclosing one.
 */
class AiRetryContextTest {

    private final ProviderRetryCommentNotifier notifier =
            new ProviderRetryCommentNotifier(new AiRetryProperties());

    @AfterEach
    void clearContext() {
        AiRetryContext.clear();
    }

    private static ProviderRetryNotifier.Event scheduled(int attempt) {
        return new ProviderRetryNotifier.Event(attempt, 5, Duration.ofSeconds(20),
                Instant.now().plusSeconds(20), List.of(Duration.ofSeconds(20)),
                new IllegalStateException("503 Service Unavailable: high demand"));
    }

    @Test
    void clearRestoresTheNoticeThatWasInstalledBefore() {
        AiRetryContext.install(new AiRetryContext.Notice("outer-wf", body -> { }));
        AiRetryContext.install(new AiRetryContext.Notice("inner-wf", body -> { }));
        assertEquals("inner-wf", AiRetryContext.notice().label());

        AiRetryContext.clear();

        assertEquals("outer-wf", AiRetryContext.notice().label(),
                "the inner flow's clear() must restore the outer notice, not drop it");
    }

    @Test
    void clearingEveryLevelLeavesTheThreadWithoutANotice() {
        AiRetryContext.install(new AiRetryContext.Notice("outer-wf", body -> { }));
        AiRetryContext.install(new AiRetryContext.Notice("inner-wf", body -> { }));

        AiRetryContext.clear();
        AiRetryContext.clear();

        assertNull(AiRetryContext.notice());
    }

    @Test
    void clearWithoutAnInstalledNoticeIsANoOp() {
        AiRetryContext.clear();
        AiRetryContext.clear();

        assertNull(AiRetryContext.notice());
    }

    @Test
    void everyLevelKeepsItsOwnNoticeBudget() {
        List<String> outerPosts = new ArrayList<>();
        AiRetryContext.install(new AiRetryContext.Notice("outer-wf", outerPosts::add));

        notifier.retryScheduled(scheduled(1));           // claims the outer cooldown window
        AiRetryContext.install(new AiRetryContext.Notice("inner-wf", body -> { }));
        AiRetryContext.clear();                          // the inner flow ends
        notifier.retryScheduled(scheduled(2));           // still inside the outer cooldown

        assertEquals(1, outerPosts.size(),
                "an inner flow must not hand the outer flow a fresh notice budget");
    }
}
