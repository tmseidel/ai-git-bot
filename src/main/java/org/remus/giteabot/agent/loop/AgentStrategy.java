package org.remus.giteabot.agent.loop;

import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolDescriptor;

import java.util.List;

/**
 * Strategy contract for one agent flavour driven by {@link AgentLoop}.
 *
 * <p>Concrete strategies (writer, coding, …) own:</p>
 * <ul>
 *     <li>System-prompt selection.</li>
 *     <li>AI-response parsing into a domain plan.</li>
 *     <li>All side effects: tool execution, branch switching, comment posting,
 *         workspace inspection, sub-budget enforcement.</li>
 *     <li>The decision to continue with a follow-up prompt or terminate.</li>
 * </ul>
 *
 * <p>The loop only owns the chat/history/session synchronisation mechanics
 * and the hard {@link AgentBudget#maxRounds()} cap.</p>
 *
 * <p>Step 6: a strategy may opt in to provider-native function calling by
 * returning {@link ToolingMode#NATIVE} from {@link #preferredToolMode()} and
 * non-empty {@link #toolDescriptors()}. When the resolved
 * {@link org.remus.giteabot.ai.AiClient AiClient} also supports native tools
 * the loop calls {@code chatWithTools(...)} and forwards the structured
 * {@link ChatTurn} via {@link #step(AgentRunContext, ChatTurn, int)}. Legacy
 * turns retain their completion metadata through {@link #stepLegacy}, whose
 * default implementation delegates to the text-based {@code step(...)} path.</p>
 */
public interface AgentStrategy {

    /** System prompt to send on every provider chat call. */
    String systemPrompt();

    /**
     * Optional user reminder appended to conversation history before a model call.
     * The remaining loop count includes that upcoming call; strategies may also
     * consider their own budget for the resolved tooling mode. Once added, the
     * hook is no longer called and the reminder is restored before requests if
     * compaction removes it. {@code null} adds nothing.
     */
    default String roundBudgetReminder(int remainingRounds, ToolingMode mode) {
        return null;
    }

    /**
     * Optional context sent with every model call but never stored in history, so
     * compaction cannot drop it and repeated calls do not accumulate copies. It is
     * appended to the call's user message. {@code null} adds nothing.
     */
    default String requiredContext() {
        return null;
    }

    /** Logical turns reserved for terminal submission and one repair, not available for exploration. */
    default int reservedFinalizationRounds() { return 0; }

    /** Restricts an opted-in protocol to its terminal operations before the reserved turns. */
    default void beginFinalization() { }

    /**
     * Decide what to do given the AI's latest assistant turn (text path).
     *
     * <p>May execute tools, post comments, mutate {@code ctx} (e.g. update
     * {@link AgentRunContext#setBaseBranch}), and update session status.</p>
     *
     * @param ctx           per-run context (also writeable for branch switches)
     * @param aiResponse    raw assistant content from the latest AI call
     * @param round         1-based loop iteration counter
     * @return next decision
     */
    StepDecision step(AgentRunContext ctx, String aiResponse, int round);

    /**
     * Handles a legacy text-protocol turn with its provider completion metadata.
     * The default delegates to the existing text handler. Strategies that need
     * completion validation can override this before parsing the text, without
     * routing legacy JSON through their native tool handler.
     */
    default StepDecision stepLegacy(AgentRunContext ctx, ChatTurn turn, int round) {
        return step(ctx, turn.assistantText(), round);
    }

    /**
     * Hook called when the loop exhausts {@link AgentBudget#maxRounds()}
     * without the strategy returning a {@link StepDecision.Finish}. Strategies
     * use this to record their own "no success" outcome.
     */
    LoopOutcome onBudgetExhausted(AgentRunContext ctx);

    // ---------------------------------------------------------------------
    // Step 6 — opt-in native tool calling.
    // ---------------------------------------------------------------------

    /**
     * Default {@link ToolingMode#LEGACY}; override to {@link ToolingMode#NATIVE}
     * once the strategy implements
     * {@link #step(AgentRunContext, ChatTurn, int)} and returns useful
     * {@link #toolDescriptors()}.
     */
    default ToolingMode preferredToolMode() {
        return ToolingMode.LEGACY;
    }

    /**
     * Tools the model is allowed to invoke when running in
     * {@link ToolingMode#NATIVE}. Default is empty, which forces the loop
     * back onto the legacy text path even if the client supports native tools.
     */
    default List<ToolDescriptor> toolDescriptors() {
        return List.of();
    }

    /**
     * Native tool-calling variant of {@link #step(AgentRunContext, String, int)}.
     * The default implementation delegates to the text path using
     * {@link ChatTurn#assistantText()} so existing strategies keep working
     * untouched. Strategies that opt in to {@link ToolingMode#NATIVE} should
     * override this to dispatch {@link ChatTurn#toolCalls()} directly.
     */
    default StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        return step(ctx, turn.assistantText(), round);
    }
}
