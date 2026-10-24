package org.remus.giteabot.ai.openai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiUsageEvidenceTest {
    @ParameterizedTest
    @MethodSource("counters")
    void strictOptionalCounterPreservesTheAnswerAndEveryCompanionCounter(
            String field, String json, Long expected) {
        String usage = switch (field) {
            case "prompt_tokens" -> "{\"completion_tokens\":3,\"total_tokens\":15," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":5}" + member(field, json) + "}";
            case "completion_tokens" -> "{\"prompt_tokens\":12,\"total_tokens\":15," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":5}" + member(field, json) + "}";
            case "total_tokens" -> "{\"prompt_tokens\":12,\"completion_tokens\":3," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":5}" + member(field, json) + "}";
            case "cached_tokens" -> "{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15," +
                    "\"prompt_tokens_details\":{\"cache_write_tokens\":5" + member(field, json) + "}}";
            case "cache_write_tokens" -> "{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":7" + member(field, json) + "}}";
            default -> throw new AssertionError(field);
        };
        var fixture = new Fixture(answer(usage));
        var properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(true);
        fixture.client.setUsageProperties(properties);

        ChatTurn turn = fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null);

        Long input = field.equals("prompt_tokens") ? expected : Long.valueOf(12);
        Long output = field.equals("completion_tokens") ? expected : Long.valueOf(3);
        assertTurn(turn, input, output);
        assertThat(fixture.recorder.usage).containsExactly(new RecordedUsage(orZero(input), orZero(output),
                field.equals("cache_write_tokens") ? orZero(expected) : 5,
                field.equals("cached_tokens") ? orZero(expected) : 7));
        var rawUsage = AgentJackson.mapper().readTree(fixture.recorder.rawResponse).get("usage");
        var counter = field.equals("cached_tokens") || field.equals("cache_write_tokens")
                ? rawUsage.get("prompt_tokens_details").get(field) : rawUsage.get(field);
        if (expected == null) assertThat(counter.isNull()).isTrue();
        else assertThat(counter.longValue()).isEqualTo(expected);
        assertThat(fixture.recorder.rawRequest).doesNotContain("test-secret-sentinel");
        assertThat(fixture.recorder.rawResponse).doesNotContain("secret-sentinel");
        fixture.verify();
    }

    @Test
    void malformedCountersBeforeTheToolAnswerPreserveValidNativeCallsAndCompanionCounts() {
        var fixture = new Fixture(toolAnswer("""
                {"prompt_tokens":{"secret-sentinel":[1]},"completion_tokens":3,"total_tokens":[],
                 "prompt_tokens_details":{"cached_tokens":[],"cache_write_tokens":5}}
                """));

        var turn = fixture.client.chatWithTools(List.of(), "Question",
                List.of(new ToolDescriptor("lookup", "Read fixture", null)), "sys", null, null);

        assertTurn(turn, null, 3L);
        assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().getFirst().args().get("fixture").intValue()).isEqualTo(1);
        assertThat(fixture.recorder.usage).containsExactly(new RecordedUsage(0, 3, 5, 0));
        fixture.verify();
    }

    @Test
    void usageToleranceDoesNotTurnAnErrorFinishIntoAnEndTurn() {
        var fixture = new Fixture("""
                {"usage":[],"choices":[{"finish_reason":"error","message":{"content":"Answer"}}]}
                """);

        var turn = fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null);

        assertThat(turn.stopReason()).isEqualTo(StopReason.OTHER);
        assertThat(turn.hasReportedUsage()).isFalse();
        assertThat(turn.hasToolCalls()).isFalse();
        fixture.verify();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void optionalContainersCannotDiscardValidTextToolsOrCompanionUsage(
            String operation, String field, String json, boolean usageObject) {
        boolean nativeTools = operation.equals("native");
        String usage = field.equals("usage") ? json :
                "{\"prompt_tokens\":0,\"completion_tokens\":3" + member(field, json) + "}";
        var fixture = new Fixture(nativeTools ? toolAnswer(usage) : answer(usage));

        if (operation.equals("chat")) {
            assertThat(fixture.client.chat(List.of(), "Question", "sys", null)).isEqualTo("Answer");
        } else {
            var turn = fixture.client.chatWithTools(List.of(), "Question", nativeTools ?
                    List.of(new ToolDescriptor("lookup", "Read fixture", null)) : List.of(), "sys", null, null);
            assertTurn(turn, field.equals("usage") ? null : 0L, field.equals("usage") ? null : 3L);
            if (nativeTools) {
                assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
                assertThat(turn.toolCalls()).hasSize(1);
                assertThat(turn.toolCalls().getFirst().name()).isEqualTo("lookup");
                assertThat(turn.toolCalls().getFirst().args().get("fixture").intValue()).isEqualTo(1);
            }
        }
        if (field.equals("prompt_tokens_details")) {
            assertThat(fixture.recorder.usage).containsExactly(new RecordedUsage(0, 3, 0, 0));
        } else if (usageObject) {
            assertThat(fixture.recorder.usage).containsExactly(new RecordedUsage(0, 0, 0, 0));
        } else {
            assertThat(fixture.recorder.usage).isEmpty();
        }
        assertThat(fixture.recorder.rawRequest).isNull();
        assertThat(fixture.recorder.rawResponse).isNull();
        fixture.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Answer\"}}],\"usage\":[{\"secret-sentinel\":]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Answer\"}}],\"usage\":{\"prompt_tokens_details\":[",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"lookup\",\"arguments\":{\"secret-sentinel\":1}}}]}}],\"usage\":[]}"
    })
    void invalidJsonAndMalformedToolStructureStillFail(String response) {
        var fixture = new Fixture(response);

        assertThatThrownBy(() -> fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null))
                .isInstanceOf(RestClientException.class);
        assertThat(fixture.recorder.usage).isEmpty();
        fixture.verify();
    }

    private static Stream<Arguments> containers() {
        return Stream.of("typed", "native", "chat").flatMap(operation ->
                Stream.of("usage", "prompt_tokens_details").flatMap(field -> Stream.of(
                        new Container(null, false), new Container("null", false), new Container("{}", true),
                        new Container("0", false), new Container("12", false), new Container("0.5", false),
                        new Container("true", false), new Container("false", false), new Container("\"\"", false),
                        new Container("\"secret-sentinel\"", false), new Container("[]", false),
                        new Container("[{\"prompt_tokens\":12,\"cached_tokens\":7}]", false))
                        .map(container -> Arguments.of(operation, field, container.json(), container.usageObject()))));
    }

    private record Container(String json, boolean usageObject) {}

    private static String toolAnswer(String usage) {
        return """
                {%s"choices":[{"finish_reason":"tool_calls","message":{"content":"Answer",
                  "tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":"{\\"fixture\\":1}"}}]}}]}
                """.formatted(usage == null ? "" : "\"usage\":" + usage + ",");
    }

    private static Stream<Arguments> counters() {
        return Stream.of("prompt_tokens", "completion_tokens", "total_tokens", "cached_tokens", "cache_write_tokens")
                .flatMap(field -> counts(field.equals("cached_tokens") || field.equals("cache_write_tokens"))
                        .map(count -> Arguments.of(field, count.json(), count.expected())));
    }

    private static Stream<Count> counts(boolean longRange) {
        return Stream.of(new Count(null, null), new Count("null", null), new Count("0", 0L),
                new Count("12", 12L), new Count("2147483647", 2147483647L),
                new Count("2147483648", longRange ? 2147483648L : null),
                new Count("9223372036854775807", longRange ? 9223372036854775807L : null),
                new Count("-1", null), new Count("0.5", null), new Count("1.0", null),
                new Count("true", null), new Count("false", null), new Count("\"12\"", null),
                new Count("\"secret-sentinel\"", null), new Count("{}", null),
                new Count("{\"nested\":[1]}", null), new Count("[]", null), new Count("[12]", null),
                new Count("9223372036854775808", null));
    }

    private record Count(String json, Long expected) {}

    private static String member(String field, String json) {
        return json == null ? "" : ",\"" + field + "\":" + json;
    }

    private static String answer(String usage) {
        return """
                {%s"choices":[{"finish_reason":"stop","message":{"content":"Answer"}}]}
                """.formatted(usage == null ? "" : "\"usage\":" + usage + ",");
    }

    private static void assertTurn(ChatTurn turn, Long input, Long output) {
        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.inputTokens()).isEqualTo(orZero(input));
        assertThat(turn.outputTokens()).isEqualTo(orZero(output));
        assertThat(turn.inputTokensReported()).isEqualTo(input != null);
        assertThat(turn.outputTokensReported()).isEqualTo(output != null);
        assertThat(turn.hasReportedUsage()).isEqualTo(input != null && output != null);
    }

    private static long orZero(Long value) { return value == null ? 0 : value; }

    private static final class Fixture {
        private final MockRestServiceServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final CapturingRecorder recorder = new CapturingRecorder();
        private final OpenAiClient client;

        private Fixture(String response) {
            var http = RestClient.builder().baseUrl("https://provider.example")
                    .defaultHeader("Authorization", "Bearer test-secret-sentinel");
            server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://provider.example/v1/chat/completions"))
                    .andExpect(request -> requests.incrementAndGet())
                    .andExpect(header("Authorization", "Bearer test-secret-sentinel"))
                    .andExpect(jsonPath("$.max_completion_tokens").value(32))
                    .andExpect(jsonPath("$.max_tokens").doesNotExist())
                    .andExpect(jsonPath("$.provider").doesNotExist())
                    .andExpect(jsonPath("$.plugins").doesNotExist())
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            client = new OpenAiClient(http.build(), "model", 32, true, OpenAiFlavor.STANDARD);
            client.setAuditRecorder(recorder);
        }

        private void verify() {
            server.verify();
            assertThat(requests.get()).isEqualTo(1);
        }
    }

    private record RecordedUsage(long input, long output, long cacheWrite, long cacheRead) {}

    private static final class CapturingRecorder implements AiAuditRecorder {
        private final List<RecordedUsage> usage = new ArrayList<>();
        private String rawRequest;
        private String rawResponse;

        @Override public void recordUsage(long input, long output, long cacheWrite, long cacheRead,
                                          String rawRequest, String rawResponse) {
            usage.add(new RecordedUsage(input, output, cacheWrite, cacheRead));
            this.rawRequest = rawRequest;
            this.rawResponse = rawResponse;
        }

        @Override public void recordError(Throwable error) { throw new AssertionError(error); }
    }
}
