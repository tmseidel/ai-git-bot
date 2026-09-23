package org.remus.giteabot.ai.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AbstractAiClient;
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
import java.util.Comparator;
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

    /**
     * Distance between message cache breakpoints in content blocks. A
     * breakpoint only finds a cached predecessor within a 20-block lookback,
     * and an agent round adds ~2 blocks ({@code tool_use} + {@code
     * tool_result}), so anchoring every 16 blocks (~8 rounds) keeps the
     * trail within reach of the lookback window.
     */
    static final int CACHE_BREAKPOINT_STRIDE = 16;

    /**
     * Message-side breakpoints per request. Together with the system-block
     * breakpoint this stays within Anthropic's limit of 4 breakpoints.
     */
    static final int MAX_MESSAGE_BREAKPOINTS = 3;

    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final boolean promptCachingEnabled;
    private final boolean extendedThinkingEnabled;
    private final String extendedThinkingEffort;
    private final ObjectMapper jackson = AgentJackson.mapper();


    public AnthropicAiClient(RestClient restClient, String model, int maxTokens,
                             boolean nativeToolsEnabled, boolean promptCachingEnabled,
                             boolean extendedThinkingEnabled, String extendedThinkingEffort) {
        super(model, maxTokens);
        this.restClient = restClient;
        this.nativeToolsEnabled = nativeToolsEnabled;
        this.promptCachingEnabled = promptCachingEnabled;
        this.extendedThinkingEnabled = extendedThinkingEnabled;
        this.extendedThinkingEffort = extendedThinkingEffort;
    }

    @Override
    public boolean supportsNativeTools() {
        return nativeToolsEnabled;
    }

    /**
     * Builds the optional {@code thinking} request object. Returns {@code null}
     * when extended thinking is disabled, so the field is omitted from the
     * serialized payload entirely (no-op for every provider behaviour).
     */
    private AnthropicRequest.Thinking thinking() {
        if (!extendedThinkingEnabled) {
            return null;
        }
        return AnthropicRequest.Thinking.builder()
                .type("adaptive")
                .build();
    }

    /**
     * Builds the optional {@code output_config} request object that steers
     * adaptive thinking effort. Returns {@code null} when extended thinking is
     * disabled, mirroring {@link #thinking()}.
     */
    private AnthropicRequest.OutputConfig outputConfig() {
        if (!extendedThinkingEnabled) {
            return null;
        }
        return AnthropicRequest.OutputConfig.builder()
                .effort(extendedThinkingEffort)
                .build();
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
                .system(toSystemBlocks(systemPrompt))
                .thinking(thinking())
                .outputConfig(outputConfig())
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ))
                .build();

        AnthropicResponse response = executeRequest(request);
        return extractText(request, response, "review");
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
                .system(toSystemBlocks(systemPrompt))
                .thinking(thinking())
                .outputConfig(outputConfig())
                .messages(anthropicMessages)
                .build();

        AnthropicResponse response = executeRequest(request);
        return extractText(request, response, "chat");
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

        List<AnthropicRequest.Message> messages = useNativeTools ? buildToolMessages(fullHistory)
                : fullHistory.stream().map(this::toLegacyMessage).toList();

        // Tool definitions sit at the very front of the rendered prefix
        // (tools -> system -> messages), so a non-deterministic tool order
        // would silently invalidate every cache entry. Sort by name to keep
        // the prefix byte-stable across requests.
        List<AnthropicRequest.Tool> toolPayloads = useNativeTools ? tools.stream()
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .map(this::toToolPayload)
                .toList() : List.of();

        if (useNativeTools && promptCachingEnabled) {
            applyCacheBreakpoints(messages);
        }

        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(effectiveMaxTokens)
                .system(toSystemBlocks(effectivePrompt))
                .thinking(thinking())
                .outputConfig(outputConfig())
                .messages(messages)
                .tools(useNativeTools ? toolPayloads : null)
                .build();

        log.info("Anthropic chat turn request: model={}, tools={}, history={}",
                effectiveModel, toolPayloads.size(), messages.size());

        AnthropicResponse response = executeRequest(request);
        return interpret(request, response);
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
                Set<String> requestedIds = collectToolUseIds(out.isEmpty() ? null : out.getLast());
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

    /**
     * Places prompt-caching breakpoints on the assembled message list:
     * a rolling one on the very last content block (so round N+1 reads
     * round N's whole prefix at the 0.1x cache-read price) plus anchor
     * breakpoints every {@link #CACHE_BREAKPOINT_STRIDE} blocks walking
     * backwards. The anchors keep older parts of the conversation reachable:
     * a breakpoint only finds a cached predecessor within a 20-block
     * lookback, so a lone rolling breakpoint loses the trail once the tail
     * grows past that window.
     *
     * <p>String message contents are normalized to a single text content
     * block (semantically equivalent on the wire) so any position can carry
     * a breakpoint; blank strings are left untouched because empty text
     * blocks are rejected by the API.</p>
     */
    private void applyCacheBreakpoints(List<AnthropicRequest.Message> messages) {
        List<AnthropicRequest.ContentBlock> blocks = new ArrayList<>();
        for (AnthropicRequest.Message m : messages) {
            if (m.getContent() instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof AnthropicRequest.ContentBlock cb) {
                        blocks.add(cb);
                    }
                }
            } else if (m.getContent() instanceof String text && !text.isBlank()) {
                AnthropicRequest.ContentBlock cb = AnthropicRequest.ContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build();
                m.setContent(new ArrayList<>(List.of(cb)));
                blocks.add(cb);
            }
        }
        int index = blocks.size() - 1;
        int placed = 0;
        while (index >= 0 && placed < MAX_MESSAGE_BREAKPOINTS) {
            blocks.get(index).setCacheControl(AnthropicRequest.CacheControl.builder().build());
            placed++;
            index -= CACHE_BREAKPOINT_STRIDE;
        }
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

    private ChatTurn interpret(AnthropicRequest request, AnthropicResponse response) {
        if (response == null) {
            log.warn("Empty response from Anthropic tool-call request");
            return new ChatTurn("", List.of(), StopReason.OTHER, 0L, 0L);
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        List<AnthropicResponse.ContentBlock> content = response.getContent() == null ? List.of() : response.getContent();
        for (AnthropicResponse.ContentBlock block : content) {
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
        if (!calls.isEmpty() && reason == StopReason.END_TURN) {
            reason = StopReason.TOOL_USE;
        }
        long inputTokens = 0L;
        long outputTokens = 0L;
        if (response.getUsage() != null) {
            AnthropicResponse.Usage usage = response.getUsage();
            // With prompt caching, input_tokens only counts the uncached
            // remainder; the total prompt size (what the context window and
            // compaction threshold care about) is the sum of all three.
            inputTokens = usage.getInputTokens()
                    + usage.getCacheCreationInputTokens()
                    + usage.getCacheReadInputTokens();
            outputTokens = usage.getOutputTokens();
            log.info("Anthropic chat-with-tools: {} input tokens ({} uncached, {} cache write, "
                            + "{} cache read), {} output tokens, {} tool_use block(s)",
                    inputTokens, usage.getInputTokens(), usage.getCacheCreationInputTokens(),
                    usage.getCacheReadInputTokens(), outputTokens, calls.size());
            reportUsage(inputTokens, outputTokens,
                    usage.getCacheCreationInputTokens(), usage.getCacheReadInputTokens(),
                    request, response);
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

    private String extractText(AnthropicRequest request, AnthropicResponse response, String context) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Empty response from Anthropic API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(AnthropicResponse.ContentBlock::getText)
                .reduce("", (a, b) -> a + b);

        if (response.getUsage() != null) {
            AnthropicResponse.Usage usage = response.getUsage();
            long totalInput = usage.getInputTokens() + usage.getCacheCreationInputTokens()
                    + usage.getCacheReadInputTokens();
            log.info("Anthropic {} response: {} input tokens ({} uncached, {} cache write, "
                            + "{} cache read), {} output tokens",
                    context,
                    totalInput,
                    usage.getInputTokens(),
                    usage.getCacheCreationInputTokens(),
                    usage.getCacheReadInputTokens(),
                    usage.getOutputTokens());
            reportUsage(totalInput, usage.getOutputTokens(),
                    usage.getCacheCreationInputTokens(), usage.getCacheReadInputTokens(),
                    request, response);
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

    /**
     * Wraps a plain system-prompt string as a single-element content-block
     * list, the shape required since {@link AnthropicRequest#getSystem()}
     * became cache-controllable (must be an array, not a bare string). When
     * prompt caching is enabled the block carries the static-head
     * breakpoint: everything before it (tool definitions + system prompt)
     * is shared by every round of the agentic loop and is cached once.
     */
    private List<AnthropicRequest.ContentBlock> toSystemBlocks(String systemPrompt) {
        AnthropicRequest.ContentBlock.ContentBlockBuilder block = AnthropicRequest.ContentBlock.builder()
                .type("text")
                .text(systemPrompt);
        if (promptCachingEnabled) {
            block.cacheControl(AnthropicRequest.CacheControl.builder().build());
        }
        return List.of(block.build());
    }
}
