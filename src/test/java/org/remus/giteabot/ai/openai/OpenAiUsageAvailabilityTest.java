package org.remus.giteabot.ai.openai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.agent.loop.TokenUsageTracker;
import org.remus.giteabot.agent.session.AgentSession;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiUsageAvailabilityTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            null | false | false | 100 | 2
            {} | false | false | 100 | 2
            {"prompt_tokens":0,"completion_tokens":0} | true | true | 0 | 0
            {"prompt_tokens":0} | true | false | 0 | 2
            {"completion_tokens":0} | false | true | 100 | 0
            {"prompt_tokens":12,"completion_tokens":3} | true | true | 12 | 3
            {"prompt_tokens":-1,"completion_tokens":3} | false | true | 100 | 3
            """)
    void wirePresenceSurvivesTheTurnAndControlsOnlyTheMissingCounterEstimate(
            String usage, boolean inputReported, boolean outputReported, long input, long output) {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"12345678"}}],"usage":%s}
                        """.formatted(usage), MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(http.build(), "model", 32, true, OpenAiFlavor.STANDARD);
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
}
