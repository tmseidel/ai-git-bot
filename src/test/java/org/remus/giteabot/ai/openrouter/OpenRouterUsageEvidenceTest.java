package org.remus.giteabot.ai.openrouter;

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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterUsageEvidenceTest {
    @Test
    void explicitZeroIsReportedIndependentlyFromMissingUsage() {
        var response = AgentJackson.mapper().readValue(
                answer("{\"prompt_tokens\":0,\"completion_tokens\":0}"), OpenRouterResponse.class);

        assertTurn(response.toTurn(), 0L, 0L);
    }

    @ParameterizedTest
    @MethodSource("tokenCounters")
    void onlyNonNegativeLongIntegersAreReportedWithoutLosingTheCompanionCounter(
            String field, String json, Long expected) {
        String companion = field.equals("prompt_tokens") ? "\"completion_tokens\":3" : "\"prompt_tokens\":12";
        var response = AgentJackson.mapper().readValue(answer(json == null ? "{" + companion + "}" :
                "{\"" + field + "\":" + json + "," + companion + "}"), OpenRouterResponse.class);

        assertTurn(response.toTurn(), field.equals("prompt_tokens") ? expected : Long.valueOf(12),
                field.equals("completion_tokens") ? expected : Long.valueOf(3));
    }

    @ParameterizedTest
    @MethodSource("cacheCounters")
    void cacheCountersKeepTheirLongRangeAndDistinguishZeroFromUnavailable(
            String field, String json, Long expected) {
        String companion = field.equals("cached_tokens") ? "\"cache_write_tokens\":5" : "\"cached_tokens\":7";
        var response = AgentJackson.mapper().readValue(answer("""
                {"prompt_tokens":12,"completion_tokens":3,"prompt_tokens_details":{%s%s}}
                """.formatted(companion, member(field, json))), OpenRouterResponse.class);

        assertTurn(response.toTurn(), 12L, 3L);
        var details = response.usage().promptTokensDetails();
        assertThat(details.cacheWriteTokens()).isEqualTo(field.equals("cache_write_tokens") ? expected : Long.valueOf(5));
        assertThat(details.cachedTokens()).isEqualTo(field.equals("cached_tokens") ? expected : Long.valueOf(7));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5, 2147483648L})
    void malformedCountersPreserveNativeCallsCompanionCountsAndOptInAudit(long cacheWrite) {
        var fixture = new Fixture(toolAnswer("""
                {"prompt_tokens":{"secret-sentinel":[1]},"completion_tokens":3,
                 "prompt_tokens_details":{"cached_tokens":[],"cache_write_tokens":%d}}
                """.formatted(cacheWrite)));
        var properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(true);
        fixture.client.setUsageProperties(properties);

        var turn = fixture.client.chatWithTools(List.of(), "Question",
                List.of(new ToolDescriptor("lookup", "Read fixture", null)), "sys", null, null);

        assertTurn(turn, null, 3L);
        assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().getFirst().name()).isEqualTo("lookup");
        assertThat(turn.toolCalls().getFirst().args().get("fixture").intValue()).isEqualTo(1);
        assertThat(turn.reasoningDetails().getFirst().get("data").asString()).isEqualTo("private-reasoning-sentinel");
        assertThat(turn.toString()).doesNotContain("private-reasoning-sentinel");
        assertThat(AgentJackson.mapper().writeValueAsString(turn.toAssistantMessage()))
                .doesNotContain("private-reasoning-sentinel");
        assertThat(fixture.recorder.usage).containsExactly(new RecordedUsage(0, 3, cacheWrite, 0));
        var usage = AgentJackson.mapper().readTree(fixture.recorder.rawResponse).get("usage");
        assertThat(usage.get("prompt_tokens").isNull()).isTrue();
        assertThat(usage.get("prompt_tokens_details").get("cached_tokens").isNull()).isTrue();
        assertThat(usage.get("prompt_tokens_details").get("cache_write_tokens").longValue()).isEqualTo(cacheWrite);
        assertThat(fixture.recorder.rawRequest).doesNotContain("test-secret-sentinel");
        assertThat(fixture.recorder.rawResponse).doesNotContain("secret-sentinel");
        fixture.server.verify();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void optionalContainersPreserveParsedTextAndCompanionUsage(String field, String json, boolean object) {
        String usage = field.equals("usage") ? json :
                "{\"prompt_tokens\":0,\"completion_tokens\":3" + member(field, json) + "}";
        var response = AgentJackson.mapper().readValue(answer(usage), OpenRouterResponse.class);

        assertTurn(response.toTurn(), field.equals("usage") ? null : 0L, field.equals("usage") ? null : 3L);
        if (field.equals("usage")) {
            assertThat(response.usage() != null).isEqualTo(object);
        } else {
            assertThat(response.usage().promptTokensDetails() != null).isEqualTo(object);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "chat"})
    void malformedUsageCannotDiscardClientAnswers(String operation) {
        var fixture = new Fixture(answer("[]"));

        if (operation.equals("chat")) {
            assertThat(fixture.client.chat(List.of(), "Question", "sys", null)).isEqualTo("Answer");
        } else {
            assertTurn(fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null), null, null);
        }
        assertThat(fixture.recorder.usage).isEmpty();
        assertThat(fixture.recorder.rawRequest).isNull();
        assertThat(fixture.recorder.rawResponse).isNull();
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Answer\"}}],\"usage\":[{\"secret-sentinel\":]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Answer\"}}],\"usage\":{\"prompt_tokens_details\":["
    })
    void syntacticallyInvalidResponsesStillFailWithoutLeakingTheirPayload(String response) {
        var fixture = new Fixture(response);

        assertThatThrownBy(() -> fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null))
                .isInstanceOf(RestClientException.class).hasMessage("OpenRouter request failed")
                .hasMessageNotContaining("secret-sentinel").hasNoCause();
        assertThat(fixture.recorder.usage).isEmpty();
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"error\",\"message\":{\"content\":\"Answer\"},\"error\":{\"message\":\"secret-sentinel\"}}],\"usage\":[]}",
            "{\"error\":{\"message\":\"secret-sentinel\"},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Answer\"}}],\"usage\":true}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"Answer\",\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"secret-sentinel\"}}]}}],\"usage\":[]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"lookup\",\"arguments\":{\"secret-sentinel\":1}}}]}}],\"usage\":[]}"
    })
    void forgivingUsageNeverMakesProviderErrorsOrMalformedToolsSuccessful(String response) {
        var fixture = new Fixture(response);

        assertThatThrownBy(() -> fixture.client.chatWithTools(List.of(), "Question", List.of(), "sys", null, null))
                .isInstanceOf(RestClientException.class).hasMessageNotContaining("secret-sentinel").hasNoCause();
        assertThat(fixture.recorder.usage).isEmpty();
        fixture.server.verify();
    }

    private static Stream<Arguments> containers() {
        return Stream.of("usage", "prompt_tokens_details").flatMap(field ->
                Stream.of(null, "null", "{}", "0", "12", "0.5", "true", "false", "\"\"",
                                "\"secret-sentinel\"", "[]", "[{\"prompt_tokens\":12,\"cached_tokens\":7}]")
                        .map(json -> Arguments.of(field, json, "{}".equals(json))));
    }

    private static String toolAnswer(String usage) {
        return """
                {%s"choices":[{"finish_reason":"tool_calls","message":{"content":"Answer",
                  "reasoning_details":[{"type":"reasoning.encrypted","data":"private-reasoning-sentinel"}],
                  "tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":"{\\"fixture\\":1}"}}]}}]}
                """.formatted(usage == null ? "" : "\"usage\":" + usage + ",");
    }

    private static Stream<Arguments> tokenCounters() {
        return Stream.of("prompt_tokens", "completion_tokens").flatMap(field ->
                counts().map(count -> Arguments.of(field, count.json(), count.expected())));
    }

    private static Stream<Arguments> cacheCounters() {
        return Stream.of("cached_tokens", "cache_write_tokens").flatMap(field ->
                counts().map(count -> Arguments.of(field, count.json(), count.expected())));
    }

    private static Stream<Count> counts() {
        return Stream.of(new Count(null, null), new Count("null", null), new Count("0", 0L),
                new Count("12", 12L), new Count("2147483648", 2147483648L),
                new Count("9223372036854775807", 9223372036854775807L),
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
        private final CapturingRecorder recorder = new CapturingRecorder();
        private final OpenRouterClient client;

        private Fixture(String response) {
            var http = RestClient.builder().baseUrl("https://openrouter.ai/api")
                    .defaultHeader("Authorization", "Bearer test-secret-sentinel");
            server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(header("Authorization", "Bearer test-secret-sentinel"))
                    .andExpect(jsonPath("$.max_tokens").value(32))
                    .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
                    .andExpect(jsonPath("$.provider.require_parameters").value(true))
                    .andExpect(jsonPath("$.provider.allow_fallbacks").value(false))
                    .andExpect(jsonPath("$.provider.data_collection").value("deny"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            client = new OpenRouterClient(http.build(), "author/model", 32, true, "AI Git Bot");
            client.setAuditRecorder(recorder);
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
