package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProviderUsageAvailabilityTest {
    @ParameterizedTest
    @MethodSource("cases")
    void preservesCounterPresenceAndNormalizesDisjointCounters(String provider, String usage,
            boolean inputReported, boolean outputReported, long input, long output) {
        var builder = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(builder).build();
        String path;
        String response;
        Function<RestClient, AiClient> factory;
        switch (provider) {
            case "anthropic" -> {
                path = "/v1/messages";
                response = "{\"stop_reason\":\"end_turn\",\"content\":[],\"usage\":" + usage + "}";
                factory = http -> new AnthropicAiClient(http, "model", 32, true, false, false, "high");
            }
            case "google" -> {
                path = "/v1beta/models/model:generateContent";
                response = "{\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":" + usage + "}";
                factory = http -> new GoogleAiClient(http, "model", 32, true);
            }
            case "ollama" -> {
                path = "/api/chat";
                response = "{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"content\":\"\"}" + usage + "}\n";
                factory = http -> new OllamaClient(http, "model", 32, true);
            }
            case "llamacpp" -> {
                path = "/completion";
                response = "data: {\"stop\":true,\"stop_type\":\"eos\",\"content\":\"\"" + usage + "}\n\n";
                factory = http -> new LlamaCppClient(http, "model", 32);
            }
            default -> throw new AssertionError(provider);
        }
        server.expect(requestTo("https://provider.example" + path)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        ChatTurn turn = factory.apply(builder.build()).chatWithTools(List.of(), "Review", List.of(), "sys", null, null);
        assertThat(turn.inputTokensReported()).isEqualTo(inputReported);
        assertThat(turn.outputTokensReported()).isEqualTo(outputReported);
        assertThat(turn.inputTokens()).isEqualTo(input);
        assertThat(turn.outputTokens()).isEqualTo(output);
        server.verify();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("anthropic", "null", false, false, 0, 0),
                Arguments.of("anthropic", "{}", false, false, 0, 0),
                Arguments.of("anthropic", "{\"input_tokens\":0,\"output_tokens\":0}", true, true, 0, 0),
                Arguments.of("anthropic", "{\"input_tokens\":10,\"cache_creation_input_tokens\":20,\"cache_read_input_tokens\":30}", true, false, 60, 0),
                Arguments.of("anthropic", "{\"output_tokens\":0}", false, true, 0, 0),
                Arguments.of("google", "null", false, false, 0, 0),
                Arguments.of("google", "{}", false, false, 0, 0),
                Arguments.of("google", "{\"promptTokenCount\":0,\"candidatesTokenCount\":0}", true, true, 0, 0),
                Arguments.of("google", "{\"promptTokenCount\":100,\"cachedContentTokenCount\":40,\"candidatesTokenCount\":3,\"thoughtsTokenCount\":7}", true, true, 100, 10),
                Arguments.of("google", "{\"promptTokenCount\":0}", true, false, 0, 0),
                Arguments.of("ollama", "", false, false, 0, 0),
                Arguments.of("ollama", ",\"prompt_eval_count\":0,\"eval_count\":0", true, true, 0, 0),
                Arguments.of("ollama", ",\"eval_count\":0", false, true, 0, 0),
                Arguments.of("llamacpp", "", false, false, 0, 0),
                Arguments.of("llamacpp", ",\"tokens_evaluated\":0,\"tokens_predicted\":0", true, true, 0, 0),
                Arguments.of("llamacpp", ",\"tokens_predicted\":0", false, true, 0, 0));
    }
}
