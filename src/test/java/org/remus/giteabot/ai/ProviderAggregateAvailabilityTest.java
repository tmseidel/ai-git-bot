package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProviderAggregateAvailabilityTest {

    @ParameterizedTest(name = "{0}: native tools = {1}")
    @CsvSource(textBlock = """
            anthropic, true,  false, true,  0, 3
            anthropic, false, false, true,  0, 3
            google,    true,  true,  false, 5, 0
            google,    false, true,  false, 5, 0
            """)
    void suppliedNullComponentInvalidatesOnlyItsDependentAggregate(
            String provider, boolean nativeTools, boolean inputReported, boolean outputReported,
            long input, long output) {
        String usage = "anthropic".equals(provider)
                ? """
                  {"input_tokens":0,"output_tokens":3,"cache_creation_input_tokens":null}
                  """
                : """
                  {"promptTokenCount":5,"candidatesTokenCount":0,"thoughtsTokenCount":null}
                  """;
        assertUsage(provider, nativeTools ? "native-turn" : "text-turn", usage,
                inputReported, outputReported, input, output, 0, 0);
    }

    @ParameterizedTest(name = "{0}: usage = {1}")
    @MethodSource({"invalidOptionalComponents", "invalidPrimaryCounters", "invalidCachedMetadata", "invalidTotalMetadata"})
    void invalidCounterRetainsAnswerAndIndependentUsage(String provider, String usage,
            boolean inputReported, boolean outputReported, long input, long output, long cacheWrite, long cacheRead) {
        for (String path : List.of("native-turn", "text-turn", "chat")) {
            assertUsage(provider, path, usage, inputReported, outputReported, input, output, cacheWrite, cacheRead);
        }
    }

    static Stream<Arguments> invalidOptionalComponents() {
        return invalidCounterValues()
                .flatMap(value -> Stream.of(
                        Arguments.of("anthropic", """
                                {"input_tokens":5,"cache_creation_input_tokens":%s,
                                 "cache_read_input_tokens":30,"output_tokens":3}
                                """.formatted(value), false, true, 0, 3, 0, 30),
                        Arguments.of("anthropic", """
                                {"input_tokens":5,"cache_read_input_tokens":%s,
                                 "cache_creation_input_tokens":20,"output_tokens":3}
                                """.formatted(value), false, true, 0, 3, 20, 0),
                        Arguments.of("google", """
                                {"thoughtsTokenCount":%s,"promptTokenCount":100,
                                 "candidatesTokenCount":3,"cachedContentTokenCount":40}
                                """.formatted(value), true, false, 100, 0, 0, 40)));
    }

    static Stream<Arguments> invalidPrimaryCounters() {
        return invalidCounterValues()
                .flatMap(value -> Stream.of(
                        Arguments.of("anthropic", """
                                {"input_tokens":%s,"output_tokens":3,
                                 "cache_creation_input_tokens":20,"cache_read_input_tokens":30}
                                """.formatted(value), false, true, 0, 3, 20, 30),
                        Arguments.of("anthropic", """
                                {"output_tokens":%s,"input_tokens":5,
                                 "cache_creation_input_tokens":20,"cache_read_input_tokens":30}
                                """.formatted(value), true, false, 55, 0, 20, 30),
                        Arguments.of("google", """
                                {"promptTokenCount":%s,"candidatesTokenCount":3,
                                 "thoughtsTokenCount":7,"cachedContentTokenCount":40}
                                """.formatted(value), false, true, 0, 10, 0, 40),
                        Arguments.of("google", """
                                {"candidatesTokenCount":%s,"promptTokenCount":100,
                                 "thoughtsTokenCount":7,"cachedContentTokenCount":40}
                                """.formatted(value), true, false, 100, 0, 0, 40)));
    }

    static Stream<Arguments> invalidCachedMetadata() {
        return invalidCounterValues().map(value -> Arguments.of("google", """
                {"cachedContentTokenCount":%s,"promptTokenCount":100,
                 "candidatesTokenCount":3,"thoughtsTokenCount":7}
                """.formatted(value), true, true, 100, 10, 0, 0));
    }

    static Stream<Arguments> invalidTotalMetadata() {
        return invalidCounterValues().map(value -> Arguments.of("google", """
                {"totalTokenCount":%s,"promptTokenCount":100,"candidatesTokenCount":3,
                 "thoughtsTokenCount":7,"cachedContentTokenCount":40}
                """.formatted(value), true, true, 100, 10, 0, 40));
    }

    @ParameterizedTest(name = "{0}: usage = {1}")
    @MethodSource("counterPresenceCases")
    void distinguishesMissingPrimaryCountersFromOmittedOptionalComponents(String provider, String usage,
            boolean inputReported, boolean outputReported, long input, long output, long cacheWrite, long cacheRead) {
        for (String path : List.of("native-turn", "text-turn", "chat")) {
            assertUsage(provider, path, usage, inputReported, outputReported, input, output, cacheWrite, cacheRead);
        }
    }

    static Stream<Arguments> counterPresenceCases() {
        return Stream.of(
                Arguments.of("anthropic", "{}", false, false, 0, 0, 0, 0),
                Arguments.of("anthropic", "{\"input_tokens\":0,\"output_tokens\":0}", true, true, 0, 0, 0, 0),
                Arguments.of("anthropic", """
                        {"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}
                        """, true, true, 0, 0, 0, 0),
                Arguments.of("anthropic", """
                        {"output_tokens":3,"cache_creation_input_tokens":20,"cache_read_input_tokens":30}
                        """, false, true, 0, 3, 20, 30),
                Arguments.of("anthropic", "{\"input_tokens\":0}", true, false, 0, 0, 0, 0),
                Arguments.of("anthropic", "{\"output_tokens\":0}", false, true, 0, 0, 0, 0),
                Arguments.of("google", "{}", false, false, 0, 0, 0, 0),
                Arguments.of("google", "{\"totalTokenCount\":17}", false, false, 0, 0, 0, 0),
                Arguments.of("google", "{\"promptTokenCount\":0,\"candidatesTokenCount\":0}", true, true, 0, 0, 0, 0),
                Arguments.of("google", """
                        {"promptTokenCount":0,"candidatesTokenCount":0,"thoughtsTokenCount":0,"cachedContentTokenCount":0}
                        """, true, true, 0, 0, 0, 0),
                Arguments.of("google", """
                        {"promptTokenCount":5,"candidatesTokenCount":0,"thoughtsTokenCount":7}
                        """, true, true, 5, 7, 0, 0),
                Arguments.of("google", "{\"promptTokenCount\":5,\"thoughtsTokenCount\":7}", true, false, 5, 0, 0, 0),
                Arguments.of("google", "{\"candidatesTokenCount\":0}", false, true, 0, 0, 0, 0),
                Arguments.of("google", """
                        {"candidatesTokenCount":3,"thoughtsTokenCount":7,"cachedContentTokenCount":40}
                        """, false, true, 0, 10, 0, 40));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wideAggregateCases")
    void sumsDisjointCountersWithoutOverflowOrDoubleCounting(String provider, String usage,
            long input, long output, long cacheWrite, long cacheRead) {
        for (String path : List.of("native-turn", "text-turn", "chat")) {
            assertUsage(provider, path, usage, true, true, input, output, cacheWrite, cacheRead);
        }
    }

    static Stream<Arguments> wideAggregateCases() {
        return Stream.of(
                Arguments.of("anthropic", """
                        {"input_tokens":2147483647,"output_tokens":2147483647,
                         "cache_creation_input_tokens":2147483647,"cache_read_input_tokens":2147483647}
                        """, 6_442_450_941L, 2_147_483_647L, 2_147_483_647L, 2_147_483_647L),
                Arguments.of("google", """
                        {"promptTokenCount":2147483647,"candidatesTokenCount":2147483647,
                         "thoughtsTokenCount":2147483647,"cachedContentTokenCount":2147483647,"totalTokenCount":0}
                        """, 2_147_483_647L, 4_294_967_294L, 0, 2_147_483_647L));
    }

    private static Stream<String> invalidCounterValues() {
        return Stream.of("null", "-1", "-0.5", "0.5", "1.0", "\"0\"", "true", "{\"value\":0}", "[0]", "2147483648");
    }

    private static void assertUsage(String provider, String path, String usage,
            boolean inputReported, boolean outputReported, long input, long output, long cacheWrite, long cacheRead) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AbstractAiClient client;
        String endpoint;
        String response;
        switch (provider) {
            case "anthropic" -> {
                client = new AnthropicAiClient(http.build(), "test-model", 32, true, false, false, null);
                endpoint = "/v1/messages";
                response = """
                        {"content":[{"type":"text","text":"Answer"}],"stop_reason":"end_turn","usage":%s}
                        """.formatted(usage);
            }
            case "google" -> {
                client = new GoogleAiClient(http.build(), "test-model", 32, true);
                endpoint = "/v1beta/models/test-model:generateContent";
                response = """
                        {"candidates":[{"content":{"parts":[{"text":"Answer"}]},"finishReason":"STOP"}],
                         "usageMetadata":%s}
                        """.formatted(usage);
            }
            default -> throw new IllegalArgumentException(provider);
        }
        boolean nativeTools = "native-turn".equals(path);
        server.expect(requestTo("https://provider.example" + endpoint))
                .andExpect(nativeTools ? jsonPath("$.tools").isNotEmpty() : jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        CapturingRecorder recorder = new CapturingRecorder();
        client.setAuditRecorder(recorder);

        if ("chat".equals(path)) {
            assertThat(client.chat(List.of(), "Question", "System", null)).isEqualTo("Answer");
        } else {
            List<ToolDescriptor> tools = nativeTools
                    ? List.of(new ToolDescriptor("lookup", "Read context", null)) : List.of();
            ChatTurn turn = client.chatWithTools(List.of(), "Question", tools, "System", null, null);
            assertAll(provider + ": " + path,
                    () -> assertThat(turn.assistantText()).isEqualTo("Answer"),
                    () -> assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN),
                    () -> assertThat(turn.inputTokensReported()).as("input availability").isEqualTo(inputReported),
                    () -> assertThat(turn.outputTokensReported()).as("output availability").isEqualTo(outputReported),
                    () -> assertThat(turn.inputTokens()).isEqualTo(input),
                    () -> assertThat(turn.outputTokens()).isEqualTo(output));
        }
        server.verify();
        assertThat(recorder.usage).as(provider + ": " + path)
                .containsExactly(new RecordedUsage(input, output, cacheWrite, cacheRead));
    }

    private record RecordedUsage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {}

    private static final class CapturingRecorder implements AiAuditRecorder {
        private final List<RecordedUsage> usage = new ArrayList<>();

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
            usage.add(new RecordedUsage(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
        }

    }
}
