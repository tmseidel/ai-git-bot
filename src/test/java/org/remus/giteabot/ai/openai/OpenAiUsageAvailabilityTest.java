package org.remus.giteabot.ai.openai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.agent.loop.TokenUsageTracker;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiUsageAvailabilityTest {
    @Test
    void invalidWireCounterIsUnavailableInTheTurnAndOptInAuditPayload() {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"Answer"}}],
                         "usage":{"prompt_tokens":-1,"completion_tokens":3}}
                        """, MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(http.build(), "model", 32, true, OpenAiFlavor.STANDARD, null);
        var recorder = new CapturingRecorder();
        client.setAuditRecorder(recorder);
        var properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(true);
        client.setUsageProperties(properties);
        var tool = new ToolDescriptor("search", "Search", AgentJackson.mapper().createObjectNode());

        var turn = client.chatWithTools(List.of(), "Review", List.of(tool), "sys", null, null);

        assertThat(turn.inputTokensReported()).isFalse();
        assertThat(turn.inputTokens()).isZero();
        assertThat(recorder.usage).containsExactly(new RecordedUsage(0, 3));
        var rawUsage = AgentJackson.mapper().readTree(recorder.rawResponse).get("usage");
        assertThat(rawUsage.get("prompt_tokens").isNull()).isTrue();
        assertThat(rawUsage.get("completion_tokens").asInt()).isEqualTo(3);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("auditUsageCases")
    void auditUsesNonNegativeLegacyPlaceholdersWithoutInferringAvailability(
            String operation, String usage, Long expectedInput, Long expectedOutput) {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"Answer"}}],
                         "usage":%s}
                        """.formatted(usage), MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(http.build(), "model", 32, true, OpenAiFlavor.STANDARD, null);
        var recorder = new CapturingRecorder();
        client.setAuditRecorder(recorder);

        switch (operation) {
            case "native" -> client.chatWithTools(List.of(), "Review", List.of(
                    new ToolDescriptor("search", "Search", AgentJackson.mapper().createObjectNode())), "sys", null, null);
            case "fallback" -> client.chatWithTools(List.of(), "Review", List.of(), "sys", null, null);
            case "chat" -> client.chat(List.of(), "Question", "sys", null);
            case "review" -> client.submitReviewPrompt("sys", null, "Review");
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        }

        if (expectedInput == null) {
            assertThat(recorder.usage).isEmpty();
        } else {
            assertThat(recorder.usage).containsExactly(new RecordedUsage(expectedInput, expectedOutput));
        }
        server.verify();
    }

    private static Stream<Arguments> auditUsageCases() {
        return Stream.of("native", "fallback", "chat", "review").flatMap(operation -> Stream.of(
                Arguments.of(operation, "null", null, null),
                Arguments.of(operation, "{}", 0L, 0L),
                Arguments.of(operation, "{\"prompt_tokens\":null,\"completion_tokens\":null}", 0L, 0L),
                Arguments.of(operation, "{\"prompt_tokens\":0,\"completion_tokens\":0}", 0L, 0L),
                Arguments.of(operation, "{\"prompt_tokens\":0}", 0L, 0L),
                Arguments.of(operation, "{\"completion_tokens\":0}", 0L, 0L),
                Arguments.of(operation, "{\"prompt_tokens\":12}", 12L, 0L),
                Arguments.of(operation, "{\"completion_tokens\":3}", 0L, 3L),
                Arguments.of(operation, "{\"prompt_tokens\":12,\"completion_tokens\":3}", 12L, 3L),
                Arguments.of(operation, "{\"prompt_tokens\":-1,\"completion_tokens\":3}", 0L, 3L),
                Arguments.of(operation, "{\"prompt_tokens\":12,\"completion_tokens\":-1}", 12L, 0L),
                Arguments.of(operation, "{\"prompt_tokens\":-1,\"completion_tokens\":-1}", 0L, 0L)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            null | false | false | 100 | 2
            {} | false | false | 100 | 2
            {"prompt_tokens":0,"completion_tokens":0} | true | true | 0 | 0
            {"prompt_tokens":0} | true | false | 0 | 2
            {"completion_tokens":0} | false | true | 100 | 0
            {"prompt_tokens":12,"completion_tokens":3} | true | true | 12 | 3
            {"prompt_tokens":-1,"completion_tokens":3} | false | true | 100 | 3
            {"prompt_tokens":12,"completion_tokens":-1} | true | false | 12 | 2
            {"prompt_tokens":-1,"completion_tokens":-1} | false | false | 100 | 2
            {"prompt_tokens":null,"completion_tokens":null} | false | false | 100 | 2
            {"prompt_tokens":null,"completion_tokens":0} | false | true | 100 | 0
            """)
    void wirePresenceSurvivesTheTurnAndControlsOnlyTheMissingCounterEstimate(
            String usage, boolean inputReported, boolean outputReported, long input, long output) {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"12345678"}}],"usage":%s}
                        """.formatted(usage), MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(http.build(), "model", 32, true, OpenAiFlavor.STANDARD, null);
        var turn = client.chatWithTools(List.of(), "Review", List.of(), "sys", null, null);
        assertThat(turn.inputTokensReported()).isEqualTo(inputReported);
        assertThat(turn.outputTokensReported()).isEqualTo(outputReported);
        assertThat(turn.hasReportedUsage()).isEqualTo(inputReported && outputReported);
        var session = new AgentSession("owner", "repo", 1L, "test");
        new TokenUsageTracker(200_000, 0.7).record(session, turn, 400);
        assertThat(session.getTotalInputTokens()).isEqualTo(input);
        assertThat(session.getTotalOutputTokens()).isEqualTo(output);
        server.verify();
    }

    private record RecordedUsage(long inputTokens, long outputTokens) {}

    private static final class CapturingRecorder implements AiAuditRecorder {
        private final List<RecordedUsage> usage = new ArrayList<>();
        private String rawResponse;

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
            usage.add(new RecordedUsage(inputTokens, outputTokens));
            this.rawResponse = rawResponse;
        }

        @Override
        public void recordError(Throwable error) {
            throw new AssertionError("Unexpected provider error", error);
        }
    }
}
