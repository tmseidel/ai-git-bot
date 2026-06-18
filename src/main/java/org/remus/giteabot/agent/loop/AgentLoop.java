package org.remus.giteabot.agent.loop;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.session.PendingMessage;
import org.remus.giteabot.agent.shared.AgentMetricsHolder;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generic agent loop that owns the chat/history/session-sync mechanics shared
 * by every agent flavour. The agent-specific decision logic lives in an
 * {@link AgentStrategy}.
 *
 * <p>Step 6: every round publishes the
 * {@code agent.tool_call.mode_total{mode, provider}} counter and the
 * {@code agent.tool_call.latency_seconds{mode, provider}} timer via
 * {@link AgentMetricsHolder}. When the strategy requests
 * {@link ToolingMode#NATIVE} <em>and</em> the resolved
 * {@link AiClient#supportsNativeTools()} returns {@code true} <em>and</em>
 * the strategy supplies non-empty {@link AgentStrategy#toolDescriptors()},
 * the loop calls {@link AiClient#chatWithTools chatWithTools} and forwards
 * the structured {@link ChatTurn} to
 * {@link AgentStrategy#step(AgentRunContext, ChatTurn, int)}. Otherwise the
 * loop transparently falls back to the legacy text path.</p>
 */
@Slf4j
public final class AgentLoop {

    private final AiClient aiClient;
    private final AgentSessionService sessionService;
    private final AgentBudget budget;
    private final String providerTag;
    private final ToolResultTruncator truncator;
    private final HistoryCompactor compactor;
    private final TokenUsageTracker tokenTracker;

    public AgentLoop(AiClient aiClient, AgentSessionService sessionService, AgentBudget budget) {
        this.aiClient = aiClient;
        this.sessionService = sessionService;
        this.budget = budget;
        this.providerTag = aiClient == null ? "unknown"
                : aiClient.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        this.truncator = new ToolResultTruncator(budget.maxToolResultChars());
        this.compactor = new HistoryCompactor(budget.maxHistoryChars(), 4);
        this.tokenTracker = new TokenUsageTracker(
                budget.contextWindowTokens(), budget.proactiveCompactionThreshold());
    }

    public AgentBudget budget() {
        return budget;
    }

    /**
     * Runs the loop until the strategy returns {@link StepDecision.Finish} or
     * the {@link AgentBudget#maxRounds()} cap is reached.
     */
    public LoopOutcome run(AgentRunContext ctx, String initialUserMessage, AgentStrategy strategy) {
        String systemPrompt = strategy.systemPrompt();
        List<AiMessage> history = new ArrayList<>(sessionService.toAiMessages(ctx.session()));
        // Messages produced during a round are accumulated here and persisted in a
        // single transaction at the round boundary (see flushRound) instead of one
        // transaction per message.
        List<PendingMessage> pending = new ArrayList<>();
        pending.add(new PendingMessage("user", initialUserMessage));
        String currentMessage = initialUserMessage;

        ToolingMode resolvedMode = resolveMode(strategy);
        ctx.setToolingMode(resolvedMode);
        List<ToolDescriptor> tools = resolvedMode == ToolingMode.NATIVE
                ? strategy.toolDescriptors() : List.of();
        log.debug("AgentLoop starting for issue #{}: provider={}, mode={}, tools={}",
                ctx.issueNumber(), providerTag, resolvedMode, tools.size());

        for (int round = 1; round <= budget.maxRounds(); round++) {
            log.debug("AgentLoop round {}/{} for issue #{}: calling AI (history={} msgs, prompt={} chars, mode={})",
                    round, budget.maxRounds(), ctx.issueNumber(), history.size(),
                    currentMessage == null ? 0 : currentMessage.length(), resolvedMode);

            AgentMetricsHolder.recordToolCallMode(modeTag(resolvedMode), providerTag);

            long started = System.nanoTime();
            ChatTurn turn;
            try {
                turn = callAiWithRetry(history, currentMessage, tools, systemPrompt, resolvedMode);
            } finally {
                AgentMetricsHolder.recordLatency(modeTag(resolvedMode), providerTag,
                        Duration.ofNanos(System.nanoTime() - started));
            }

            String aiResponse = turn.assistantText();
            log.debug("AgentLoop round {}/{} for issue #{}: AI returned {} chars, {} tool_call(s), reason={}",
                    round, budget.maxRounds(), ctx.issueNumber(),
                    aiResponse == null ? 0 : aiResponse.length(),
                    turn.toolCalls().size(), turn.stopReason());
            pending.add(new PendingMessage("assistant", aiResponse));

            // Track token usage (accumulated in-memory on the session; persisted by
            // flushRound) and trigger proactive compaction if needed.
            int promptChars = currentMessage == null ? 0 : currentMessage.length();
            tokenTracker.record(ctx.session(), turn, promptChars);
            if (tokenTracker.shouldCompactProactively(ctx.session())) {
                log.info("AgentLoop round {}/{} for issue #{}: proactive compaction triggered (usage: {}%)",
                        round, budget.maxRounds(), ctx.issueNumber(),
                        String.format("%.1f", tokenTracker.usageFraction(ctx.session()) * 100));
                compactor.compact(history);
            }

            StepDecision decision;
            try {
                decision = strategy.step(ctx, turn, round);
            } catch (RuntimeException e) {
                log.error("AgentLoop round {}/{} for issue #{}: strategy.step threw {}: {}",
                        round, budget.maxRounds(), ctx.issueNumber(),
                        e.getClass().getSimpleName(), e.getMessage(), e);
                throw e;
            }
            if (decision instanceof StepDecision.Finish(LoopOutcome outcome)) {
                log.debug("AgentLoop round {}/{} for issue #{}: strategy decided FINISH",
                        round, budget.maxRounds(), ctx.issueNumber());
                flushRound(ctx, pending);
                return outcome;
            }
            // The user message that drove this round and the assistant turn must always be
            // recorded in the in-flight history so the next AI call sees them. For native
            // rounds the assistant turn additionally carries the tool_calls payload so the
            // following tool-role messages can correlate by id (Anthropic/OpenAI both reject
            // tool messages whose call id is unknown to the preceding assistant message).
            if (currentMessage != null && !currentMessage.isEmpty()) {
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
            }
            history.add(AiMessage.builder()
                    .role("assistant")
                    .content(aiResponse)
                    .toolCalls(turn.toolCalls().isEmpty() ? null : turn.toolCalls())
                    .build());

            if (decision instanceof StepDecision.ContinueWithToolResults(
                    List<StepDecision.ToolCallResult> results, String follow
            )) {
                log.debug("AgentLoop round {}/{} for issue #{}: strategy decided CONTINUE_WITH_TOOL_RESULTS ({} results)",
                        round, budget.maxRounds(), ctx.issueNumber(), results.size());
                for (StepDecision.ToolCallResult r : results) {
                    String truncated = truncator.truncate(r.resultText());
                    history.add(AiMessage.builder()
                            .role("tool")
                            .toolCallId(r.toolCallId())
                            .toolResult(truncated)
                            .build());
                    // Persist a textual marker in the session log so post-hoc review still shows
                    // the tool flow. Full structured replay is intentionally out of scope here.
                    pending.add(new PendingMessage("tool",
                            "[" + r.toolCallId() + "] " + truncated));
                }
                currentMessage = (follow == null || follow.isEmpty()) ? "" : follow;
                if (!currentMessage.isEmpty()) {
                    pending.add(new PendingMessage("user", currentMessage));
                }
                // Compact in-memory history if it exceeds the budget
                compactor.compact(history);
                flushRound(ctx, pending);
                continue;
            }

            log.debug("AgentLoop round {}/{} for issue #{}: strategy decided CONTINUE",
                    round, budget.maxRounds(), ctx.issueNumber());
            String next = ((StepDecision.Continue) decision).nextUserMessage();
            currentMessage = next;
            pending.add(new PendingMessage("user", currentMessage));
            // Compact in-memory history if it exceeds the budget
            compactor.compact(history);
            flushRound(ctx, pending);
        }

        log.warn("AgentLoop exhausted {} rounds without final decision (issue #{})",
                budget.maxRounds(), ctx.issueNumber());
        return strategy.onBudgetExhausted(ctx);
    }

    /**
     * Persists the round's accumulated messages and the session's current
     * cumulative token counts in a single transaction, then clears the pending
     * buffer. A no-op when there is nothing to flush.
     *
     * <p>The context's session reference is intentionally <em>not</em> rebound to
     * the returned managed entity: {@link AgentSessionService#flushMessages}
     * re-fetches the row by id every round and the in-memory cumulative token
     * totals accumulate on the stable session reference, so rebinding would only
     * churn object identity (and risk lazy-loading a detached proxy) for no
     * functional gain.</p>
     */
    private void flushRound(AgentRunContext ctx, List<PendingMessage> pending) {
        if (pending.isEmpty()) {
            return;
        }
        // Transient sessions (e.g. the read-only review agent) have no id and are
        // never persisted; drop the buffer without touching the database.
        if (ctx.session().getId() == null) {
            pending.clear();
            return;
        }
        // Pass an immutable snapshot: the caller clears the live buffer right
        // after this returns, and the service must not alias the loop's state.
        sessionService.flushMessages(
                ctx.session().getId(),
                List.copyOf(pending),
                ctx.session().getTotalInputTokens(),
                ctx.session().getTotalOutputTokens());
        pending.clear();
    }

    private ToolingMode resolveMode(AgentStrategy strategy) {
        boolean clientSupportsNative = aiClient != null && aiClient.supportsNativeTools();
        ToolingMode resolved = ToolingMode.resolve(
                strategy.preferredToolMode(),
                clientSupportsNative,
                !strategy.toolDescriptors().isEmpty());
        if (strategy.preferredToolMode() == ToolingMode.NATIVE && resolved == ToolingMode.LEGACY) {
            if (!clientSupportsNative) {
                log.debug("Strategy requested NATIVE tools but client {} does not support them; "
                        + "falling back to LEGACY", providerTag);
            } else {
                log.debug("Strategy requested NATIVE tools but supplied no descriptors; falling back to LEGACY");
            }
        }
        return resolved;
    }

    private static String modeTag(ToolingMode mode) {
        return mode == ToolingMode.NATIVE ? "native" : "legacy";
    }

    /**
     * Calls the AI with retry-and-compact logic for "prompt too long" errors.
     * When the provider rejects the request because the prompt exceeds its
     * context window, this method aggressively compacts the in-memory history
     * (keeping only the last 2 compaction units) and retries once.
     *
     * <p>If the second attempt also fails with a prompt-too-long error, the
     * exception is re-thrown to the caller.</p>
     *
     * @return the {@link ChatTurn} from the successful AI call
     * @throws HttpClientErrorException if the retry also fails
     */
    private ChatTurn callAiWithRetry(List<AiMessage> history, String currentMessage,
                                     List<ToolDescriptor> tools, String systemPrompt,
                                     ToolingMode resolvedMode) {
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (resolvedMode == ToolingMode.NATIVE) {
                    return aiClient.chatWithTools(history, currentMessage, tools, systemPrompt,
                            null, budget.maxTokensPerCall());
                } else {
                    String text = aiClient.chat(history, currentMessage, systemPrompt,
                            null, budget.maxTokensPerCall());
                    return ChatTurn.text(text);
                }
            } catch (HttpClientErrorException e) {
                if (!aiClient.isPromptTooLongError(e) || attempt == maxRetries) {
                    throw e;
                }
                log.warn("AgentLoop: AI call failed with prompt-too-long error on attempt {}/{}. "
                        + "Aggressively compacting history and retrying. Error: {}",
                        attempt, maxRetries, e.getMessage());
                // Aggressive compaction: keep only the last 2 compaction units
                compactor.compactAggressively(history);
            }
        }
        // Unreachable, but keeps the compiler happy
        throw new IllegalStateException("callAiWithRetry: exceeded max retries");
    }
}

