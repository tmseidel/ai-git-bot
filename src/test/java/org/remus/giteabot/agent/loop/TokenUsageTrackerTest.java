package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TokenUsageTracker}, focusing on the corrected threshold
 * logic that uses the most recent call's input tokens instead of the
 * cumulative total.
 */
class TokenUsageTrackerTest {

    private AgentSessionService sessionService;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        sessionService = mock(AgentSessionService.class);
        session = new AgentSession();
        session.setId(1L);
        session.setRepoOwner("test");
        session.setRepoName("repo");
        session.setIssueNumber(1L);
    }

    @Test
    void record_withExplicitTokens_usesProvidedValues() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);
        ChatTurn turn = new ChatTurn("response", null, null, 1000L, 500L);

        tracker.record(session, turn, 10_000);

        assertEquals(1000L, session.getTotalInputTokens());
        assertEquals(500L, session.getTotalOutputTokens());
        verify(sessionService).recordTokenUsage(session, 1000L, 500L);
    }

    @Test
    void record_withoutExplicitTokens_estimatesFromChars() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);
        ChatTurn turn = ChatTurn.text("response"); // 8 chars -> 2 tokens

        tracker.record(session, turn, 4000); // 4000 chars -> 1000 tokens

        assertEquals(1000L, session.getTotalInputTokens());
        assertEquals(2L, session.getTotalOutputTokens());
    }

    @Test
    void record_accumulatesTokensAcrossCalls() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        ChatTurn turn1 = new ChatTurn("r1", null, null, 1000L, 100L);
        ChatTurn turn2 = new ChatTurn("r2", null, null, 2000L, 200L);

        tracker.record(session, turn1, 10_000);
        tracker.record(session, turn2, 20_000);

        assertEquals(3000L, session.getTotalInputTokens());
        assertEquals(300L, session.getTotalOutputTokens());
    }

    @Test
    void shouldCompactProactively_belowThreshold_returnsFalse() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Record a call with 100k input tokens (50% of 200k context)
        ChatTurn turn = new ChatTurn("r", null, null, 100_000L, 1000L);
        tracker.record(session, turn, 400_000);

        assertFalse(tracker.shouldCompactProactively(session));
    }

    @Test
    void shouldCompactProactively_aboveThreshold_returnsTrue() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Record a call with 150k input tokens (75% of 200k context)
        ChatTurn turn = new ChatTurn("r", null, null, 150_000L, 1000L);
        tracker.record(session, turn, 600_000);

        assertTrue(tracker.shouldCompactProactively(session));
    }

    @Test
    void shouldCompactProactively_usesLastCallNotCumulative() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // First call: 50k tokens
        ChatTurn turn1 = new ChatTurn("r1", null, null, 50_000L, 1000L);
        tracker.record(session, turn1, 200_000);
        assertFalse(tracker.shouldCompactProactively(session));

        // Second call: 30k tokens (cumulative now 80k, but last call is 30k = 15% of 200k)
        ChatTurn turn2 = new ChatTurn("r2", null, null, 30_000L, 500L);
        tracker.record(session, turn2, 120_000);

        // Should NOT trigger because last call was only 30k (15%), even though
        // cumulative is 80k (40%)
        assertFalse(tracker.shouldCompactProactively(session));
        assertEquals(80_000L, session.getTotalInputTokens()); // cumulative for audit
    }

    @Test
    void shouldCompactProactively_largeLastCall_triggers() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Multiple small calls
        for (int i = 0; i < 10; i++) {
            ChatTurn small = new ChatTurn("r", null, null, 5_000L, 100L);
            tracker.record(session, small, 20_000);
        }
        assertFalse(tracker.shouldCompactProactively(session)); // cumulative 50k, last 5k

        // One large call
        ChatTurn large = new ChatTurn("r", null, null, 150_000L, 1000L);
        tracker.record(session, large, 600_000);

        assertTrue(tracker.shouldCompactProactively(session)); // last call 150k > 70%
    }

    @Test
    void shouldCompactProactively_nullSession_returnsFalse() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        assertFalse(tracker.shouldCompactProactively(null));
    }

    @Test
    void shouldCompactProactively_zeroContextWindow_returnsFalse() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 0, 0.7);

        ChatTurn turn = new ChatTurn("r", null, null, 150_000L, 1000L);
        tracker.record(session, turn, 600_000);

        assertFalse(tracker.shouldCompactProactively(session));
    }

    @Test
    void usageFraction_belowThreshold_returnsCorrectFraction() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        ChatTurn turn = new ChatTurn("r", null, null, 100_000L, 1000L);
        tracker.record(session, turn, 400_000);

        assertEquals(0.5, tracker.usageFraction(session), 0.001);
    }

    @Test
    void usageFraction_zeroContextWindow_returnsZero() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 0, 0.7);

        ChatTurn turn = new ChatTurn("r", null, null, 100_000L, 1000L);
        tracker.record(session, turn, 400_000);

        assertEquals(0.0, tracker.usageFraction(session));
    }

    @Test
    void usageFraction_reflectsLastCallOnly() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Large call
        ChatTurn large = new ChatTurn("r", null, null, 150_000L, 1000L);
        tracker.record(session, large, 600_000);
        assertEquals(0.75, tracker.usageFraction(session), 0.001);

        // Small call
        ChatTurn small = new ChatTurn("r", null, null, 20_000L, 100L);
        tracker.record(session, small, 80_000);

        // Should reflect the small call, not cumulative
        assertEquals(0.1, tracker.usageFraction(session), 0.001);
    }

    @Test
    void record_nullSession_noOp() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);
        ChatTurn turn = new ChatTurn("r", null, null, 1000L, 100L);

        // Should not throw
        tracker.record(null, turn, 4000);

        verifyNoInteractions(sessionService);
    }

    @Test
    void threshold_atExactBoundary_triggers() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Exactly at 70%
        ChatTurn turn = new ChatTurn("r", null, null, 140_000L, 1000L);
        tracker.record(session, turn, 560_000);

        assertTrue(tracker.shouldCompactProactively(session));
    }

    @Test
    void threshold_justBelowBoundary_doesNotTrigger() {
        TokenUsageTracker tracker = new TokenUsageTracker(sessionService, 200_000, 0.7);

        // Just below 70%
        ChatTurn turn = new ChatTurn("r", null, null, 139_999L, 1000L);
        tracker.record(session, turn, 559_996);

        assertFalse(tracker.shouldCompactProactively(session));
    }
}
