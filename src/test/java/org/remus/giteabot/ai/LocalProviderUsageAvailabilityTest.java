package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalProviderUsageAvailabilityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputCounterCases")
    void retainsAnswerAndReportedOutputZeroWhenInputCounterIsInvalid(
            String provider, String endpoint, MediaType mediaType, String response) {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example" + endpoint))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response, mediaType));
        AbstractAiClient client = switch (provider) {
            case "ollama" -> new OllamaClient(http.build(), "model", 32, true);
            case "llamacpp" -> new LlamaCppClient(http.build(), "model", 32);
            default -> throw new IllegalArgumentException(provider);
        };
        List<List<Long>> legacyUsageRows = new ArrayList<>();
        client.setAuditRecorder(new AiAuditRecorder() {
            @Override
            public void recordUsage(long inputTokens, long outputTokens,
                                    long cacheCreationInputTokens, long cacheReadInputTokens,
                                    String rawRequest, String rawResponse) {
                legacyUsageRows.add(List.of(inputTokens, outputTokens));
            }

        });

        ChatTurn turn = client.chatWithTools(List.of(), "Question", List.of(), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.inputTokensReported()).isFalse();
        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokensReported()).isTrue();
        assertThat(turn.outputTokens()).isZero();
        // The legacy audit callback uses zero placeholders; only the turn carries availability.
        assertThat(legacyUsageRows).containsExactly(List.of(0L, 0L));
        server.verify();
    }

    static Stream<Arguments> invalidInputCounterCases() {
        return Stream.of(
                Arguments.of("ollama", "/api/chat", MediaType.APPLICATION_NDJSON, """
                        {"message":{"role":"assistant","content":"Answer"},"done":true,"prompt_eval_count":{},"eval_count":0}
                        """),
                Arguments.of("llamacpp", "/v1/completions", MediaType.TEXT_EVENT_STREAM, """
                        data: {"choices":[{"text":"Answer","finish_reason":"stop"}],"usage":{"prompt_tokens":{},"completion_tokens":0}}

                        data: [DONE]

                        """));
    }

    @ParameterizedTest(name = "{0}: input={1}")
    @MethodSource("unavailableCounterCases")
    void retainsAnswerAndReportedOutputZeroWhenInputCounterIsUnavailable(String provider, String inputJson) {
        Fixture fixture = respondingWith(provider, inputJson, "0", null);

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isFalse();
        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokensReported()).isTrue();
        assertThat(turn.outputTokens()).isZero();
        assertThat(fixture.usageRows()).containsExactly(List.of(0L, 0L, 0L, 0L));
        fixture.server().verify();
    }

    @ParameterizedTest(name = "{0}: output={1}")
    @MethodSource("unavailableCounterCases")
    void retainsAnswerAndReportedInputZeroWhenOutputCounterIsUnavailable(String provider, String outputJson) {
        Fixture fixture = respondingWith(provider, "0", outputJson, null);

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isTrue();
        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokensReported()).isFalse();
        assertThat(turn.outputTokens()).isZero();
        assertThat(fixture.usageRows()).containsExactly(List.of(0L, 0L, 0L, 0L));
        fixture.server().verify();
    }

    static Stream<Arguments> unavailableCounterCases() {
        return Stream.of("ollama-native", "ollama-text", "llamacpp").flatMap(provider ->
                Stream.concat(unavailableCounterValues(), Stream.of("2147483648"))
                        .map(json -> Arguments.of(provider, json)));
    }

    private static Stream<String> unavailableCounterValues() {
        return Stream.of(null, "null", "-1", "-2147483648", "-2147483649", "1.5", "7.0", "1e2",
                "\"7\"", "\"\"", "true", "false", "{}", "[]", "[1,{\"nested\":[2]}]", "{\"nested\":[{},1]}",
                "9223372036854775808",
                "9999999999999999999999999999999999999999999999999999999999999999999999999999999999");
    }

    @ParameterizedTest(name = "{0}: input={1}, output={2}")
    @MethodSource("validPrimaryCounterCases")
    void reportsOnlyTheCountersActuallyProvided(String provider, String inputJson, String outputJson,
                                                boolean inputReported, boolean outputReported, long input, long output) {
        Fixture fixture = respondingWith(provider, inputJson, outputJson, null);

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isEqualTo(inputReported);
        assertThat(turn.inputTokens()).isEqualTo(input);
        assertThat(turn.outputTokensReported()).isEqualTo(outputReported);
        assertThat(turn.outputTokens()).isEqualTo(output);
        assertThat(fixture.usageRows()).containsExactly(List.of(input, output, 0L, 0L));
        fixture.server().verify();
    }

    static Stream<Arguments> validPrimaryCounterCases() {
        return Stream.of("ollama-native", "ollama-text", "llamacpp").flatMap(provider -> Stream.of(
                Arguments.of(provider, "0", "0", true, true, 0L, 0L),
                Arguments.of(provider, "7", "11", true, true, 7L, 11L),
                Arguments.of(provider, "2147483647", "2147483647", true, true, 2147483647L, 2147483647L),
                Arguments.of(provider, "7", null, true, false, 7L, 0L),
                Arguments.of(provider, null, "11", false, true, 0L, 11L)));
    }

    @ParameterizedTest(name = "{0}: input={1}, output={2}")
    @MethodSource("partialTextUsageCases")
    void publicChatAuditsTheIndependentlyAvailableCounter(String provider, String inputJson,
                                                         String outputJson, long input, long output) {
        Fixture fixture = respondingWith(provider, inputJson, outputJson, null);

        String answer = fixture.client().chat(List.of(), "Question", "System", null);

        assertThat(answer).isEqualTo("Answer");
        assertThat(fixture.usageRows()).containsExactly(List.of(input, output, 0L, 0L));
        fixture.server().verify();
    }

    static Stream<Arguments> partialTextUsageCases() {
        return Stream.of("ollama-text", "llamacpp").flatMap(provider -> Stream.concat(Stream.of(
                Arguments.of(provider, "7", null, 7L, 0L),
                Arguments.of(provider, null, "11", 0L, 11L),
                Arguments.of(provider, "0", null, 0L, 0L),
                Arguments.of(provider, null, "0", 0L, 0L)),
                Stream.concat(unavailableCounterValues(), Stream.of("2147483648"))
                        .flatMap(json -> Stream.of(
                                Arguments.of(provider, json, "11", 0L, 11L),
                                Arguments.of(provider, "7", json, 7L, 0L)))));
    }

    @ParameterizedTest(name = "{0}: cached={1}")
    @MethodSource("invalidCacheCounterCases")
    void invalidCachedMetadataDoesNotDiscardTheTurnOrPrimaryUsage(String provider, String cachedJson) {
        Fixture fixture = respondingWith(provider, "7", "11", cachedJson);

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isTrue();
        assertThat(turn.inputTokens()).isEqualTo(7L);
        assertThat(turn.outputTokensReported()).isTrue();
        assertThat(turn.outputTokens()).isEqualTo(11L);
        assertThat(fixture.usageRows()).containsExactly(List.of(7L, 11L, 0L, 0L));
        fixture.server().verify();
    }

    @ParameterizedTest(name = "{0}: cached={1}")
    @MethodSource("invalidTextCacheCounterCases")
    void publicChatRetainsTheAnswerAndPrimaryAuditWithInvalidCachedMetadata(String provider, String cachedJson) {
        Fixture fixture = respondingWith(provider, "7", "11", cachedJson);

        String answer = fixture.client().chat(List.of(), "Question", "System", null);

        assertThat(answer).isEqualTo("Answer");
        assertThat(fixture.usageRows()).containsExactly(List.of(7L, 11L, 0L, 0L));
        fixture.server().verify();
    }

    static Stream<Arguments> invalidCacheCounterCases() {
        return invalidCacheCounterCasesFor("ollama-native", "ollama-text", "llamacpp");
    }

    static Stream<Arguments> invalidTextCacheCounterCases() {
        return invalidCacheCounterCasesFor("ollama-text", "llamacpp");
    }

    private static Stream<Arguments> invalidCacheCounterCasesFor(String... providers) {
        return Stream.of(providers).flatMap(provider ->
                Stream.concat(unavailableCounterValues(), provider.startsWith("ollama")
                                ? Stream.of("2147483648") : Stream.empty())
                        .map(json -> Arguments.of(provider, json)));
    }

    @ParameterizedTest(name = "{0}: cached={1}")
    @MethodSource("validCacheCounterCases")
    void keepsCachedUsageSeparateFromTheReportedPrompt(String provider, String cachedJson, long cacheRead) {
        Fixture fixture = respondingWith(provider, "7", "11", cachedJson);

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isTrue();
        assertThat(turn.inputTokens()).isEqualTo(7L);
        assertThat(turn.outputTokensReported()).isTrue();
        assertThat(turn.outputTokens()).isEqualTo(11L);
        assertThat(fixture.usageRows()).containsExactly(List.of(7L, 11L, 0L, cacheRead));
        fixture.server().verify();
    }

    @ParameterizedTest(name = "{0}: cached={1}")
    @MethodSource("validCacheCounterCases")
    void publicChatKeepsCachedUsageWithPartialPrimaryUsage(String provider, String cachedJson, long cacheRead) {
        Fixture fixture = respondingWith(provider, "7", null, cachedJson);

        String answer = fixture.client().chat(List.of(), "Question", "System", null);

        assertThat(answer).isEqualTo("Answer");
        assertThat(fixture.usageRows()).containsExactly(List.of(7L, 0L, 0L, cacheRead));
        fixture.server().verify();
    }

    static Stream<Arguments> validCacheCounterCases() {
        return Stream.concat(Stream.of("ollama-native", "ollama-text", "llamacpp").flatMap(provider -> Stream.of(
                        Arguments.of(provider, "0", 0L), Arguments.of(provider, "3", 3L),
                        Arguments.of(provider, "2147483647", 2147483647L))),
                Stream.of(Arguments.of("llamacpp", "2147483648", 2147483648L),
                        Arguments.of("llamacpp", "9223372036854775807", 9223372036854775807L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cacheOnlyCases")
    void cachedMetadataDoesNotInventPrimaryCounterAvailability(String provider, List<List<Long>> expectedAudit) {
        Fixture fixture = respondingWith(provider, null, null, "3");

        ChatTurn turn = fixture.client().chatWithTools(List.of(), "Question", tools(provider), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turn.inputTokensReported()).isFalse();
        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokensReported()).isFalse();
        assertThat(turn.outputTokens()).isZero();
        assertThat(fixture.usageRows()).isEqualTo(expectedAudit);
        fixture.server().verify();
    }

    static Stream<Arguments> cacheOnlyCases() {
        return Stream.of(Arguments.of("ollama-native", List.of()),
                Arguments.of("ollama-text", List.of()),
                Arguments.of("llamacpp", List.of(List.of(0L, 0L, 0L, 3L))));
    }

    private static List<ToolDescriptor> tools(String provider) {
        return "ollama-native".equals(provider)
                ? List.of(new ToolDescriptor("lookup", "Read context", null)) : List.of();
    }

    private static Fixture respondingWith(String provider, String inputJson, String outputJson, String cachedJson) {
        boolean ollama = provider.startsWith("ollama");
        String endpoint = ollama ? "/api/chat" : "/v1/completions";
        String counters = field(ollama ? "prompt_eval_count" : "prompt_tokens", inputJson)
                + field(ollama ? "eval_count" : "completion_tokens", outputJson);
        String response;
        if (ollama) {
            response = "{" + counters + field("prompt_eval_cached_count", cachedJson) + """
                    "model":"provider-model","message":{"role":"assistant","content":"Answer"},
                    "done":true,"done_reason":"stop"}
                    """.replace("\n", "") + "\n";
        } else {
            String details = cachedJson == null ? "\"prompt_tokens_details\":null"
                    : "\"prompt_tokens_details\":{\"cached_tokens\":" + cachedJson + "}";
            response = """
                    data: {"model":"provider-model","choices":[{"text":"Answer","finish_reason":"stop"}]}

                    data: {"model":"provider-model","choices":[],"usage":{%s%s}}

                    data: [DONE]

                    """.formatted(counters, details);
        }
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example" + endpoint))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response, ollama ? MediaType.APPLICATION_NDJSON : MediaType.TEXT_EVENT_STREAM));
        AbstractAiClient client = ollama ? new OllamaClient(http.build(), "model", 32, true)
                : new LlamaCppClient(http.build(), "model", 32);
        List<List<Long>> usageRows = new ArrayList<>();
        client.setAuditRecorder(new AiAuditRecorder() {
            @Override
            public void recordUsage(long inputTokens, long outputTokens,
                                    long cacheCreationInputTokens, long cacheReadInputTokens,
                                    String rawRequest, String rawResponse) {
                usageRows.add(List.of(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
            }

        });
        return new Fixture(client, server, usageRows);
    }

    private static String field(String name, String json) {
        return json == null ? "" : "\"" + name + "\":" + json + ",";
    }

    private record Fixture(AbstractAiClient client, MockRestServiceServer server, List<List<Long>> usageRows) {
    }
}
