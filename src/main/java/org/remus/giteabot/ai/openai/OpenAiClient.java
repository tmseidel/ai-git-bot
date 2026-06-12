package org.remus.giteabot.ai.openai;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiClientDelegateSupport;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.ToolNameSanitizer;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for OpenAI-compatible APIs.
 * Uses the Chat Completions endpoint with system prompt as a role message.
 *
 * <p>Step 6: implements native function calling via {@code tools[]} +
 * {@code tool_calls}. Activation is gated by the per-integration
 * {@code use_legacy_tool_calling} switch (see {@link org.remus.giteabot.admin.AiIntegration}).</p>
 */
@Slf4j
public class OpenAiClient extends AbstractAiClient {

    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final ObjectMapper jackson = AgentJackson.mapper();

    public OpenAiClient(RestClient restClient, String model, int maxTokens,
                        int maxDiffCharsPerChunk, int maxDiffChunks,
                        int retryTruncatedChunkChars) {
        this(restClient, model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks,
                retryTruncatedChunkChars, true);
    }

    public OpenAiClient(RestClient restClient, String model, int maxTokens,
                        int maxDiffCharsPerChunk, int maxDiffChunks,
                        int retryTruncatedChunkChars, boolean nativeToolsEnabled) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
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
        List<OpenAiRequest.Message> messages = new ArrayList<>();
        messages.add(OpenAiRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(OpenAiRequest.Message.builder().role("user").content(userMessage).build());

        return doRequest(effectiveModel, maxTokens, messages, null, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<OpenAiRequest.Message> messages = buildMessages(systemPrompt, conversationMessages);
        return doRequest(effectiveModel, maxTokens, messages, null, "chat");
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

        List<OpenAiRequest.Message> messages = buildMessages(systemPrompt, fullHistory);
        List<OpenAiRequest.Tool> toolPayloads = tools.stream()
                .map(this::toToolPayload)
                .toList();

        OpenAiRequest request = OpenAiRequest.builder()
                .model(effectiveModel)
                .maxTokens(effectiveMaxTokens)
                .messages(messages)
                .tools(toolPayloads)
                .build();

        log.info("OpenAI chat-with-tools request: model={}, tools={}, history={}",
                effectiveModel, toolPayloads.size(), messages.size());

        OpenAiResponse response = executeRequest(request);
        return interpret(response);
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("maximum context length")
                || normalized.contains("too many tokens")
                || normalized.contains("max_completion_tokens");
    }

    private List<OpenAiRequest.Message> buildMessages(String systemPrompt,
                                                      List<AiMessage> conversationMessages) {
        List<OpenAiRequest.Message> messages = new ArrayList<>();
        messages.add(OpenAiRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            if ("tool".equals(m.getRole())) {
                messages.add(OpenAiRequest.Message.builder()
                        .role("tool")
                        .toolCallId(m.getToolCallId())
                        .content(m.getToolResult() != null ? m.getToolResult() : m.getContent())
                        .build());
                continue;
            }
            OpenAiRequest.Message.MessageBuilder builder = OpenAiRequest.Message.builder()
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

    private OpenAiRequest.Tool toToolPayload(ToolDescriptor descriptor) {
        return OpenAiRequest.Tool.builder()
                .type("function")
                .function(OpenAiRequest.Function.builder()
                        .name(ToolNameSanitizer.sanitize(descriptor.name()))
                        .description(descriptor.description())
                        .parameters(descriptor.jsonSchema())
                        .build())
                .build();
    }

    private OpenAiRequest.ToolCallPayload toToolCallPayload(ToolCall call) {
        String args;
        try {
            args = call.args() == null ? "{}" : jackson.writeValueAsString(call.args());
        } catch (Exception e) {
            args = "{}";
        }
        return OpenAiRequest.ToolCallPayload.builder()
                .id(call.id())
                .type("function")
                .function(OpenAiRequest.FunctionCall.builder()
                        .name(ToolNameSanitizer.sanitize(call.name()))
                        .arguments(args)
                        .build())
                .build();
    }

    private ChatTurn interpret(OpenAiResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("Empty response from OpenAI tool-call request");
            return ChatTurn.text("Unable to generate response - empty reply from AI.");
        }
        OpenAiResponse.Choice choice = response.getChoices().getFirst();
        OpenAiResponse.Message message = choice.getMessage();
        String text = message != null && message.getContent() != null ? message.getContent() : "";
        StopReason reason = mapStopReason(choice.getFinishReason());

        List<ToolCall> calls = new ArrayList<>();
        if (message != null && message.getToolCalls() != null) {
            for (OpenAiResponse.ToolCallResponse tcr : message.getToolCalls()) {
                if (tcr.getFunction() == null || tcr.getFunction().getName() == null) {
                    continue;
                }
                JsonNode args;
                try {
                    String raw = tcr.getFunction().getArguments();
                    args = (raw == null || raw.isBlank())
                            ? jackson.createObjectNode()
                            : jackson.readTree(raw);
                } catch (Exception e) {
                    log.warn("Failed to parse tool-call arguments for {}: {}",
                            tcr.getFunction().getName(), e.getMessage());
                    args = jackson.createObjectNode();
                }
                calls.add(new ToolCall(tcr.getId(),
                        ToolNameSanitizer.desanitize(tcr.getFunction().getName()), args));
            }
            if (!calls.isEmpty()) {
                reason = StopReason.TOOL_USE;
            }
        }
        if (response.getUsage() != null) {
            log.info("OpenAI chat-with-tools: {} prompt tokens, {} completion tokens, {} tool_call(s)",
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens(),
                    calls.size());
            reportUsage(response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }
        return new ChatTurn(text, calls, reason);
    }

    private StopReason mapStopReason(String finishReason) {
        if (finishReason == null) {
            return StopReason.OTHER;
        }
        return switch (finishReason) {
            case "stop" -> StopReason.END_TURN;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }

    private String doRequest(String model, int maxTokens,
                             List<OpenAiRequest.Message> messages,
                             List<OpenAiRequest.Tool> tools, String context) {
        OpenAiRequest request = OpenAiRequest.builder()
                .model(model)
                .maxTokens(maxTokens)
                .messages(messages)
                .tools(tools)
                .build();

        OpenAiResponse response = executeRequest(request);

        return extractText(response, context);
    }

    private OpenAiResponse executeRequest(OpenAiRequest request) {
        return restClient.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(OpenAiResponse.class);
    }

    private String extractText(OpenAiResponse response, String context) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("Empty response from OpenAI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        OpenAiResponse.Choice firstChoice = response.getChoices().getFirst();
        if (firstChoice == null
                || firstChoice.getMessage() == null
                || firstChoice.getMessage().getContent() == null) {
            log.warn("Empty response from OpenAI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = firstChoice.getMessage().getContent();
        if (response.getUsage() != null) {
            log.info("OpenAI {} response: {} prompt tokens, {} completion tokens",
                    context,
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
            reportUsage(response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }

        return result;
    }
}


