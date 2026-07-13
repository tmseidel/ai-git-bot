package org.remus.giteabot.prworkflow.i18n;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight chat-and-dispatch loop for the {@link I18nCoverageAgent}.
 *
 * <p>Mirrors {@code ReadmeSyncAgentRunner} one-to-one — native function calling
 * when the provider supports it, transparent legacy-envelope fallback
 * otherwise — but dispatches {@code i18n-write} / {@code i18n-delete} calls to
 * the {@link I18nToolExecutor} against the real repository checkout.</p>
 */
@Slf4j
public final class I18nCoverageAgentRunner {

    private final AiClient aiClient;
    private final I18nToolExecutor toolExecutor;
    private final I18nCoverageToolContext toolContext;
    private final List<ToolDescriptor> toolDescriptors;
    private final String systemPrompt;
    private final int maxRounds;
    private final Integer maxTokens;
    private final String agentLabel;
    private final Map<String, List<String>> argOrderByTool;
    private final AiResponseParser legacyEnvelopeParser = new AiResponseParser();

    public I18nCoverageAgentRunner(AiClient aiClient,
                                   I18nToolExecutor toolExecutor,
                                   I18nCoverageToolContext toolContext,
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
        this.agentLabel = agentLabel == null ? "i18n-coverage" : agentLabel;
        this.argOrderByTool = buildArgOrderIndex(this.toolDescriptors);
    }

    /** One executed tool call with the textual result returned to the model. */
    public record ToolInvocation(String toolName, Map<String, Object> args, String result) { }

    /**
     * Result of one {@link #run(String)} call.
     *
     * @param lastAssistantText final assistant text
     * @param toolInvocations   every tool that was dispatched, in order
     * @param rounds            number of round-trips consumed
     * @param budgetExhausted   {@code true} when the loop hit {@link #maxRounds}
     *                          without a terminal text turn
     */
    public record Result(String lastAssistantText,
                         List<ToolInvocation> toolInvocations,
                         int rounds,
                         boolean budgetExhausted) {
        public Result {
            toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        }
    }

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
            log.debug("[{}] round {}/{}: assistantTextLen={} toolCalls={}",
                    agentLabel, round, maxRounds, lastAssistantText.length(), turn.toolCalls().size());

            // -------- LEGACY mode --------
            if (mode != ToolingMode.NATIVE) {
                ImplementationPlan plan = legacyEnvelopeParser.parseAiResponse(lastAssistantText);
                List<ImplementationPlan.ToolRequest> requests = plan == null
                        ? List.of() : plan.getEffectiveToolRequests();
                if (requests.isEmpty()) {
                    return new Result(lastAssistantText, invocations, round, false);
                }
                if (currentMessage != null && !currentMessage.isEmpty()) {
                    history.add(AiMessage.builder().role("user").content(currentMessage).build());
                }
                history.add(AiMessage.builder().role("assistant").content(lastAssistantText).build());

                StringBuilder feedback = new StringBuilder("## Tool Execution Results\n\n");
                for (ImplementationPlan.ToolRequest req : requests) {
                    Map<String, Object> mapped = positionalToNamedArgs(req.getTool(), req.getArgs());
                    String result = toolExecutor.execute(req.getTool(), mapped, toolContext);
                    invocations.add(new ToolInvocation(req.getTool(), mapped, result));
                    feedback.append("### ").append(req.getTool()).append("\n")
                            .append(result == null ? "(no output)" : result).append("\n\n");
                }
                currentMessage = feedback.toString();
                continue;
            }

            // -------- NATIVE mode --------
            if (!turn.hasToolCalls()) {
                return new Result(lastAssistantText, invocations, round, false);
            }
            if (currentMessage != null && !currentMessage.isEmpty()) {
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
            }
            history.add(AiMessage.builder()
                    .role("assistant")
                    .content(lastAssistantText)
                    .toolCalls(turn.toolCalls())
                    .build());

            for (ToolCall call : turn.toolCalls()) {
                Map<String, Object> mapped = extractArgs(call.args());
                String result = toolExecutor.execute(call.name(), mapped, toolContext);
                invocations.add(new ToolInvocation(call.name(), mapped, result));
                history.add(AiMessage.builder()
                        .role("tool")
                        .toolCallId(call.id())
                        .toolResult(result)
                        .build());
            }
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

    private static Map<String, List<String>> buildArgOrderIndex(List<ToolDescriptor> descriptors) {
        Map<String, List<String>> out = new HashMap<>();
        for (ToolDescriptor d : descriptors) {
            JsonNode schema = d.jsonSchema();
            if (schema == null) {
                continue;
            }
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                out.put(d.name(), List.of());
                continue;
            }
            List<String> ordered = new ArrayList<>();
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode r : required) {
                    String n = r.isString() ? r.asString() : null;
                    if (n != null && properties.get(n) != null && !ordered.contains(n)) {
                        ordered.add(n);
                    }
                }
            }
            for (Map.Entry<String, JsonNode> p : properties.properties()) {
                if (!ordered.contains(p.getKey())) {
                    ordered.add(p.getKey());
                }
            }
            out.put(d.name(), List.copyOf(ordered));
        }
        return out;
    }

    Map<String, Object> positionalToNamedArgs(String toolName, List<String> positional) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> order = argOrderByTool.getOrDefault(toolName, List.of());
        if (positional == null || positional.isEmpty() || order.isEmpty()) {
            return out;
        }
        int n = Math.min(positional.size(), order.size());
        for (int i = 0; i < n; i++) {
            out.put(order.get(i), positional.get(i));
        }
        return out;
    }

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
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(jsonToJava(child));
            }
            return list;
        }
        return node.toString();
    }
}
