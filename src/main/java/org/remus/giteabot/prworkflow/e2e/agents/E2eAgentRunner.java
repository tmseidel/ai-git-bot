package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolContext;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lightweight chat-and-dispatch loop used by the three E2E agents
 * ({@code TestPlannerAgent}, {@code TestAuthorAgent}, {@code TestRunnerAgent}).
 *
 * <p>This is intentionally a separate, simpler runner than
 * {@link org.remus.giteabot.agent.loop.AgentLoop}: the coding/writer loop is
 * tied to {@code AgentSession} persistence, the {@code AgentToolRouter},
 * branch switching and an {@code AgentRunContext} that assumes a cloned
 * source-repository workspace. E2E agents have none of these — they only
 * dispatch {@code PR_WORKFLOW} tools against the sandboxed PR test
 * workspace and care about chat turns + tool results. Sharing
 * {@link AgentLoop} would force us to fabricate fake sessions, fake
 * branches and fake source-workspace paths, so we keep this loop dedicated
 * but mirror its tooling-mode resolution semantics one-to-one (native
 * function calling when supported, transparent legacy fallback otherwise).</p>
 */
@Slf4j
public final class E2eAgentRunner {


    private final AiClient aiClient;
    private final PrWorkflowToolExecutor toolExecutor;
    private final PrWorkflowToolContext toolContext;
    private final List<ToolDescriptor> toolDescriptors;
    private final String systemPrompt;
    private final int maxRounds;
    private final Integer maxTokens;
    private final String agentLabel;

    public E2eAgentRunner(AiClient aiClient,
                          PrWorkflowToolExecutor toolExecutor,
                          PrWorkflowToolContext toolContext,
                          List<ToolDescriptor> toolDescriptors,
                          String systemPrompt,
                          int maxRounds,
                          Integer maxTokens,
                          String agentLabel) {
        this.aiClient = aiClient;
        this.toolExecutor = toolExecutor;
        this.toolContext = toolContext;
        this.toolDescriptors = toolDescriptors == null ? List.of() : List.copyOf(toolDescriptors);
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.maxTokens = maxTokens;
        this.agentLabel = agentLabel == null ? "e2e-agent" : agentLabel;
    }

    /**
     * Result of one {@link #run(String)} call.
     *
     * @param lastAssistantText final assistant text (empty when the model only
     *                          emitted tool calls in the last round)
     * @param toolInvocations   every PR-workflow tool that was dispatched, in order
     * @param rounds            number of round-trips actually consumed
     * @param budgetExhausted   {@code true} when the loop hit {@link #maxRounds}
     *                          without ever observing a text-only assistant turn
     */
    public record Result(String lastAssistantText,
                         List<ToolInvocation> toolInvocations,
                         int rounds,
                         boolean budgetExhausted) {
        public Result {
            toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        }
    }

    /** One executed tool call with the textual result returned to the model. */
    public record ToolInvocation(String toolName, Map<String, Object> args, String result) { }

    /**
     * Runs the loop. Returns when the model produces a text-only turn (final
     * answer), the loop runs out of rounds, or the AI client throws. The
     * exception path returns a {@link Result} flagged as
     * {@code budgetExhausted=true} so callers can surface it as a graceful
     * "agent failed" outcome without crashing the workflow.
     */
    public Result run(String initialUserMessage) {
        ToolingMode mode = resolveMode();
        List<AiMessage> history = new ArrayList<>();
        String currentMessage = initialUserMessage;
        List<ToolInvocation> invocations = new ArrayList<>();
        String lastAssistantText = "";

        for (int round = 1; round <= maxRounds; round++) {
            ChatTurn turn;
            try {
                if (mode == ToolingMode.NATIVE) {
                    turn = aiClient.chatWithTools(history, currentMessage, toolDescriptors,
                            systemPrompt, null, maxTokens);
                } else {
                    String text = aiClient.chat(history, currentMessage, systemPrompt, null, maxTokens);
                    turn = ChatTurn.text(text);
                }
            } catch (RuntimeException e) {
                log.warn("[{}] AI call failed in round {}: {}", agentLabel, round, e.getMessage(), e);
                return new Result(lastAssistantText, invocations, round - 1, true);
            }
            lastAssistantText = turn.assistantText() == null ? "" : turn.assistantText();
            log.debug("[{}] round {}/{}: assistantTextLen={} toolCalls={} stopReason={}",
                    agentLabel, round, maxRounds,
                    lastAssistantText.length(), turn.toolCalls().size(), turn.stopReason());

            if (!turn.hasToolCalls()) {
                return new Result(lastAssistantText, invocations, round, false);
            }

            // Append the user/assistant turn pair before processing tool calls.
            if (currentMessage != null && !currentMessage.isEmpty()) {
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
            }
            history.add(AiMessage.builder()
                    .role("assistant")
                    .content(lastAssistantText)
                    .toolCalls(turn.toolCalls())
                    .build());

            // Dispatch every tool call to the executor and feed results back.
            for (ToolCall call : turn.toolCalls()) {
                Map<String, Object> args = extractArgs(call.args());
                String result = toolExecutor.execute(call.name(), args, toolContext);
                invocations.add(new ToolInvocation(call.name(), args, result));
                history.add(AiMessage.builder()
                        .role("tool")
                        .toolCallId(call.id())
                        .toolResult(result)
                        .build());
            }
            // Default to an empty user message so the model continues from tool results.
            currentMessage = "";
        }

        log.warn("[{}] exhausted {} rounds without a terminal text turn", agentLabel, maxRounds);
        return new Result(lastAssistantText, invocations, maxRounds, true);
    }

    private ToolingMode resolveMode() {
        return ToolingMode.resolve(
                ToolingMode.NATIVE,
                aiClient != null && aiClient.supportsNativeTools(),
                !toolDescriptors.isEmpty());
    }

    /**
     * Flattens a model-supplied {@code args} JSON object into a plain
     * {@link Map} for {@link PrWorkflowToolExecutor}. Array values (used by
     * {@code pr-test-run}'s {@code args} param) are preserved as
     * {@code List<String>}; numbers/booleans are unboxed to native Java types
     * so the executor's {@code optInt}/{@code stringList} helpers can consume
     * them without further parsing.
     */
    static Map<String, Object> extractArgs(JsonNode root) {
        Map<String, Object> out = new HashMap<>();
        if (root == null || root.isMissingNode() || root.isNull() || !root.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            out.put(entry.getKey(), jsonToJava(entry.getValue()));
        }
        return out;
    }

    private static Object jsonToJava(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(jsonToJava(child));
            }
            return list;
        }
        // Objects: keep as JSON string so the executor's arg validation can fail loud
        // rather than us silently passing a Map that no executor branch handles.
        return node.toString();
    }

}
