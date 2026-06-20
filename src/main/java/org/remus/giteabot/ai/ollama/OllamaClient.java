package org.remus.giteabot.ai.ollama;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiClientDelegateSupport;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.ToolNameSanitizer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI client implementation for Ollama (local LLM inference).
 *
 * <p>Step 6: implements native function calling via Ollama's
 * {@code tools[]} field on {@code /api/chat} (the schema mirrors OpenAI's
 * Chat-Completions tools API). Whether the bot opts into native tools is
 * controlled by the per-integration {@code use_legacy_tool_calling} flag —
 * useful because tool-call support varies between Ollama models. When
 * disabled, the legacy text path remains unchanged, including JSON-mode
 * detection on the system prompt.</p>
 */
@Slf4j
public class OllamaClient extends AbstractAiClient {

    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final ObjectMapper jackson = AgentJackson.mapper();

    public OllamaClient(RestClient restClient, String model, int maxTokens, boolean nativeToolsEnabled) {
        super(model, maxTokens);
        this.restClient = restClient;
        this.nativeToolsEnabled = nativeToolsEnabled;
    }

    @Override
    public boolean supportsNativeTools() {
        return nativeToolsEnabled;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        List<OllamaRequest.Message> messages = new ArrayList<>();
        messages.add(OllamaRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(OllamaRequest.Message.builder().role("user").content(userMessage).build());

        boolean useJsonMode = shouldUseJsonMode(systemPrompt);
        return doRequest(effectiveModel, messages, maxTokens, "review", useJsonMode, null);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<OllamaRequest.Message> messages = buildMessages(systemPrompt, conversationMessages);
        boolean useJsonMode = shouldUseJsonMode(systemPrompt);
        return doRequest(effectiveModel, messages, maxTokens, "chat", useJsonMode, null);
    }

    @Override
    public ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                  String newUserMessage,
                                  List<ToolDescriptor> tools,
                                  String systemPrompt,
                                  String modelOverride,
                                  Integer maxTokensOverride) {
        if (!supportsNativeTools() || tools == null || tools.isEmpty()) {
            return AiClientDelegateSupport.delegateToChat(this, conversationHistory,
                    newUserMessage, systemPrompt, modelOverride, maxTokensOverride);
        }
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : getModel();
        int effectiveMaxTokens = (maxTokensOverride != null && maxTokensOverride > 0)
                ? maxTokensOverride : getMaxTokens();

        List<AiMessage> fullHistory = new ArrayList<>(conversationHistory);
        if (newUserMessage != null && !newUserMessage.isBlank()) {
            fullHistory.add(AiMessage.builder().role("user").content(newUserMessage).build());
        }

        List<OllamaRequest.Message> messages = buildMessages(systemPrompt, fullHistory);
        List<OllamaRequest.Tool> toolPayloads = tools.stream()
                .map(this::toToolPayload)
                .toList();

        OllamaRequest request = OllamaRequest.builder()
                .model(effectiveModel)
                .messages(messages)
                .stream(false)
                .options(OllamaRequest.Options.builder().numPredict(effectiveMaxTokens).build())
                .tools(toolPayloads)
                .build();

        log.info("Ollama chat-with-tools request: model={}, tools={}, history={}",
                effectiveModel, toolPayloads.size(), messages.size());

        OllamaResponse response = executeRequest(request);
        return interpret(response);
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("too long") || normalized.contains("context length");
    }

    private List<OllamaRequest.Message> buildMessages(String systemPrompt,
                                                      List<AiMessage> conversationMessages) {
        List<OllamaRequest.Message> messages = new ArrayList<>();
        messages.add(OllamaRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            if ("tool".equals(m.getRole())) {
                messages.add(OllamaRequest.Message.builder()
                        .role("tool")
                        .toolCallId(m.getToolCallId())
                        .content(m.getToolResult() != null ? m.getToolResult() : m.getContent())
                        .build());
                continue;
            }
            OllamaRequest.Message.MessageBuilder builder = OllamaRequest.Message.builder()
                    .role(m.getRole())
                    .content(m.getContent());
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                builder.toolCalls(m.getToolCalls().stream()
                        .map(this::toToolCallPayload)
                        .toList());
            }
            messages.add(builder.build());
        }
        return messages;
    }

    private OllamaRequest.Tool toToolPayload(ToolDescriptor descriptor) {
        Object schema;
        try {
            schema = descriptor.jsonSchema() == null
                    ? Map.of("type", "object")
                    : jackson.convertValue(descriptor.jsonSchema(), Map.class);
        } catch (RuntimeException e) {
            schema = Map.of("type", "object");
        }
        return OllamaRequest.Tool.builder()
                .type("function")
                .function(OllamaRequest.Function.builder()
                        .name(ToolNameSanitizer.sanitize(descriptor.name()))
                        .description(descriptor.description())
                        .parameters(schema)
                        .build())
                .build();
    }

    private OllamaRequest.ToolCallPayload toToolCallPayload(ToolCall call) {
        Map<String, Object> args;
        try {
            args = call.args() == null ? new LinkedHashMap<>()
                    : jackson.convertValue(call.args(), Map.class);
        } catch (RuntimeException e) {
            args = new LinkedHashMap<>();
        }
        return OllamaRequest.ToolCallPayload.builder()
                .function(OllamaRequest.FunctionCall.builder()
                        .name(ToolNameSanitizer.sanitize(call.name()))
                        .arguments(args)
                        .build())
                .build();
    }

    private ChatTurn interpret(OllamaResponse response) {
        if (response == null || response.getMessage() == null) {
            log.warn("Empty response from Ollama tool-call request");
            return ChatTurn.text("Unable to generate response - empty reply from AI.");
        }
        String text = response.getMessage().getContent() != null ? response.getMessage().getContent() : "";

        List<ToolCall> calls = new ArrayList<>();
        if (response.getMessage().getToolCalls() != null) {
            int idx = 0;
            for (OllamaResponse.ToolCallResponse tcr : response.getMessage().getToolCalls()) {
                if (tcr.getFunction() == null || tcr.getFunction().getName() == null) {
                    continue;
                }
                Map<String, Object> raw = tcr.getFunction().getArguments() != null
                        ? tcr.getFunction().getArguments() : new LinkedHashMap<>();
                JsonNode args = jackson.valueToTree(raw);
                // Ollama doesn't supply call ids; synthesise one for round-trip correlation.
                // Translate any sanitised colons back so the rest of the system sees the
                // canonical name (e.g. MCP tools like "mcp:github:issue_read").
                String originalName = ToolNameSanitizer.desanitize(tcr.getFunction().getName());
                calls.add(new ToolCall(originalName + ":" + (idx++), originalName, args));
            }
        }
        StopReason reason = mapStopReason(response.getDoneReason(), !calls.isEmpty());
        long inputTokens = 0L;
        long outputTokens = 0L;
        if (response.getPromptEvalCount() != null && response.getEvalCount() != null) {
            inputTokens = response.getPromptEvalCount();
            outputTokens = response.getEvalCount();
            log.info("Ollama chat-with-tools: {} prompt tokens, {} eval tokens, {} tool_call(s)",
                    inputTokens, outputTokens, calls.size());
            reportUsage(inputTokens, outputTokens);
        }
        return new ChatTurn(text, calls, reason, inputTokens, outputTokens);
    }

    private StopReason mapStopReason(String doneReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return StopReason.TOOL_USE;
        }
        if (doneReason == null) {
            return StopReason.END_TURN;
        }
        return switch (doneReason) {
            case "stop", "end_turn" -> StopReason.END_TURN;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }

    private boolean shouldUseJsonMode(String systemPrompt) {
        if (systemPrompt == null) {
            return false;
        }
        String lower = systemPrompt.toLowerCase(Locale.ROOT);
        return lower.contains("respond with a json")
                || lower.contains("output json")
                || lower.contains("output format") && lower.contains("json")
                || lower.contains("```json");
    }

    private String doRequest(String model, List<OllamaRequest.Message> messages,
                             int maxTokens, String context, boolean useJsonMode,
                             List<OllamaRequest.Tool> tools) {
        OllamaRequest.OllamaRequestBuilder requestBuilder = OllamaRequest.builder()
                .model(model)
                .messages(messages)
                .stream(false)
                .options(OllamaRequest.Options.builder()
                        .numPredict(maxTokens)
                        .build());

        if (useJsonMode) {
            requestBuilder.format("json");
            log.info("Ollama {} request: JSON mode enabled for structured output", context);
        }
        if (tools != null && !tools.isEmpty()) {
            requestBuilder.tools(tools);
        }

        OllamaRequest request = requestBuilder.build();

        OllamaResponse response = executeRequest(request);
        return extractText(response, context);
    }

    private OllamaResponse executeRequest(OllamaRequest request) {
        return restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaResponse.class);
    }

    private String extractText(OllamaResponse response, String context) {
        if (response == null || response.getMessage() == null
                || response.getMessage().getContent() == null) {
            log.warn("Empty response from Ollama API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getMessage().getContent();

        if (response.getPromptEvalCount() != null && response.getEvalCount() != null) {
            log.info("Ollama {} response: {} prompt tokens, {} eval tokens",
                    context,
                    response.getPromptEvalCount(),
                    response.getEvalCount());
            reportUsage(response.getPromptEvalCount(), response.getEvalCount());
        }

        return result;
    }
}


