package org.remus.giteabot.ai.anthropic;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI client implementation for Anthropic's Messages API.
 *
 * <p>Step 6: implements native function/tool calling via the
 * {@code tools[]} request field and {@code tool_use}/{@code tool_result}
 * content blocks. Activation is gated by the per-integration
 * {@code use_legacy_tool_calling} switch (see
 * {@link org.remus.giteabot.admin.AiIntegration}). When native tools are
 * disabled, the client behaves exactly like the pre-Step-6 implementation:
 * plain text turns and JSON-in-prompt tool requests handled by the agent
 * parsers.</p>
 */
@Slf4j
public class AnthropicAiClient extends AbstractAiClient {

    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final ObjectMapper jackson = AgentJackson.mapper();

    public AnthropicAiClient(RestClient restClient, String model, int maxTokens,
                             int maxDiffCharsPerChunk, int maxDiffChunks,
                             int retryTruncatedChunkChars) {
        this(restClient, model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks,
                retryTruncatedChunkChars, true);
    }

    public AnthropicAiClient(RestClient restClient, String model, int maxTokens,
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

    // ---------------------------------------------------------------------
    // Legacy text-only paths (unchanged behaviour from pre-Step-6).
    // ---------------------------------------------------------------------

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ))
                .build();

        AnthropicResponse response = executeRequest(request);
        return extractText(response, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages) {
        List<AnthropicRequest.Message> anthropicMessages = messages.stream()
                .map(this::toLegacyMessage)
                .toList();

        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(anthropicMessages)
                .build();

        AnthropicResponse response = executeRequest(request);
        return extractText(response, "chat");
    }

    private AnthropicRequest.Message toLegacyMessage(AiMessage m) {
        // Tool/assistant turns with tool_calls cannot survive in plain-text
        // mode, so we drop the tool envelope and just keep visible content.
        String role = "tool".equals(m.getRole()) ? "user" : m.getRole();
        String content = m.getContent();
        if ("tool".equals(m.getRole())) {
            content = m.getToolResult() != null ? m.getToolResult() : content;
        }
        return AnthropicRequest.Message.builder()
                .role(role)
                .content(content == null ? "" : content)
                .build();
    }

    // ---------------------------------------------------------------------
    // Native function calling (Step 6).
    // ---------------------------------------------------------------------

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

        List<AnthropicRequest.Message> messages = buildToolMessages(fullHistory);

        List<AnthropicRequest.Tool> toolPayloads = tools.stream()
                .map(this::toToolPayload)
                .toList();

        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(effectiveMaxTokens)
                .system(systemPrompt)
                .messages(messages)
                .tools(toolPayloads)
                .build();

        log.info("Anthropic chat-with-tools request: model={}, tools={}, history={}",
                effectiveModel, toolPayloads.size(), messages.size());

        AnthropicResponse response = executeRequest(request);
        return interpret(response);
    }

    /**
     * Build Anthropic-shaped messages for a tool-calling turn. Tool results
     * coming back from {@link AiMessage#getToolResult()} are merged into a
     * single {@code user}-role message holding {@code tool_result} blocks,
     * because the Anthropic API requires tool results to live in a
     * {@code user} turn.
     *
     * <p>Defensive sanitisation: Anthropic strictly requires every
     * {@code tool_use} block in an assistant turn to be matched 1:1 by a
     * {@code tool_result} with the same {@code tool_use_id} in the
     * immediately following user turn — and rejects {@code tool_result}
     * blocks without a {@code tool_use_id}. Replayed session history can
     * violate either rule (older messages persisted before the native flow
     * had stable ids, or a tool message dropped because the run crashed),
     * so we drop orphan blocks on both sides instead of letting the API
     * 400 the whole request.</p>
     */
    private List<AnthropicRequest.Message> buildToolMessages(List<AiMessage> history) {
        List<AnthropicRequest.Message> out = new ArrayList<>();
        List<AnthropicRequest.ContentBlock> pendingToolResults = new ArrayList<>();

        for (AiMessage m : history) {
            if ("tool".equals(m.getRole())) {
                String toolCallId = m.getToolCallId();
                if (toolCallId == null || toolCallId.isBlank()) {
                    log.warn("Dropping tool-role history message without tool_use_id "
                            + "(would be rejected by Anthropic)");
                    continue;
                }
                String resultText = m.getToolResult() != null ? m.getToolResult() : m.getContent();
                AnthropicRequest.ContentBlock block = AnthropicRequest.ContentBlock.builder()
                        .type("tool_result")
                        .toolUseId(toolCallId)
                        .content(resultText != null ? resultText : "")
                        .build();
                pendingToolResults.add(block);
                continue;
            }
            if (!pendingToolResults.isEmpty()) {
                out.add(AnthropicRequest.Message.builder()
                        .role("user")
                        .content(new ArrayList<>(pendingToolResults))
                        .build());
                pendingToolResults.clear();
            }

            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<AnthropicRequest.ContentBlock> blocks = new ArrayList<>();
                if (m.getContent() != null && !m.getContent().isBlank()) {
                    blocks.add(AnthropicRequest.ContentBlock.builder()
                            .type("text").text(m.getContent()).build());
                }
                for (ToolCall call : m.getToolCalls()) {
                    if (call.id() == null || call.id().isBlank()) {
                        log.warn("Dropping tool_use block without id in assistant history message "
                                + "(would be rejected by Anthropic)");
                        continue;
                    }
                    blocks.add(AnthropicRequest.ContentBlock.builder()
                            .type("tool_use")
                            .id(call.id())
                            .name(ToolNameSanitizer.sanitize(call.name()))
                            .input(call.args() != null
                                    ? jackson.convertValue(call.args(), Map.class)
                                    : Map.of())
                            .build());
                }
                if (blocks.isEmpty()) {
                    continue;
                }
                out.add(AnthropicRequest.Message.builder()
                        .role("assistant")
                        .content(blocks)
                        .build());
            } else {
                out.add(AnthropicRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent() == null ? "" : m.getContent())
                        .build());
            }
        }
        if (!pendingToolResults.isEmpty()) {
            out.add(AnthropicRequest.Message.builder()
                    .role("user")
                    .content(new ArrayList<>(pendingToolResults))
                    .build());
        }
        return reconcileToolCallPairs(out);
    }

    /**
     * Walk the assembled message list and drop {@code tool_use} blocks whose
     * id is not answered by a {@code tool_result} in the very next user turn,
     * and {@code tool_result} blocks whose id has no preceding {@code tool_use}.
     * If reconciling leaves an assistant message with only orphan tool calls,
     * the whole message is dropped to keep the conversation well-formed.
     */
    private List<AnthropicRequest.Message> reconcileToolCallPairs(List<AnthropicRequest.Message> msgs) {
        List<AnthropicRequest.Message> out = new ArrayList<>(msgs.size());
        for (int i = 0; i < msgs.size(); i++) {
            AnthropicRequest.Message m = msgs.get(i);
            if ("assistant".equals(m.getRole()) && m.getContent() instanceof List<?> blocks) {
                Set<String> answeredIds = collectToolResultIds(i + 1 < msgs.size() ? msgs.get(i + 1) : null);
                List<AnthropicRequest.ContentBlock> kept = new ArrayList<>();
                int droppedToolUses = 0;
                for (Object o : blocks) {
                    if (!(o instanceof AnthropicRequest.ContentBlock cb)) {
                        continue;
                    }
                    if ("tool_use".equals(cb.getType()) && !answeredIds.contains(cb.getId())) {
                        droppedToolUses++;
                        continue;
                    }
                    kept.add(cb);
                }
                if (droppedToolUses > 0) {
                    log.warn("Dropped {} unmatched tool_use block(s) from replayed assistant turn",
                            droppedToolUses);
                }
                boolean anyToolUseLeft = kept.stream().anyMatch(cb -> "tool_use".equals(cb.getType()));
                boolean anyTextLeft = kept.stream().anyMatch(cb -> "text".equals(cb.getType()));
                if (!anyToolUseLeft && !anyTextLeft) {
                    continue;
                }
                out.add(AnthropicRequest.Message.builder()
                        .role("assistant")
                        .content(kept)
                        .build());
            } else if ("user".equals(m.getRole()) && m.getContent() instanceof List<?> blocks) {
                Set<String> requestedIds = collectToolUseIds(out.isEmpty() ? null : out.get(out.size() - 1));
                List<AnthropicRequest.ContentBlock> kept = new ArrayList<>();
                int droppedToolResults = 0;
                for (Object o : blocks) {
                    if (!(o instanceof AnthropicRequest.ContentBlock cb)) {
                        continue;
                    }
                    if ("tool_result".equals(cb.getType()) && !requestedIds.contains(cb.getToolUseId())) {
                        droppedToolResults++;
                        continue;
                    }
                    kept.add(cb);
                }
                if (droppedToolResults > 0) {
                    log.warn("Dropped {} orphan tool_result block(s) from replayed user turn",
                            droppedToolResults);
                }
                if (kept.isEmpty()) {
                    continue;
                }
                out.add(AnthropicRequest.Message.builder()
                        .role("user")
                        .content(kept)
                        .build());
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private Set<String> collectToolResultIds(AnthropicRequest.Message m) {
        if (m == null || !"user".equals(m.getRole()) || !(m.getContent() instanceof List<?> blocks)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : blocks) {
            if (o instanceof AnthropicRequest.ContentBlock cb
                    && "tool_result".equals(cb.getType())
                    && cb.getToolUseId() != null) {
                ids.add(cb.getToolUseId());
            }
        }
        return ids;
    }

    private Set<String> collectToolUseIds(AnthropicRequest.Message m) {
        if (m == null || !"assistant".equals(m.getRole()) || !(m.getContent() instanceof List<?> blocks)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : blocks) {
            if (o instanceof AnthropicRequest.ContentBlock cb
                    && "tool_use".equals(cb.getType())
                    && cb.getId() != null) {
                ids.add(cb.getId());
            }
        }
        return ids;
    }

    private AnthropicRequest.Tool toToolPayload(ToolDescriptor descriptor) {
        Object schema;
        try {
            schema = descriptor.jsonSchema() == null
                    ? Map.of("type", "object")
                    : jackson.convertValue(descriptor.jsonSchema(), Map.class);
        } catch (RuntimeException e) {
            schema = Map.of("type", "object");
        }
        return AnthropicRequest.Tool.builder()
                .name(ToolNameSanitizer.sanitize(descriptor.name()))
                .description(descriptor.description())
                .inputSchema(schema)
                .build();
    }

    private ChatTurn interpret(AnthropicResponse response) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Empty response from Anthropic tool-call request");
            return ChatTurn.text("Unable to generate response - empty reply from AI.");
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (AnthropicResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
            } else if ("tool_use".equals(block.getType())) {
                Map<String, Object> input = block.getInput() != null
                        ? block.getInput() : new LinkedHashMap<>();
                JsonNode args = jackson.valueToTree(input);
                calls.add(new ToolCall(block.getId(),
                        ToolNameSanitizer.desanitize(block.getName()), args));
            }
        }
        StopReason reason = mapStopReason(response.getStopReason());
        if (!calls.isEmpty()) {
            reason = StopReason.TOOL_USE;
        }
        long inputTokens = 0L;
        long outputTokens = 0L;
        if (response.getUsage() != null) {
            inputTokens = response.getUsage().getInputTokens();
            outputTokens = response.getUsage().getOutputTokens();
            log.info("Anthropic chat-with-tools: {} input tokens, {} output tokens, {} tool_use block(s)",
                    inputTokens, outputTokens, calls.size());
            reportUsage(inputTokens, outputTokens);
        }
        return new ChatTurn(text.toString(), calls, reason, inputTokens, outputTokens);
    }

    private StopReason mapStopReason(String stopReason) {
        if (stopReason == null) {
            return StopReason.OTHER;
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("prompt is too long") || normalized.contains("maximum");
    }

    private String extractText(AnthropicResponse response, String context) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Empty response from Anthropic API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(AnthropicResponse.ContentBlock::getText)
                .reduce("", (a, b) -> a + b);

        if (response.getUsage() != null) {
            log.info("Anthropic {} response: {} input tokens, {} output tokens",
                    context,
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
            reportUsage(response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
        }
        return result;
    }

    private AnthropicResponse executeRequest(AnthropicRequest request) {
        return restClient.post()
                .uri("/v1/messages")
                .body(request)
                .retrieve()
                .body(AnthropicResponse.class);
    }
}

