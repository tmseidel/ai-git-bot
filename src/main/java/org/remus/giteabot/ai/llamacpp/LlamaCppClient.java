package org.remus.giteabot.ai.llamacpp;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.StreamingLineReader;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for llama.cpp server.
 * Uses the native /completion endpoint for full GBNF grammar support.
 * <p>
 * Supports GBNF grammar constraints for structured JSON output, which significantly
 * improves reliability for the agent feature compared to unconstrained generation.
 * <p>
 * See: https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md
 */
@Slf4j
public class LlamaCppClient extends AbstractAiClient {

    private final RestClient restClient;

    private final ObjectMapper jackson = AgentJackson.mapper();

    /**
     * GBNF grammar for the agent's JSON response format.
     * This grammar constrains the model to output valid JSON matching the expected schema:
     * - fileChanges: array of file modifications
     * - runTool: optional tool invocation
     * - message: optional message to the user
     * - done: boolean indicating completion
     */
    private static final String AGENT_JSON_GRAMMAR = """
            root ::= "{" ws members ws "}" ws
            members ::= pair ("," ws pair)*
            pair ::= string ws ":" ws value
            value ::= string | number | object | array | "true" | "false" | "null"
            object ::= "{" ws (members ws)? "}"
            array ::= "[" ws (value ("," ws value)*)? ws "]"
            string ::= "\\"" ([^"\\\\\\x00-\\x1f] | "\\\\" ["\\\\/bfnrt] | "\\\\u" [0-9a-fA-F]{4})* "\\""
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            ws ::= [ \\t\\n\\r]*
            """;

    /**
     * Stop sequences to prevent runaway generation.
     */
    private static final List<String> STOP_SEQUENCES = List.of(
            "<|im_start|>",
            "<|im_end|>",
            "<|end|>",
            "<|eot_id|>",
            "<|endoftext|>"
    );

    public LlamaCppClient(RestClient restClient, String model, int maxTokens) {
        super(model, maxTokens);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        String prompt = buildChatPrompt(systemPrompt, userMessage);
        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(prompt, maxTokens, "review", grammar);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        String prompt = buildChatPrompt(systemPrompt, conversationMessages);
        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(prompt, maxTokens, "chat", grammar);
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("context length")
                || normalized.contains("too long")
                || normalized.contains("maximum context")
                || normalized.contains("token limit")
                || normalized.contains("exceeds");
    }

    /**
     * Builds a chat prompt using ChatML format (used by Qwen, Mistral, etc.)
     */
    private String buildChatPrompt(String systemPrompt, String userMessage) {
        return "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n" +
                "<|im_start|>user\n" + userMessage + "<|im_end|>\n" +
                "<|im_start|>assistant\n";
    }

    /**
     * Builds a chat prompt from conversation history using ChatML format.
     */
    private String buildChatPrompt(String systemPrompt, List<AiMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n");

        for (AiMessage msg : messages) {
            sb.append("<|im_start|>").append(msg.getRole()).append("\n");
            sb.append(msg.getContent()).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    /**
     * Detects whether the system prompt is requesting JSON output for the agent.
     * If so, we enable GBNF grammar constraints for reliable structured responses.
     */
    private boolean shouldUseJsonGrammar(String systemPrompt) {
        if (systemPrompt == null) {
            return false;
        }
        String lower = systemPrompt.toLowerCase(Locale.ROOT);
        return lower.contains("respond with a json")
                || lower.contains("output json")
                || (lower.contains("output format") && lower.contains("json"))
                || lower.contains("\"filechanges\"")
                || lower.contains("\"runtool\"");
    }


    private String doRequest(String prompt, int maxTokens, String context, String grammar) {
        LlamaCppRequest.LlamaCppRequestBuilder requestBuilder = LlamaCppRequest.builder()
                .prompt(prompt)
                .nPredict(maxTokens)
                .stream(true)
                .stop(STOP_SEQUENCES)
                .temperature(0.7)
                .topP(0.9)
                .topK(40)
                .repeatPenalty(1.1)
                .frequencyPenalty(0.0)
                .presencePenalty(0.0)
                .cachePrompt(true);

        if (grammar != null) {
            requestBuilder.grammar(grammar);
            log.info("llama.cpp {} request: GBNF grammar enabled for structured JSON output", context);
        }

        LlamaCppRequest request = requestBuilder.build();

        log.debug("llama.cpp request to /completion: promptLength={}, maxTokens={}, grammar={}",
                prompt.length(), maxTokens, grammar != null);

        LlamaCppResponse response = executeRequest(request);

        return extractText(request, response, context);
    }

    private LlamaCppResponse executeRequest(LlamaCppRequest request) {
        // Stream the SSE chunks and reassemble them into a single response.
        // llama.cpp /completion with stream:true emits "data: {json}" per chunk
        // (an optional trailing "data: [DONE]" marker terminates the stream).
        // Content is concatenated across chunks; tokens_evaluated /
        // tokens_predicted / stopped_limit / timings come from the final chunk
        // (stop:true), which is the only one carrying the usage counters — so
        // audit/usage totals are unchanged from the non-streamed path.
        StringBuilder content = new StringBuilder();
        LlamaCppResponse[] finalRef = new LlamaCppResponse[1];
        LlamaCppResponse[] lastRef = new LlamaCppResponse[1];

        StreamingLineReader.streamLines(restClient, "/completion", request, line -> {
            // Strip the SSE "data: " framing (the line reader already dropped
            // line terminators and blank lines).
            String json = line.startsWith("data:") ? line.substring(5).stripLeading() : line;
            if (json.isEmpty() || "[DONE]".equals(json)) {
                return;
            }
            LlamaCppResponse chunk;
            try {
                chunk = jackson.readValue(json, LlamaCppResponse.class);
            } catch (JacksonException e) {
                // A malformed SSE line fails the request (do not silently skip).
                // Surfaced as an I/O error so AgentLoop.callAiWithRetry retries it
                // like any transient failure.
                throw new ResourceAccessException(
                        "Malformed llama.cpp stream line: " + e.getMessage(), new IOException(e));
            }
            lastRef[0] = chunk;
            if (chunk.getContent() != null) {
                content.append(chunk.getContent());
            }
            // llama.cpp marks the final chunk with stop:true; it also carries the
            // usage counters and timings.
            if ("true".equals(chunk.getStop())) {
                finalRef[0] = chunk;
            }
        });

        // 0-chunk stream: reproduce the old empty-response path (body() used to
        // return null for an empty body), so extractText applies its existing
        // "Unable to generate ... empty response" fallback.
        LlamaCppResponse source = finalRef[0] != null ? finalRef[0] : lastRef[0];
        if (source == null) {
            return null;
        }

        LlamaCppResponse merged = new LlamaCppResponse();
        merged.setContent(!content.isEmpty()
                ? content.toString()
                : (source.getContent() != null ? source.getContent() : ""));
        merged.setModel(source.getModel());
        merged.setStop(source.getStop());
        merged.setStoppedEos(source.getStoppedEos());
        merged.setStoppedLimit(source.getStoppedLimit());
        merged.setStoppedWord(source.getStoppedWord());
        merged.setTokensEvaluated(source.getTokensEvaluated());
        merged.setTokensPredicted(source.getTokensPredicted());
        merged.setTruncated(source.getTruncated());
        merged.setTimings(source.getTimings());
        return merged;
    }

    private String extractText(LlamaCppRequest request, LlamaCppResponse response, String context) {
        if (response == null || response.getContent() == null) {
            log.warn("Empty response from llama.cpp server");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getContent();

        // Log token usage
        if (response.getTokensEvaluated() != null && response.getTokensPredicted() != null) {
            log.info("llama.cpp {} response: {} prompt tokens, {} generated tokens",
                    context,
                    response.getTokensEvaluated(),
                    response.getTokensPredicted());
            reportUsage(response.getTokensEvaluated(), response.getTokensPredicted(), 0L, 0L, request, response);
        }

        // Log if stopped due to limits
        if (Boolean.TRUE.equals(response.getStoppedLimit())) {
            log.warn("llama.cpp {} response was truncated due to max token limit", context);
        }

        return result;
    }
}
