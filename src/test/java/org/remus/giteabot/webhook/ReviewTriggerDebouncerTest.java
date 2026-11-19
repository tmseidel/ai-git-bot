package org.remus.giteabot.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewTriggerDebouncerTest {

    private Instant now = Instant.parse("2026-11-12T10:00:00Z");
    private ReviewTriggerDebouncer debouncer;
    private Bot bot;

    @BeforeEach
    void setUp() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now);
        debouncer = new ReviewTriggerDebouncer(clock);
        bot = new Bot();
        bot.setId(1L);
    }

    @Test
    void secondTriggerForTheSameCommitWithinTheWindow_isDropped() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        now = now.plus(ReviewTriggerDebouncer.WINDOW.minusSeconds(1));

        assertFalse(debouncer.tryClaim(bot, payload(42L, "abc123")));
    }

    @Test
    void triggerForTheSameCommitAfterTheWindow_isClaimed() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        now = now.plus(ReviewTriggerDebouncer.WINDOW);

        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));
    }

    @Test
    void triggerForANewCommit_isClaimed() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        assertTrue(debouncer.tryClaim(bot, payload(42L, "def456")));
    }

    @Test
    void triggersForOtherPullRequestsOrBots_areIndependent() {
        Bot otherBot = new Bot();
        otherBot.setId(2L);

        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        assertTrue(debouncer.tryClaim(bot, payload(43L, "abc123")));
        assertTrue(debouncer.tryClaim(otherBot, payload(42L, "abc123")));
    }

    @Test
    void forget_endsTheWindow() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        debouncer.forget(bot, payload(42L, "abc123"));

        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));
    }

    @Test
    void release_givesTheClaimBack() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));

        debouncer.release(bot, payload(42L, "abc123"));

        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));
    }

    @Test
    void release_leavesAClaimForANewerCommitAlone() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, "abc123")));
        assertTrue(debouncer.tryClaim(bot, payload(42L, "def456")));

        debouncer.release(bot, payload(42L, "abc123"));

        assertFalse(debouncer.tryClaim(bot, payload(42L, "def456")));
    }

    @Test
    void payloadWithoutHeadSha_isNeverDebounced() {
        assertTrue(debouncer.tryClaim(bot, payload(42L, null)));
        assertTrue(debouncer.tryClaim(bot, payload(42L, null)));
    }

    private static WebhookPayload payload(long number, String sha) {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setOwner(owner);
        repository.setName("repo");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setSha(sha);
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(number);
        pullRequest.setHead(head);
        WebhookPayload payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setPullRequest(pullRequest);
        return payload;
    }
}
