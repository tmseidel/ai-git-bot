package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.remus.giteabot.ai.openrouter.OpenRouterClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NativeStopReasonTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void tokenLimitIsNotOverriddenByParsableToolCalls(Provider provider) {
        ChatTurn turn = send(provider, provider.withTools(provider.limitReason()));

        assertThat(turn.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(turn.toolCalls()).extracting(ToolCall::name).containsExactly("lookup");
        assertThat(turn.inputTokens()).isEqualTo(100);
        assertThat(turn.outputTokens()).isEqualTo(32);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void completedToolTurnRemainsUsable(Provider provider) {
        ChatTurn turn = send(provider, provider.withTools(provider.completedReason()));

        assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(turn.toolCalls()).extracting(ToolCall::name).containsExactly("lookup");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void unknownStopReasonIsNotPromotedToToolUse(Provider provider) {
        ChatTurn turn = send(provider, provider.withTools("unrecognized-stop"));

        assertThat(turn.stopReason()).isEqualTo(StopReason.OTHER);
        assertThat(turn.toolCalls()).extracting(ToolCall::name).containsExactly("lookup");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void missingStopReasonIsNotPromotedToToolUse(Provider provider) {
        assertThat(send(provider, provider.withTools(null)).stopReason()).isEqualTo(StopReason.OTHER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void emptyHttpBodyIsNotACompletedTurn(Provider provider) {
        ChatTurn turn = send(provider, "");

        assertThat(turn.stopReason()).isEqualTo(StopReason.OTHER);
        assertThat(turn.assistantText()).isEmpty();
        assertThat(turn.toolCalls()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void emptyJsonResponseIsNotACompletedTurn(Provider provider) {
        ChatTurn turn = send(provider, "{}");

        assertThat(turn.stopReason()).isEqualTo(StopReason.OTHER);
        assertThat(turn.assistantText()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void emptyLimitedTurnRetainsItsStopReasonAndUsage(Provider provider) {
        ChatTurn turn = send(provider, provider.emptyLimitedResponse());

        assertThat(turn.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(turn.assistantText()).isEmpty();
        assertThat(turn.inputTokens()).isEqualTo(100);
        assertThat(turn.outputTokens()).isEqualTo(32);
    }

    private ChatTurn send(Provider provider, String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.example" + provider.path()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response.isEmpty() ? "" : response.replace("\n", "") + "\n",
                        MediaType.APPLICATION_JSON));

        ChatTurn turn = provider.client().apply(builder.build()).chatWithTools(
                List.of(), "Review this change", List.of(new ToolDescriptor("lookup", "Read context", null)),
                "You are a reviewer.", null, null);

        server.verify();
        return turn;
    }

    private static Stream<Provider> providers() {
        return Stream.of(
                new Provider("OpenAI-compatible", "/v1/chat/completions",
                        http -> new OpenAiClient(http, "test-model", 32, true, OpenAiFlavor.STANDARD),
                        "length", "stop", """
                        {"choices":[{"finish_reason":%s,"message":{"role":"assistant","content":"",
                          "tool_calls":[{"id":"call-1","type":"function",
                            "function":{"name":"lookup","arguments":"{}"}}]}}],
                         "usage":{"prompt_tokens":100,"completion_tokens":32,"total_tokens":132}}
                        """, """
                        {"choices":[{"finish_reason":"length","message":{"content":""}}],
                         "usage":{"prompt_tokens":100,"completion_tokens":32,"total_tokens":132}}
                        """),
                new Provider("OpenRouter", "/v1/chat/completions",
                        http -> new OpenRouterClient(http, "test-model", 32, true),
                        "length", "stop", """
                        {"choices":[{"finish_reason":%s,"message":{"role":"assistant","content":"",
                          "tool_calls":[{"id":"call-1","type":"function",
                            "function":{"name":"lookup","arguments":"{}"}}]}}],
                         "usage":{"prompt_tokens":100,"completion_tokens":32,"total_tokens":132}}
                        """, """
                        {"choices":[{"finish_reason":"length","message":{"content":""}}],
                         "usage":{"prompt_tokens":100,"completion_tokens":32,"total_tokens":132}}
                        """),
                new Provider("Anthropic", "/v1/messages",
                        http -> new AnthropicAiClient(http, "test-model", 32, true, false, false, "high"),
                        "max_tokens", "end_turn", """
                        {"stop_reason":%s,"content":[{"type":"tool_use","id":"call-1",
                          "name":"lookup","input":{}}],"usage":{"input_tokens":100,"output_tokens":32}}
                        """, """
                        {"stop_reason":"max_tokens","content":[],"usage":{"input_tokens":100,"output_tokens":32}}
                        """),
                new Provider("Google", "/v1beta/models/test-model:generateContent",
                        http -> new GoogleAiClient(http, "test-model", 32, true),
                        "MAX_TOKENS", "STOP", """
                        {"candidates":[{"finishReason":%s,"content":{"parts":[
                          {"functionCall":{"name":"lookup","args":{}}}]}}],
                         "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":32,"totalTokenCount":132}}
                        """, """
                        {"candidates":[{"finishReason":"MAX_TOKENS"}],
                         "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":32,"totalTokenCount":132}}
                        """),
                new Provider("Ollama", "/api/chat",
                        http -> new OllamaClient(http, "test-model", 32, true),
                        "length", "stop", """
                        {"done":true,"done_reason":%s,"message":{"role":"assistant","content":"",
                          "tool_calls":[{"function":{"name":"lookup","arguments":{}}}]},
                         "prompt_eval_count":100,"eval_count":32}
                        """, """
                        {"done":true,"done_reason":"length","message":{"content":""},
                         "prompt_eval_count":100,"eval_count":32}
                        """));
    }

    private record Provider(String name, String path, Function<RestClient, AiClient> client,
                            String limitReason, String completedReason, String toolResponse,
                            String emptyLimitedResponse) {
        String withTools(String reason) {
            return toolResponse.formatted(reason == null ? "null" : "\"" + reason + "\"");
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
