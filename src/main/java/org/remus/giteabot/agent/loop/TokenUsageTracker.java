package org.remus.giteabot.agent.loop;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.ChatTurn;

/**
 * Tracks token usage for a single agent session and triggers proactive
 * compaction when the most recent AI call's prompt size approaches the
 * context window limit.
 *
 * <p>Token usage is estimated from character counts when the provider does not
 * return explicit token counts. The rough heuristic is 1 token ≈ 4 characters.</p>
 *
 * <p>Proactive compaction is triggered when the <em>last call's</em> input
 * tokens exceed a configurable threshold (default: 70% of the context window).
 * Cumulative totals are tracked on {@link AgentSession} for cost/audit only —
 * they grow superlinearly across rounds (each call's input already includes
 * the full history) and are therefore useless for measuring context-window
 * pressure.</p>
 *
 * <p>This class is session-agnostic at construction time; the session is
 * supplied per call so the same tracker instance can be reused across
 * different run contexts.</p>
 */
@Slf4j
public final class TokenUsageTracker {

    /** Rough approximation: 1 token ≈ 4 characters for English text. */
    private static final int CHARS_PER_TOKEN = 4;

    private final AgentSessionService sessionService;
    private final int contextWindowTokens;
    private final double proactiveCompactionThreshold;

    /**
     * Input tokens from the most recent AI call. Represents the actual prompt
     * size (including full history) and is the correct measure for context-
     * window pressure. Reset on every {@link #record} call.
     */
    private long lastInputTokens;

    /**
     * @param sessionService             the session service for persisting token counts
     * @param contextWindowTokens        the model's context window size in tokens
     * @param proactiveCompactionThreshold fraction (0.0-1.0) at which proactive
     *                                   compaction triggers (e.g. 0.7 = 70%)
     */
    public TokenUsageTracker(AgentSessionService sessionService,
                             int contextWindowTokens, double proactiveCompactionThreshold) {
        this.sessionService = sessionService;
        this.contextWindowTokens = contextWindowTokens;
        this.proactiveCompactionThreshold = proactiveCompactionThreshold;
    }

    /**
     * Records token usage from a {@link ChatTurn}. If the turn carries explicit
     * token counts, those are used. Otherwise, usage is estimated from character
     * counts.
     *
     * <p>Cumulative totals are persisted on the session for cost/audit.
     * The {@link #lastInputTokens} field is updated with this call's input
     * tokens for the compaction decision.</p>
     *
     * @param session     the agent session to accumulate tokens into
     * @param turn        the AI response turn
     * @param promptChars the character count of the prompt sent in this round
     */
    public void record(AgentSession session, ChatTurn turn, int promptChars) {
        if (session == null) {
            return;
        }
        long inputTokens;
        long outputTokens;

        if (turn.inputTokens() > 0) {
            inputTokens = turn.inputTokens();
        } else {
            inputTokens = estimateTokens(promptChars);
        }

        if (turn.outputTokens() > 0) {
            outputTokens = turn.outputTokens();
        } else {
            outputTokens = estimateTokens(turn.assistantText() == null ? 0 : turn.assistantText().length());
        }

        this.lastInputTokens = inputTokens;
        session.accumulateTokens(inputTokens, outputTokens);
        sessionService.recordTokenUsage(session, session.getTotalInputTokens(), session.getTotalOutputTokens());

        log.debug("TokenUsageTracker: session={} round tokens: in={}, out={}, cumulative: in={}, out={}",
                session.getId(), inputTokens, outputTokens,
                session.getTotalInputTokens(), session.getTotalOutputTokens());
    }

    /**
     * Returns {@code true} if the most recent AI call's input tokens exceed
     * the proactive compaction threshold of the context window.
     */
    public boolean shouldCompactProactively(AgentSession session) {
        if (session == null || contextWindowTokens <= 0) {
            return false;
        }
        double usage = (double) lastInputTokens / contextWindowTokens;
        if (usage >= proactiveCompactionThreshold) {
            log.info("TokenUsageTracker: session={} last call input {}% exceeds threshold {}%, "
                    + "proactive compaction recommended",
                    session.getId(),
                    String.format("%.1f", usage * 100),
                    String.format("%.1f", proactiveCompactionThreshold * 100));
            return true;
        }
        return false;
    }

    /**
     * Returns the most recent AI call's input token count as a fraction
     * (0.0-1.0) of the context window, or 0 if the context window is unknown.
     */
    public double usageFraction(AgentSession session) {
        if (contextWindowTokens <= 0) {
            return 0.0;
        }
        return (double) lastInputTokens / contextWindowTokens;
    }

    private static long estimateTokens(int chars) {
        return Math.max(1L, (long) Math.ceil((double) chars / CHARS_PER_TOKEN));
    }
}
