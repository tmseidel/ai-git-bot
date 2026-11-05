package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.openrouter.OpenRouterClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProviderCacheUsageTest {

    @ParameterizedTest
    @MethodSource("cacheUsageCases")
    void recordsProviderCacheUsageAcrossCallPaths(String provider, String path, String cacheFields,
                                                 long cacheWrite, long cacheRead) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        boolean nativeTools = !"legacy-turn".equals(path);
        AbstractAiClient client = switch (provider) {
            case "google" -> new GoogleAiClient(http.build(), "test-model", 128, nativeTools);
            case "ollama" -> new OllamaClient(http.build(), "test-model", 128, nativeTools);
            case "llamacpp" -> new LlamaCppClient(http.build(), "test-model", 128);
            case "openrouter" -> new OpenRouterClient(http.build(), "author/model", 128, nativeTools, "AI Git Bot");
            case "anthropic" -> new AnthropicAiClient(http.build(), "test-model", 128,
                    nativeTools, false, false, null);
            default -> throw new IllegalArgumentException(provider);
        };
        String endpoint = switch (provider) {
            case "google" -> "/v1beta/models/test-model:generateContent";
            case "ollama" -> "/api/chat";
            case "llamacpp" -> "/v1/completions";
            case "openrouter" -> "/v1/chat/completions";
            case "anthropic" -> "/v1/messages";
            default -> throw new IllegalArgumentException(provider);
        };
        String response = response(provider, cacheFields, cacheWrite, cacheRead);
        MediaType mediaType = switch (provider) {
            case "ollama" -> MediaType.APPLICATION_NDJSON;
            case "llamacpp" -> MediaType.TEXT_EVENT_STREAM;
            default -> MediaType.APPLICATION_JSON;
        };
        server.expect(requestTo("https://provider.example" + endpoint))
                .andRespond(withSuccess(response, mediaType));
        AiAuditRecorder recorder = mock(AiAuditRecorder.class);
        client.setAuditRecorder(recorder);

        switch (path) {
            case "review" -> assertEquals("OK", client.submitReviewPrompt("System", null, "Question"));
            case "chat" -> assertEquals("OK", client.chat(List.of(), "Question", "System", null));
            default -> {
                List<ToolDescriptor> tools = "text-turn".equals(path) ? List.of()
                        : List.of(new ToolDescriptor("lookup", "Read context",
                        AgentJackson.mapper().createObjectNode()));
                ChatTurn turn = client.chatWithTools(List.of(), "Question", tools, "System", null, null);
                assertEquals("OK", turn.assistantText());
                assertEquals(100L, turn.inputTokens());
                assertEquals(10L, turn.outputTokens());
            }
        }

        verify(recorder).recordUsage(100L, 10L, cacheWrite, cacheRead, null, null);
        server.verify();
    }

    static Stream<Arguments> cacheUsageCases() {
        return Stream.of("review", "chat", "native-turn", "text-turn", "legacy-turn")
                .flatMap(path -> Stream.of(
                        Arguments.of("google", path, "", 0L, 0L),
                        Arguments.of("google", path, ",\"cachedContentTokenCount\":null", 0L, 0L),
                        Arguments.of("google", path, ",\"cachedContentTokenCount\":0", 0L, 0L),
                        Arguments.of("google", path, ",\"cachedContentTokenCount\":80", 0L, 80L),
                        Arguments.of("ollama", path, "", 0L, 0L),
                        Arguments.of("ollama", path, ",\"prompt_eval_cached_count\":null", 0L, 0L),
                        Arguments.of("ollama", path, ",\"prompt_eval_cached_count\":0", 0L, 0L),
                        Arguments.of("ollama", path, ",\"prompt_eval_cached_count\":80", 0L, 80L),
                        Arguments.of("llamacpp", path, "", 0L, 0L),
                        Arguments.of("llamacpp", path, ",\"prompt_tokens_details\":null", 0L, 0L),
                        Arguments.of("llamacpp", path, ",\"prompt_tokens_details\":{}", 0L, 0L),
                        Arguments.of("llamacpp", path, ",\"prompt_tokens_details\":{\"cached_tokens\":null}", 0L, 0L),
                        Arguments.of("llamacpp", path, ",\"prompt_tokens_details\":{\"cached_tokens\":0}", 0L, 0L),
                        Arguments.of("llamacpp", path, ",\"prompt_tokens_details\":{\"cached_tokens\":80}", 0L, 80L),
                        Arguments.of("openrouter", path, "", 0L, 0L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":null", 0L, 0L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":{\"cached_tokens\":null,\"cache_write_tokens\":null}", 0L, 0L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}", 0L, 0L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":{\"cached_tokens\":80}", 0L, 80L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":{\"cache_write_tokens\":15}", 15L, 0L),
                        Arguments.of("openrouter", path, ",\"prompt_tokens_details\":{\"cached_tokens\":80,\"cache_write_tokens\":15}", 15L, 80L),
                        Arguments.of("anthropic", path, "", 0L, 0L),
                        Arguments.of("anthropic", path,
                                ",\"cache_creation_input_tokens\":15,\"cache_read_input_tokens\":80", 15L, 80L)));
    }

    private static String response(String provider, String cacheFields, long cacheWrite, long cacheRead) {
        return switch (provider) {
            case "google" -> """
                    {"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":10%s}}
                    """.formatted(cacheFields);
            case "ollama" -> """
                    {"message":{"role":"assistant","content":"O"},"done":false}
                    {"message":{"role":"assistant","content":"K"},"done":true,"done_reason":"stop","prompt_eval_count":100,"eval_count":10%s}
                    """.formatted(cacheFields);
            case "llamacpp" -> """
                    data: {"choices":[{"text":"OK","finish_reason":"stop"}]}
                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10%s}}
                    data: [DONE]
                    """.formatted(cacheFields);
            case "openrouter" -> """
                    {"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":100,"completion_tokens":10%s}}
                    """.formatted(cacheFields);
            case "anthropic" -> """
                    {"content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":%d,"output_tokens":10%s}}
                    """.formatted(100L - cacheWrite - cacheRead, cacheFields);
            default -> throw new IllegalArgumentException(provider);
        };
    }
}
