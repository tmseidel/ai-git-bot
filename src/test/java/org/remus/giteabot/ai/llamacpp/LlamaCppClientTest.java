package org.remus.giteabot.ai.llamacpp;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlamaCppClientTest {

    private LlamaCppClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new LlamaCppClient(restClient, "qwen2.5-coder-7b-instruct", 4096);
    }

    @Test
    void isPromptTooLongError_detectsContextLengthError() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"context length exceeded\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTooLongError() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"input is too long\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsMaximumContextError() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"maximum context size reached\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTokenLimitError() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"token limit exceeded\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsExceedsError() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"prompt exceeds model capacity\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_handlesNullBody() {
        LlamaCppClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                null,
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void typedTurnKeepsChatMlGrammarAndTokenOverrideWithoutAdvertisingTools() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.example/completion"))
                .andExpect(jsonPath("$.prompt").value("""
                        <|im_start|>system
                        Output JSON<|im_end|>
                        <|im_start|>user
                        Earlier question<|im_end|>
                        <|im_start|>assistant
                        Earlier response<|im_end|>
                        <|im_start|>user
                        Continue<|im_end|>
                        <|im_start|>assistant
                        """))
                .andExpect(jsonPath("$.n_predict").value(64))
                .andExpect(jsonPath("$.stream").value(true))
                .andExpect(jsonPath("$.grammar").isNotEmpty())
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("data: {\"content\":\"{}\",\"stop\":true,\"stop_type\":\"eos\"}\n",
                        MediaType.TEXT_EVENT_STREAM));
        LlamaCppClient client = new LlamaCppClient(builder.build(), "test-model", 128);
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("Earlier question").build(),
                AiMessage.builder().role("assistant").content("Earlier response").build());

        ChatTurn turn = client.chatWithTools(history, "Continue",
                List.of(new ToolDescriptor("lookup", "Read context", null)), "Output JSON", null, 64);

        assertFalse(client.supportsNativeTools());
        assertTrue(turn.toolCalls().isEmpty());
        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals("{}", turn.assistantText());
        server.verify();
    }

}
