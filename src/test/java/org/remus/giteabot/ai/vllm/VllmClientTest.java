package org.remus.giteabot.ai.vllm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VllmClientTest {

    @Test
    void sendReviewRequest_postsToChatCompletionsWithoutAuthorizationByDefault() throws Exception {
        RecordingServer server = startServer();
        try {
            VllmProviderMetadata metadata = new VllmProviderMetadata();
            RestClient restClient = metadata.buildRestClient(createIntegration(server.baseUrl()), null);
            VllmClient client = new VllmClient(restClient, "configured-model", 1024, 10, 2, 6);

            String response = client.sendReviewRequest("system prompt", "served-model", 123, "review this");

            assertEquals("vLLM response", response);
            assertEquals("POST", server.method);
            assertEquals("/v1/chat/completions", server.path);
            assertNull(server.authorization);
            assertTrue(server.body.contains("\"model\":\"served-model\""));
            assertTrue(server.body.contains("\"max_tokens\":123"));
            assertTrue(server.body.contains("\"role\":\"system\""));
            assertTrue(server.body.contains("\"role\":\"user\""));
        } finally {
            server.stop();
        }
    }

    @Test
    void sendChatRequest_postsToChatCompletionsWithOptionalBearerToken() throws Exception {
        RecordingServer server = startServer();
        try {
            VllmProviderMetadata metadata = new VllmProviderMetadata();
            RestClient restClient = metadata.buildRestClient(createIntegration(server.baseUrl()), "test-token");
            VllmClient client = new VllmClient(restClient, "served-model", 1024, 10, 2, 6);

            String response = client.sendChatRequest(
                    "system prompt",
                    "served-model",
                    321,
                    List.of(AiMessage.builder().role("user").content("hello").build()));

            assertEquals("vLLM response", response);
            assertEquals("POST", server.method);
            assertEquals("/v1/chat/completions", server.path);
            assertEquals("Bearer test-token", server.authorization);
            assertTrue(server.body.contains("\"max_tokens\":321"));
            assertTrue(server.body.contains("\"content\":\"hello\""));
        } finally {
            server.stop();
        }
    }

    @Test
    void isPromptTooLongError_detectsContextLengthError() {
        VllmClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"This model's maximum context length is 4096 tokens.\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTokenLimitError() {
        VllmClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"prompt exceeds token limit\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        VllmClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"model not found\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_handlesNullBody() {
        VllmClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                null,
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    private VllmClient createClient() {
        RestClient restClient = RestClient.builder().baseUrl("http://localhost:8000").build();
        return new VllmClient(restClient, "served-model", 1024, 10, 2, 6);
    }

    private AiIntegration createIntegration(String apiUrl) {
        AiIntegration integration = new AiIntegration();
        integration.setId(1L);
        integration.setName("test-vllm");
        integration.setProviderType("vllm");
        integration.setApiUrl(apiUrl);
        integration.setModel("served-model");
        integration.setMaxTokens(4096);
        integration.setMaxDiffCharsPerChunk(120000);
        integration.setMaxDiffChunks(8);
        integration.setRetryTruncatedChunkChars(60000);
        integration.setUpdatedAt(Instant.now());
        return integration;
    }

    private RecordingServer startServer() throws IOException {
        RecordingServer recordingServer = new RecordingServer();
        recordingServer.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        recordingServer.server.createContext("/v1/chat/completions", recordingServer::handle);
        recordingServer.server.start();
        return recordingServer;
    }

    private static class RecordingServer {
        private HttpServer server;
        private String method;
        private String path;
        private String authorization;
        private String body;

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"vLLM response"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
