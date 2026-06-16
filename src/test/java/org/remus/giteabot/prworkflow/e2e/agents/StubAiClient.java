package org.remus.giteabot.prworkflow.e2e.agents;

import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic {@link AiClient} for the M4 wave 2 agent tests. The test
 * primes a queue of canned responses (text or tool-calls); the agent then
 * runs against this stub instead of a real provider.
 *
 * <p>Every {@link #chatWithTools} or {@link #chat} call records the inbound
 * messages so the test can assert on the conversation shape (e.g. that the
 * author was given the plan as a user message, that the runner saw tool
 * results in its history, …).</p>
 */
public final class StubAiClient implements AiClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<ChatTurn> scriptedTurns = new ArrayDeque<>();
    private final List<Invocation> invocations = new ArrayList<>();
    private final boolean nativeTools;

    public StubAiClient(boolean nativeTools) {
        this.nativeTools = nativeTools;
    }

    public StubAiClient withTextTurn(String text) {
        scriptedTurns.add(ChatTurn.text(text));
        return this;
    }

    public StubAiClient withToolCalls(ToolCallSpec... calls) {
        List<ToolCall> tcalls = new ArrayList<>(calls.length);
        for (ToolCallSpec spec : calls) {
            tcalls.add(new ToolCall(
                    spec.id() == null ? "call_" + UUID.randomUUID() : spec.id(),
                    spec.name(),
                    toJson(spec.args()),
                    Map.of()));
        }
        scriptedTurns.add(new ChatTurn("", tcalls, StopReason.TOOL_USE, 0L, 0L));
        return this;
    }

    public List<Invocation> invocations() {
        return invocations;
    }

    // ----------------------------------------------------------------- AiClient impl

    @Override public boolean supportsNativeTools() { return nativeTools; }

    @Override
    public ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                  String newUserMessage,
                                  List<ToolDescriptor> tools,
                                  String systemPrompt,
                                  String modelOverride,
                                  Integer maxTokensOverride) {
        invocations.add(new Invocation(conversationHistory, newUserMessage, tools, systemPrompt));
        return nextOrEmpty();
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride) {
        return chat(conversationHistory, newUserMessage, systemPrompt, modelOverride, null);
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride, Integer maxTokensOverride) {
        invocations.add(new Invocation(conversationHistory, newUserMessage, List.of(), systemPrompt));
        return nextOrEmpty().assistantText();
    }

    @Override public String reviewDiff(String t, String b, String d) { return ""; }
    @Override public String reviewDiff(String t, String b, String d, String s, String m) { return ""; }

    private ChatTurn nextOrEmpty() {
        ChatTurn next = scriptedTurns.poll();
        return next == null ? ChatTurn.text("") : next;
    }

    private static JsonNode toJson(Map<String, Object> args) {
        ObjectNode node = JSON.createObjectNode();
        if (args == null) return node;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            switch (v) {
                case null -> node.putNull(e.getKey());
                case Number n -> node.put(e.getKey(), n.longValue());
                case Boolean b -> node.put(e.getKey(), b);
                case List<?> list -> {
                    var arr = node.putArray(e.getKey());
                    for (Object item : list) arr.add(String.valueOf(item));
                }
                default -> node.put(e.getKey(), String.valueOf(v));
            }
        }
        return node;
    }

    public record ToolCallSpec(String id, String name, Map<String, Object> args) {
        public static ToolCallSpec of(String name, Map<String, Object> args) {
            return new ToolCallSpec(null, name, args);
        }
    }

    public record Invocation(List<AiMessage> history, String newUserMessage,
                             List<ToolDescriptor> tools, String systemPrompt) { }
}
