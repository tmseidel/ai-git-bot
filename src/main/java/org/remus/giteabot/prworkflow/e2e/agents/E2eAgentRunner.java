package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.HistoryCompactor;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolContext;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final HistoryCompactor compactor;
    /** Tool name → ordered argument-property names (required first, then optional,
     *  schema-insertion order) — used to zip the legacy envelope's positional
     *  `args` array back to a named map for {@link PrWorkflowToolExecutor}. */
    private final Map<String, List<String>> argOrderByTool;
    /** Tool name → property name → JSON schema type ("string", "array", "object", ...).
     *  Used so legacy-envelope positional args that are arrays/objects can be
     *  JSON-decoded back to {@link List}/{@link Map} instances (the upstream
     *  {@link AiResponseParser} serialises any non-string positional element
     *  to a JSON string). */
    private final Map<String, Map<String, String>> argTypeByTool;
    private final AiResponseParser legacyEnvelopeParser = new AiResponseParser();
    private static final ObjectMapper JSON = new ObjectMapper();

    public E2eAgentRunner(AiClient aiClient,
                          PrWorkflowToolExecutor toolExecutor,
                          PrWorkflowToolContext toolContext,
                          List<ToolDescriptor> toolDescriptors,
                          String systemPrompt,
                          int maxRounds,
                          Integer maxTokens,
                          int maxHistoryChars,
                          String agentLabel) {
        this.aiClient = aiClient;
        this.toolExecutor = toolExecutor;
        this.toolContext = toolContext;
        this.toolDescriptors = toolDescriptors == null ? List.of() : List.copyOf(toolDescriptors);
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.maxTokens = maxTokens;
        this.agentLabel = agentLabel == null ? "e2e-agent" : agentLabel;
        this.compactor = new HistoryCompactor(maxHistoryChars, 4);
        this.argOrderByTool = buildArgOrderIndex(this.toolDescriptors);
        this.argTypeByTool = buildArgTypeIndex(this.toolDescriptors);
    }

    /**
     * Builds an ordered list of argument-property names per tool, derived from
     * each descriptor's JSON schema. Required props come first (in declared
     * schema order), then any remaining optional props (also in declared
     * order). Mirrors the convention used by
     * {@link org.remus.giteabot.agent.tools.ToolCatalog#legacyUsageExample(String)}
     * so the positional shape the model produces in the legacy envelope can be
     * reversed back to a named {@code Map<String, Object>}.
     */
    private static Map<String, List<String>> buildArgOrderIndex(List<ToolDescriptor> descriptors) {
        Map<String, List<String>> out = new HashMap<>();
        for (ToolDescriptor d : descriptors) {
            JsonNode schema = d.jsonSchema();
            if (schema == null) continue;
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

    /** Builds the tool-name → property-name → schema-type lookup. */
    private static Map<String, Map<String, String>> buildArgTypeIndex(List<ToolDescriptor> descriptors) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (ToolDescriptor d : descriptors) {
            JsonNode schema = d.jsonSchema();
            if (schema == null) continue;
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) continue;
            Map<String, String> perTool = new HashMap<>();
            for (Map.Entry<String, JsonNode> p : properties.properties()) {
                JsonNode typeNode = p.getValue() == null ? null : p.getValue().get("type");
                if (typeNode != null && typeNode.isString()) {
                    perTool.put(p.getKey(), typeNode.asString());
                }
            }
            out.put(d.name(), Map.copyOf(perTool));
        }
        return out;
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
                turn = callAiWithRetry(history, currentMessage, mode, maxTokens);
            } catch (RuntimeException e) {
                log.warn("[{}] AI call failed in round {}: {}", agentLabel, round, e.getMessage(), e);
                return new Result(lastAssistantText, invocations, round - 1, true);
            }
            lastAssistantText = turn.assistantText() == null ? "" : turn.assistantText();
            log.debug("[{}] round {}/{}: assistantTextLen={} toolCalls={} stopReason={}",
                    agentLabel, round, maxRounds,
                    lastAssistantText.length(), turn.toolCalls().size(), turn.stopReason());

            // -------- LEGACY mode: parse JSON envelope, dispatch, feed back ----
            if (mode != ToolingMode.NATIVE) {
                ImplementationPlan plan = legacyEnvelopeParser.parseAiResponse(lastAssistantText);
                List<ImplementationPlan.ToolRequest> requests = plan == null
                        ? List.of() : plan.getEffectiveToolRequests();
                if (requests.isEmpty()) {
                    // No tools requested → terminal turn (model either signalled
                    // DONE / empty runTools or produced unparseable text).
                    return new Result(lastAssistantText, invocations, round, false);
                }
                // Record assistant turn + user-side tool-result message so the
                // model can continue the dialogue in the next round.
                if (currentMessage != null && !currentMessage.isEmpty()) {
                    history.add(AiMessage.builder().role("user").content(currentMessage).build());
                }
                history.add(AiMessage.builder().role("assistant").content(lastAssistantText).build());

                StringBuilder feedback = new StringBuilder("## Tool Execution Results\n\n");
                for (ImplementationPlan.ToolRequest req : requests) {
                    Map<String, Object> args = positionalToNamedArgs(req.getTool(), req.getArgs());
                    String result = toolExecutor.execute(req.getTool(), args, toolContext);
                    invocations.add(new ToolInvocation(req.getTool(), args, result));
                    feedback.append("### `").append(req.getId() == null ? "?" : req.getId())
                            .append("` (").append(req.getTool()).append(")\n")
                            .append(result == null ? "(no output)" : result)
                            .append("\n\n");
                }
                currentMessage = feedback.toString();
                continue;
            }

            // -------- NATIVE mode (unchanged behaviour) ------------------------
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
     * Zips the legacy envelope's positional {@code args} array back to the
     * named-parameter map expected by {@link PrWorkflowToolExecutor}, using
     * the property order recorded in {@link #argOrderByTool}. Extra args
     * beyond the schema are dropped (the executor would reject them anyway);
     * missing args are simply absent from the map so the executor's own
     * required-argument validation can surface a clean error to the model.
     */
    Map<String, Object> positionalToNamedArgs(String toolName, List<String> positional) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> order = argOrderByTool.getOrDefault(toolName, List.of());
        if (positional == null || positional.isEmpty() || order.isEmpty()) {
            return out;
        }
        Map<String, String> types = argTypeByTool.getOrDefault(toolName, Map.of());
        int n = Math.min(positional.size(), order.size());
        for (int i = 0; i < n; i++) {
            String prop = order.get(i);
            String raw = positional.get(i);
            out.put(prop, coerceByType(raw, types.get(prop)));
        }
        return out;
    }

    /**
     * Coerces a stringified positional arg back to the type its schema declares.
     * {@link AiResponseParser#normalizeArgs} serialises any non-string element
     * (arrays, objects, numbers, booleans) as a JSON string; we undo that here
     * so {@link PrWorkflowToolExecutor} receives the runtime type it expects
     * (e.g. {@code List<String>} for {@code pr-test-run}'s {@code args}).
     */
    private static Object coerceByType(String raw, String schemaType) {
        if (raw == null || schemaType == null || "string".equals(schemaType)) {
            return raw;
        }
        try {
            return switch (schemaType) {
                case "array", "object" -> jsonNodeToJava(JSON.readTree(raw));
                case "integer" -> Long.parseLong(raw.trim());
                case "number"  -> Double.parseDouble(raw.trim());
                case "boolean" -> Boolean.parseBoolean(raw.trim());
                default -> raw;
            };
        } catch (Exception e) {
            // Best effort — let the executor's own validation surface a clean
            // error to the model rather than crashing the loop.
            return raw;
        }
    }

    private static Object jsonNodeToJava(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) {
            List<Object> arr = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                arr.add(jsonNodeToJava(item));
            }
            return arr;
        }
        if (node.isObject()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                obj.put(e.getKey(), jsonNodeToJava(e.getValue()));
            }
            return obj;
        }
        return node.toString();
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
     * @throws RuntimeException if the retry also fails
     */
    private ChatTurn callAiWithRetry(List<AiMessage> history, String currentMessage,
                                     ToolingMode mode, int maxTokens) {
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (mode == ToolingMode.NATIVE) {
                    return aiClient.chatWithTools(history, currentMessage, toolDescriptors,
                            systemPrompt, null, maxTokens);
                } else {
                    String text = aiClient.chat(history, currentMessage, systemPrompt, null, maxTokens);
                    return ChatTurn.text(text);
                }
            } catch (HttpClientErrorException e) {
                if (!aiClient.isPromptTooLongError(e) || attempt == maxRetries) {
                    throw e;
                }
                log.warn("[{}] AI call failed with prompt-too-long error on attempt {}/{}. "
                        + "Aggressively compacting history and retrying. Error: {}",
                        agentLabel, attempt, maxRetries, e.getMessage());
                // Aggressive compaction: keep only the last 2 compaction units
                compactor.compactAggressively(history);
            }
        }
        // Unreachable, but keeps the compiler happy
        throw new IllegalStateException("callAiWithRetry: exceeded max retries");
    }

}
