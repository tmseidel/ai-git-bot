package org.remus.giteabot.ai.openrouter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.ToolNameSanitizer;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** OpenRouter wire format; routing and opaque reasoning never enter OpenAI DTOs. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenRouterRequest(String model, @JsonProperty("max_tokens") int maxTokens,
                         List<Message> messages, List<Tool> tools) {

    static OpenRouterRequest create(String model, int maxTokens, String systemPrompt,
                                    List<AiMessage> history, List<ToolDescriptor> tools) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt, null, null, null));
        for (AiMessage message : history) {
            boolean tool = "tool".equals(message.getRole());
            var calls = tool || message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null
                    : message.getToolCalls().stream().map(call -> new ToolCall(call.id(), "function",
                    new FunctionCall(ToolNameSanitizer.sanitize(call.name()),
                            call.args() == null ? "{}" : call.args().toString()))).toList();
            messages.add(new Message(message.getRole(),
                    tool && message.getToolResult() != null ? message.getToolResult() : message.getContent(),
                    calls, tool ? message.getToolCallId() : null, tool ? null : message.getReasoningDetails()));
        }
        var payloads = tools.isEmpty() ? null : tools.stream().map(tool -> new Tool("function",
                new Function(ToolNameSanitizer.sanitize(tool.name()), tool.description(), tool.jsonSchema()))).toList();
        return new OpenRouterRequest(model, maxTokens, messages, payloads);
    }

    /** Core-adapter defaults preserve parameters and disable provider fallbacks. */
    @JsonProperty("provider")
    public ProviderPreferences provider() { return new ProviderPreferences(true, false, "deny", false); }

    /** Per-request opt-outs; enforced account-level plugins must be disabled by the operator. */
    @JsonProperty("plugins")
    public List<Plugin> plugins() {
        return List.of("web", "file-parser", "response-healing", "context-compression", "pareto-router")
                .stream().map(id -> new Plugin(id, false)).toList();
    }

    record ProviderPreferences(@JsonProperty("require_parameters") boolean requireParameters,
                               @JsonProperty("allow_fallbacks") boolean allowFallbacks,
                               @JsonProperty("data_collection") String dataCollection, boolean zdr) {}
    record Plugin(String id, boolean enabled) {}
    record Tool(String type, Function function) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Function(String name, String description, JsonNode parameters) {}
    record ToolCall(String id, String type, FunctionCall function) {}
    record FunctionCall(String name, String arguments) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(String role, String content, @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                   @JsonProperty("tool_call_id") String toolCallId,
                   @JsonProperty("reasoning_details") List<JsonNode> reasoningDetails) {
        @Override public String toString() { return "Message[role=" + role + "]"; }
    }
}
