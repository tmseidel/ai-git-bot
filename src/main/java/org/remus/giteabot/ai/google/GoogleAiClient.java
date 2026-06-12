package org.remus.giteabot.ai.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI client implementation for Google's Gemini REST API.
 *
 * <p>Step 6: implements native function calling via Gemini's
 * {@code tools[].functionDeclarations} request field and the corresponding
 * {@code functionCall}/{@code functionResponse} response/request parts.
 * Activation is gated by the per-integration
 * {@code use_legacy_tool_calling} switch (see
 * {@link org.remus.giteabot.admin.AiIntegration}).</p>
 */
@Slf4j
public class GoogleAiClient extends AbstractAiClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Key used in {@link ToolCall#providerMetadata()} to carry the Gemini 3.x
     * {@code thoughtSignature} between turns. The signature must be replayed
     * verbatim on every replayed function-call part or the API rejects the
     * request with {@code "Function call is missing a thought_signature"}.
     */
    private static final String THOUGHT_SIGNATURE_KEY = "thoughtSignature";

    private static String sanitizeFunctionName(String name) {
        return ToolNameSanitizer.sanitize(name);
    }

    private static String desanitizeFunctionName(String name) {
        return ToolNameSanitizer.desanitize(name);
    }

    private final RestClient restClient;
    private final boolean nativeToolsEnabled;
    private final tools.jackson.databind.ObjectMapper jackson3 = AgentJackson.mapper();

    public GoogleAiClient(RestClient restClient, String model, int maxTokens,
                          int maxDiffCharsPerChunk, int maxDiffChunks,
                          int retryTruncatedChunkChars) {
        this(restClient, model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks,
                retryTruncatedChunkChars, true);
    }

    public GoogleAiClient(RestClient restClient, String model, int maxTokens,
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
        GoogleAiRequest request = GoogleAiRequest.builder()
                .systemInstruction(textContent(null, systemPrompt))
                .contents(List.of(textContent("user", userMessage)))
                .generationConfig(GoogleAiRequest.GenerationConfig.builder()
                        .maxOutputTokens(maxTokens)
                        .build())
                .build();

        return doRequest(effectiveModel, request, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> messages) {
        List<GoogleAiRequest.Content> contents = new ArrayList<>();
        for (AiMessage message : messages) {
            // Plain-text path: collapse tool-results into user-text content.
            String text = "tool".equals(message.getRole())
                    ? (message.getToolResult() != null ? message.getToolResult() : message.getContent())
                    : message.getContent();
            contents.add(textContent(toGoogleRole(message.getRole()),
                    text == null ? "" : text));
        }

        GoogleAiRequest request = GoogleAiRequest.builder()
                .systemInstruction(textContent(null, systemPrompt))
                .contents(contents)
                .generationConfig(GoogleAiRequest.GenerationConfig.builder()
                        .maxOutputTokens(maxTokens)
                        .build())
                .build();

        return doRequest(effectiveModel, request, "chat");
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

        List<GoogleAiRequest.Content> contents = buildToolContents(fullHistory);

        List<GoogleAiRequest.FunctionDeclaration> declarations = tools.stream()
                .map(this::toFunctionDeclaration)
                .toList();

        GoogleAiRequest request = GoogleAiRequest.builder()
                .systemInstruction(textContent(null, systemPrompt))
                .contents(contents)
                .generationConfig(GoogleAiRequest.GenerationConfig.builder()
                        .maxOutputTokens(effectiveMaxTokens)
                        .build())
                .tools(List.of(GoogleAiRequest.Tool.builder()
                        .functionDeclarations(declarations).build()))
                .build();

        log.info("Google AI chat-with-tools request: model={}, tools={}, history={}",
                effectiveModel, declarations.size(), contents.size());

        try {
            GoogleAiResponse response = restClient.post()
                    .uri("/v1beta/" + toModelPath(effectiveModel) + ":generateContent")
                    .body(request)
                    .retrieve()
                    .body(GoogleAiResponse.class);
            return interpret(response);
        } catch (HttpClientErrorException e) {
            if (isPromptTooLongError(e)) {
                throw e;
            }
            throw new IllegalStateException("Google AI request failed: " + safeErrorMessage(e), e);
        }
    }

    private List<GoogleAiRequest.Content> buildToolContents(List<AiMessage> history) {
        List<GoogleAiRequest.Content> contents = new ArrayList<>();
        for (AiMessage m : history) {
            if ("tool".equals(m.getRole())) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("result", m.getToolResult() != null ? m.getToolResult() : m.getContent());
                contents.add(GoogleAiRequest.Content.builder()
                        .role("user")
                        .parts(List.of(GoogleAiRequest.Part.builder()
                                .functionResponse(GoogleAiRequest.FunctionResponse.builder()
                                        .name(sanitizeFunctionName(callNameFor(m, "tool")))
                                        .response(response)
                                        .build())
                                .build()))
                        .build());
                continue;
            }

            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<GoogleAiRequest.Part> parts = new ArrayList<>();
                if (m.getContent() != null && !m.getContent().isBlank()) {
                    parts.add(GoogleAiRequest.Part.builder().text(m.getContent()).build());
                }
                for (ToolCall call : m.getToolCalls()) {
                    // Replay any Gemini 3.x thoughtSignature that the model
                    // returned alongside this call — see ToolCall.providerMetadata.
                    String thoughtSignature = call.providerMetadata() != null
                            ? call.providerMetadata().get(THOUGHT_SIGNATURE_KEY) : null;
                    parts.add(GoogleAiRequest.Part.builder()
                            .functionCall(GoogleAiRequest.FunctionCall.builder()
                                    .name(sanitizeFunctionName(call.name()))
                                    .args(call.args() != null
                                            ? jackson3.convertValue(call.args(), Map.class)
                                            : Map.of())
                                    .build())
                            .thoughtSignature(thoughtSignature)
                            .build());
                }
                contents.add(GoogleAiRequest.Content.builder()
                        .role("model")
                        .parts(parts)
                        .build());
                continue;
            }

            contents.add(textContent(toGoogleRole(m.getRole()),
                    m.getContent() == null ? "" : m.getContent()));
        }
        return contents;
    }

    private String callNameFor(AiMessage m, String fallback) {
        // Tool-result messages don't directly know the call name; encode it in
        // the toolCallId or default to a generic value. The synthetic id has the
        // shape "<name>:<8-hex-uuid>", and the name itself may contain colons
        // (e.g. MCP tools like "mcp:github:issue_read"), so split at the LAST
        // colon to recover the original function name intact.
        if (m.getToolCallId() != null && m.getToolCallId().contains(":")) {
            return m.getToolCallId().substring(0, m.getToolCallId().lastIndexOf(':'));
        }
        return fallback;
    }

    private GoogleAiRequest.FunctionDeclaration toFunctionDeclaration(ToolDescriptor descriptor) {
        Object schema;
        try {
            schema = descriptor.jsonSchema() == null
                    ? Map.of("type", "object")
                    : jackson3.convertValue(descriptor.jsonSchema(), Map.class);
        } catch (RuntimeException e) {
            schema = Map.of("type", "object");
        }
        return GoogleAiRequest.FunctionDeclaration.builder()
                .name(sanitizeFunctionName(descriptor.name()))
                .description(descriptor.description())
                .parameters(schema)
                .build();
    }

    private ChatTurn interpret(GoogleAiResponse response) {
        if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
            log.warn("Empty response from Google AI tool-call request");
            return ChatTurn.text("Unable to generate response - empty reply from AI.");
        }
        GoogleAiResponse.Candidate candidate = response.getCandidates().getFirst();
        if (candidate == null || candidate.getContent() == null
                || candidate.getContent().getParts() == null) {
            return ChatTurn.text("");
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (GoogleAiResponse.Part part : candidate.getContent().getParts()) {
            if (part.getText() != null && !part.getText().isBlank()) {
                text.append(part.getText());
            } else if (part.getFunctionCall() != null) {
                Map<String, Object> args = part.getFunctionCall().getArgs() != null
                        ? part.getFunctionCall().getArgs() : new LinkedHashMap<>();
                tools.jackson.databind.JsonNode args3 = jackson3.valueToTree(args);
                // Reverse the colon-sanitisation applied in toFunctionDeclaration so
                // the rest of the system sees the original tool name (e.g. MCP tools
                // such as "mcp:github:issue_read" were sent as "mcp__github__issue_read").
                String originalName = desanitizeFunctionName(part.getFunctionCall().getName());
                String syntheticId = originalName
                        + ":" + java.util.UUID.randomUUID().toString().substring(0, 8);
                // Gemini 3.x attaches a thoughtSignature to every functionCall part
                // and rejects subsequent requests that replay the call without it.
                // Preserve it via the generic providerMetadata bag so buildToolContents
                // can echo it back verbatim. Older models leave the field null and we
                // simply skip the metadata.
                Map<String, String> meta = part.getThoughtSignature() != null
                        ? Map.of(THOUGHT_SIGNATURE_KEY, part.getThoughtSignature())
                        : null;
                calls.add(new ToolCall(
                        syntheticId,
                        originalName,
                        args3,
                        meta));
            }
        }
        StopReason reason = mapStopReason(candidate.getFinishReason());
        if (!calls.isEmpty()) {
            reason = StopReason.TOOL_USE;
        }
        if (response.getUsageMetadata() != null) {
            log.info("Google AI chat-with-tools: {} prompt tokens, {} candidate tokens, {} functionCall(s)",
                    response.getUsageMetadata().getPromptTokenCount(),
                    response.getUsageMetadata().getCandidatesTokenCount(),
                    calls.size());
            reportUsage(response.getUsageMetadata().getPromptTokenCount(),
                    response.getUsageMetadata().getCandidatesTokenCount());
        }
        return new ChatTurn(text.toString(), calls, reason);
    }

    private StopReason mapStopReason(String finishReason) {
        if (finishReason == null) {
            return StopReason.OTHER;
        }
        return switch (finishReason) {
            case "STOP" -> StopReason.END_TURN;
            case "MAX_TOKENS" -> StopReason.MAX_TOKENS;
            case "TOOL_CODE", "FUNCTION_CALL" -> StopReason.TOOL_USE;
            default -> StopReason.OTHER;
        };
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("input token count")
                || normalized.contains("maximum number of tokens")
                || normalized.contains("exceeds the maximum")
                || normalized.contains("token limit");
    }

    private String doRequest(String model, GoogleAiRequest request, String context) {
        try {
            GoogleAiResponse response = restClient.post()
                    .uri("/v1beta/" + toModelPath(model) + ":generateContent")
                    .body(request)
                    .retrieve()
                    .body(GoogleAiResponse.class);

            return extractText(response, context);
        } catch (HttpClientErrorException e) {
            if (isPromptTooLongError(e)) {
                throw e;
            }
            throw new IllegalStateException("Google AI request failed: " + safeErrorMessage(e), e);
        }
    }

    private String extractText(GoogleAiResponse response, String context) {
        if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
            log.warn("Empty response from Google AI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        GoogleAiResponse.Candidate firstCandidate = response.getCandidates().getFirst();
        if (firstCandidate == null
                || firstCandidate.getContent() == null
                || firstCandidate.getContent().getParts() == null
                || firstCandidate.getContent().getParts().isEmpty()) {
            log.warn("Empty response from Google AI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = firstCandidate.getContent().getParts().stream()
                .map(GoogleAiResponse.Part::getText)
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (a, b) -> a + b);

        if (result.isBlank()) {
            log.warn("Empty text response from Google AI API");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        if (response.getUsageMetadata() != null) {
            log.info("Google AI {} response: {} prompt tokens, {} candidate tokens",
                    context,
                    response.getUsageMetadata().getPromptTokenCount(),
                    response.getUsageMetadata().getCandidatesTokenCount());
            reportUsage(response.getUsageMetadata().getPromptTokenCount(),
                    response.getUsageMetadata().getCandidatesTokenCount());
        }

        return result;
    }

    private GoogleAiRequest.Content textContent(String role, String text) {
        return GoogleAiRequest.Content.builder()
                .role(role)
                .parts(List.of(GoogleAiRequest.Part.builder().text(text).build()))
                .build();
    }

    private String toGoogleRole(String role) {
        if ("assistant".equals(role)) {
            return "model";
        }
        return "user";
    }

    private String toModelPath(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Google AI integration requires a model");
        }
        String trimmed = model.trim();
        if (trimmed.startsWith("/") || trimmed.contains("?") || trimmed.contains("#")) {
            throw new IllegalStateException("Invalid Google AI model name: " + model);
        }
        return trimmed.startsWith("models/") ? trimmed : "models/" + trimmed;
    }

    private String safeErrorMessage(HttpClientErrorException e) {
        String responseBody = e.getResponseBodyAsString();
        String message = extractGoogleErrorMessage(responseBody);
        if (message == null || message.isBlank()) {
            message = e.getStatusText();
        }
        return e.getStatusCode() + " " + message;
    }

    private String extractGoogleErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode message = OBJECT_MAPPER.readTree(responseBody).path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

