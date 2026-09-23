package org.remus.giteabot.ai.ollama;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.StreamingLineReader;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.ToolNameSanitizer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
        boolean useNativeTools = supportsNativeTools() && tools != null && !tools.isEmpty();
        String effectivePrompt = useNativeTools ? systemPrompt : resolvePrompt(systemPrompt);
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : getModel();
        int effectiveMaxTokens = (maxTokensOverride != null && maxTokensOverride > 0)
                ? maxTokensOverride : getMaxTokens();

        List<AiMessage> fullHistory = new ArrayList<>(conversationHistory);
        if (!useNativeTools || (newUserMessage != null && !newUserMessage.isBlank())) {
            fullHistory.add(AiMessage.builder().role("user")
                    .content(newUserMessage == null ? "" : newUserMessage).build());
        }

        List<OllamaRequest.Message> messages = buildMessages(effectivePrompt, fullHistory);
        List<OllamaRequest.Tool> toolPayloads = useNativeTools ? tools.stream()
                .map(this::toToolPayload)
                .toList() : List.of();

        OllamaRequest request = OllamaRequest.builder()
                .model(effectiveModel)
                .messages(messages)
                .stream(true)
                .options(OllamaRequest.Options.builder().numPredict(effectiveMaxTokens).build())
                .tools(useNativeTools ? toolPayloads : null)
                .format(!useNativeTools && shouldUseJsonMode(effectivePrompt) ? "json" : null)
                .build();

        log.info("Ollama chat turn request: model={}, tools={}, history={}",
                effectiveModel, toolPayloads.size(), messages.size());

        OllamaResponse response = executeRequest(request);
        return interpret(request, response);
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
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

    private ChatTurn interpret(OllamaRequest request, OllamaResponse response) {
        if (response == null || response.getMessage() == null) {
            log.warn("Empty response from Ollama tool-call request");
            return new ChatTurn("", List.of(), StopReason.OTHER, 0L, 0L);
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
        StopReason reason = response.isDone()
                ? mapStopReason(response.getDoneReason(), !calls.isEmpty()) : StopReason.OTHER;
        long inputTokens = 0L;
        long outputTokens = 0L;
        if (response.getPromptEvalCount() != null && response.getEvalCount() != null) {
            inputTokens = response.getPromptEvalCount();
            outputTokens = response.getEvalCount();
            log.info("Ollama chat-with-tools: {} prompt tokens, {} eval tokens, {} tool_call(s)",
                    inputTokens, outputTokens, calls.size());
            reportUsage(inputTokens, outputTokens, 0L, 0L, request, response);
        }
        return new ChatTurn(text, calls, reason, inputTokens, outputTokens);
    }

    private StopReason mapStopReason(String doneReason, boolean hasToolCalls) {
        if (doneReason == null) {
            return StopReason.OTHER;
        }
        return switch (doneReason) {
            case "stop", "end_turn" -> hasToolCalls ? StopReason.TOOL_USE : StopReason.END_TURN;
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
                .stream(true)
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
        return extractText(request, response, context);
    }

    private OllamaResponse executeRequest(OllamaRequest request) {
        // Stream the NDJSON chunks and reassemble them into a single response.
        // Each line is a complete OllamaResponse-shaped JSON object. Content and
        // tool_calls are accumulated across chunks; done_reason / prompt_eval_count /
        // eval_count come from the final (done:true) chunk, which is the only
        // one that carries the usage counters — so audit/usage totals are unchanged
        // from the non-streamed path. If the stream ends without a done chunk
        // (e.g. provider/proxy truncation), the last chunk is used as the
        // metadata fallback, so model metadata is not lost.
        StringBuilder content = new StringBuilder();
        List<OllamaResponse.ToolCallResponse> toolCalls = new ArrayList<>();
        OllamaResponse[] finalRef = new OllamaResponse[1];
        OllamaResponse[] lastRef = new OllamaResponse[1];

        StreamingLineReader.streamLines(restClient, "/api/chat", request, line -> {
            OllamaResponse chunk;
            try {
                chunk = jackson.readValue(line, OllamaResponse.class);
            } catch (JacksonException e) {
                // A malformed NDJSON line fails the request (do not silently skip).
                // Surfaced as an I/O error so AgentLoop.callAiWithRetry retries it
                // like any transient failure.
                throw new ResourceAccessException(
                        "Malformed Ollama stream line: " + e.getMessage(), new IOException(e));
            }
            lastRef[0] = chunk;
            if (chunk.getMessage() != null && chunk.getMessage().getContent() != null) {
                content.append(chunk.getMessage().getContent());
            }
            if (chunk.getMessage() != null && chunk.getMessage().getToolCalls() != null) {
                toolCalls.addAll(chunk.getMessage().getToolCalls());
            }
            if (chunk.isDone()) {
                finalRef[0] = chunk;
            }
        });

        // Keep an empty transport response distinct from a completed model turn.
        // The text API retains its legacy fallback; native callers receive OTHER.
        OllamaResponse source = finalRef[0] != null ? finalRef[0] : lastRef[0];
        if (source == null) {
            return null;
        }

        OllamaResponse merged = new OllamaResponse();
        String mergedContent = !content.isEmpty()
                ? content.toString()
                : (source.getMessage() != null && source.getMessage().getContent() != null
                    ? source.getMessage().getContent()
                    : "");
        OllamaResponse.Message message = new OllamaResponse.Message();
        message.setRole("assistant");
        message.setContent(mergedContent);
        merged.setDone(finalRef[0] != null);
        merged.setDoneReason(source.getDoneReason());
        merged.setPromptEvalCount(source.getPromptEvalCount());
        merged.setEvalCount(source.getEvalCount());
        merged.setTotalDuration(source.getTotalDuration());
        if (source.getModel() != null) {
            merged.setModel(source.getModel());
        }
        message.setToolCalls(toolCalls);
        merged.setMessage(message);
        return merged;
    }

    private String extractText(OllamaRequest request, OllamaResponse response, String context) {
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
            reportUsage(response.getPromptEvalCount(), response.getEvalCount(), 0L, 0L, request, response);
        }

        return result;
    }
}
