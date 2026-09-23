package org.remus.giteabot.ai.ollama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaClientTest {

    private OllamaClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new OllamaClient(restClient, "llama3.2:1b", 1024, true);
    }

    @Test
    void isPromptTooLongError_detectsTooLongError() {
        OllamaClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"input is too long\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsContextLengthError() {
        OllamaClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"exceeds context length\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        OllamaClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void supportsNativeTools_defaultsToTrue() {
        assertTrue(createClient().supportsNativeTools());
    }

    @Test
    void supportsNativeTools_canBeDisabled() {
        OllamaClient client = new OllamaClient(mock(RestClient.class),
                "llama3.2:1b", 1024, false);
        assertFalse(client.supportsNativeTools());
    }

    @ParameterizedTest
    @CsvSource({"review, 128", "chat-default, 128", "chat-override, 8", "native, 8", "text-turn, 8", "forced-legacy, 8"})
    void completionLimitUsesOllamaWireName(String path, int expectedLimit) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.example/api/chat"))
                .andExpect(jsonPath("$.options.num_predict").value(expectedLimit))
                .andExpect(jsonPath("$.options.numPredict").doesNotExist())
                .andRespond(withSuccess("""
                        {"message":{"content":"OK"},"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":1}
                        """, MediaType.APPLICATION_NDJSON));
        OllamaClient client = new OllamaClient(builder.build(), "test-model", 128, !"forced-legacy".equals(path));

        switch (path) {
            case "review" -> client.submitReviewPrompt("sys", null, "Review");
            case "chat-default" -> client.chat(List.of(), "Review", "sys", null);
            case "chat-override" -> client.chat(List.of(), "Review", "sys", null, 8);
            case "text-turn" -> client.chatWithTools(List.of(), "Review", List.of(), "sys", null, 8);
            default -> client.chatWithTools(List.of(), "Review",
                    List.of(new ToolDescriptor("cat", "Read a file", null)), "sys", null, 8);
        }

        server.verify();
    }

}
