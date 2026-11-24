package org.remus.giteabot.ai.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming merge behaviour of {@link LlamaCppClient}: it now sends
 * {@code stream:true} to {@code /v1/completions} and reassembles the SSE chunks
 * ({@code data: {json}}) into a single {@link LlamaCppCompletionResponse}. These tests
 * drive the real client against a JDK {@link HttpServer} stub that emits the
 * same chunk sequence a live llama.cpp server produces.
 */
class LlamaCppClientStreamingTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static RestClient timedClient(int port, long readTimeoutMillis) {
        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(2, TimeUnit.SECONDS)
                .setConnectionRequestTimeout(2, TimeUnit.SECONDS)
                .setResponseTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .build();
        CloseableHttpClient hc = HttpClients.custom().setDefaultRequestConfig(cfg).build();
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(hc))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private LlamaCppClient client() {
        return client(5000);
    }

    private LlamaCppClient client(long readTimeoutMillis) {
        return new LlamaCppClient(timedClient(server.getAddress().getPort(), readTimeoutMillis),
                "qwen2.5-coder", 4096);
    }

    private static final class CapturingRecorder implements AiAuditRecorder {
        long input = -1;
        long output = -1;
        long invocations = 0;

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreation, long cacheRead,
                                String rawRequest, String rawResponse) {
            this.input = inputTokens;
            this.output = outputTokens;
            this.invocations++;
        }

    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    /** Escapes a Java string for embedding as a JSON string literal value. */
    private static String j(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Emits SSE chunks: each {@code data: <json>}, then a terminating
     *  {@code data: [DONE]} (both are valid llama.cpp stream framing). */
    private void emitSse(String... jsonChunks) {
        server.createContext("/v1/completions", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0); // chunked
                OutputStream os = exchange.getResponseBody();
                for (String json : jsonChunks) {
                    os.write(("data: " + json).getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                    os.flush();
                }
                // Optional terminal marker — the client must tolerate it.
                os.write("data: [DONE]\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                // best effort
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    // -----------------------------------------------------------------
    // SSE merge — content concatenated, grammar output intact, with usage
    // counters and finish reason from the final chunks.
    // -----------------------------------------------------------------

    @Test
    void sseMerge_concatenatesGrammarJsonAndTakesCountersFromFinalChunk() throws Exception {
        // A GBNF-constrained JSON response, split across chunks exactly as
        // llama.cpp would emit it token by token, with usage and finish reason
        // metadata at the end of the stream.
        String json = "{\"fileChanges\":[{\"file\":\"a.java\",\"content\":\"int x = 1;\"}],"
                + "\"message\":\"looks good\",\"done\":true}";
        // Split into a few pieces; the merge must reproduce the exact JSON.
        String a = "{\"fileChanges\":[{\"file\":\"a.java\",\"cont";
        String b = "ent\":\"int x = 1;\"}],\"message\":\"look";
        String c = "s good\",\"done\":true}";

        String mid1 = "{\"choices\":[{\"text\":\"" + j(a) + "\",\"finish_reason\":null}]}";
        String mid2 = "{\"choices\":[{\"text\":\"" + j(b) + "\",\"finish_reason\":null}]}";
        String fin = "{\"choices\":[{\"text\":\"" + j(c) + "\",\"finish_reason\":\"length\"}],"
                + "\"usage\":{\"prompt_tokens\":16,\"completion_tokens\":491}}";

        emitSse(mid1, mid2, fin);

        CapturingRecorder recorder = new CapturingRecorder();
        LlamaCppClient client = client();
        client.setAuditRecorder(recorder);

        String out = client.submitReviewPrompt("respond with a json", null, "review");

        // The merged content must be the exact, grammar-constrained JSON (no
        // framing leakage, no double-counted tokens).
        assertEquals(json, out);
        // Counters from the final chunk only.
        assertEquals(16L, recorder.input);
        assertEquals(491L, recorder.output);
        assertEquals(1, recorder.invocations);
    }

    // -----------------------------------------------------------------
    // Edge case: per-chunk counters must not be summed.
    // -----------------------------------------------------------------

    @Test
    void usageParity_recordsFinalChunkCountersNotASum() {
        String mid1 = "{\"choices\":[{\"text\":\"a\",\"finish_reason\":null}]}";
        String mid2 = "{\"choices\":[{\"text\":\"b\",\"finish_reason\":null}]}";
        String fin = "{\"choices\":[{\"text\":\"\",\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":22,\"completion_tokens\":19}}";

        emitSse(mid1, mid2, fin);

        CapturingRecorder recorder = new CapturingRecorder();
        LlamaCppClient client = client();
        client.setAuditRecorder(recorder);

        String out = client.submitReviewPrompt("review", null, "hi");

        assertEquals("ab", out);
        // Final usage, not a sum across chunks.
        assertEquals(22L, recorder.input);
        assertEquals(19L, recorder.output, "output tokens = final usage, not a sum");
    }

    @Test
    void typedTurnPreservesTokenLimitAndFinalUsage() {
        emitSse("""
                {"choices":[{"text":"Partial ","finish_reason":null}]}
                """.strip(), """
                {"choices":[{"text":"review","finish_reason":"length"}],
                 "usage":{"prompt_tokens":22,"completion_tokens":19}}
                """.replace("\n", ""));
        LlamaCppClient client = client();
        CapturingRecorder recorder = new CapturingRecorder();
        client.setAuditRecorder(recorder);

        ChatTurn turn = client.chatWithTools(List.of(), "Review this change", List.of(),
                "You are a reviewer.", null, null);

        assertEquals(StopReason.MAX_TOKENS, turn.stopReason());
        assertEquals("Partial review", turn.assistantText());
        assertTrue(turn.toolCalls().isEmpty());
        assertEquals(22L, turn.inputTokens());
        assertEquals(19L, turn.outputTokens());
        assertEquals(22L, recorder.input);
        assertEquals(19L, recorder.output);
        assertEquals(1, recorder.invocations);
    }

    @ParameterizedTest
    @CsvSource({"stop, END_TURN", "length, MAX_TOKENS", "content_filter, OTHER", "unknown, OTHER"})
    void typedTurnMapsOpenAiFinishReasons(String finishReason, StopReason expected) {
        emitSse("""
                {"choices":[{"text":"Review text","finish_reason":"%s"}],
                 "usage":{"prompt_tokens":22,"completion_tokens":19}}
                """.formatted(finishReason).replace("\n", ""));

        ChatTurn turn = client().chatWithTools(List.of(), "Review this change", List.of(), "sys", null, null);

        assertEquals(expected, turn.stopReason());
        assertEquals("Review text", turn.assistantText());
        assertEquals(22L, turn.inputTokens());
        assertEquals(19L, turn.outputTokens());
    }

    @Test
    void finalFinishReasonIsUsedAfterIntermediateChunks() {
        emitSse("{\"choices\":[{\"text\":\"Review \",\"finish_reason\":null}]}",
                "{\"choices\":[{\"text\":\"text\",\"finish_reason\":\"stop\"}]}");

        ChatTurn turn = client().chatWithTools(List.of(), "Review this change", List.of(), "sys", null, null);

        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals("Review text", turn.assistantText());
    }

    @Test
    void usageOnlyFinalChunkKeepsEarlierFinishReason() {
        emitSse("{\"choices\":[{\"text\":\"Review text\",\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");

        ChatTurn turn = client().chatWithTools(List.of(), "Review this change", List.of(), "sys", null, null);

        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals("Review text", turn.assistantText());
        assertEquals(3L, turn.inputTokens());
        assertEquals(2L, turn.outputTokens());
    }

    @Test
    void sseCommentsAndKeepalivesAreIgnored() {
        server.createContext("/v1/completions", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("""
                        : keepalive
                        : another comment
                        data: {"choices":[{"text":"Review text","finish_reason":"stop"}]}
                        data: [DONE]
                        """).getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatTurn turn = client().chatWithTools(List.of(), "Review this change", List.of(), "sys", null, null);

        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals("Review text", turn.assistantText());
    }

    @Test
    void doneMarkerStopsReadingWithoutWaitingForConnectionClose() {
        server.createContext("/v1/completions", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                os.write(("data: {\"choices\":[{\"text\":\"done\","
                        + "\"finish_reason\":\"stop\"}]}\n").getBytes(StandardCharsets.UTF_8));
                os.write("data: [DONE]\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        LlamaCppClient client = client(200);
        String result = client.submitReviewPrompt("review", null, "hi");

        assertEquals("done", result);
    }

    @Test
    void eofAfterFinishReasonIsAcceptedWithoutDoneMarker() {
        emitRawSse("data: {\"choices\":[{\"text\":\"complete\",\"finish_reason\":\"stop\"}]}\n");

        assertEquals("complete", client().submitReviewPrompt("review", null, "hi"));
    }

    @Test
    void doneMarkerCompletesStreamWithoutFinishReason() {
        emitRawSse("""
                data: {"choices":[{"text":"complete","finish_reason":null}]}
                data: [DONE]
                """);

        assertEquals("complete", client().submitReviewPrompt("review", null, "hi"));
    }

    @Test
    void eofWithoutFinishReasonOrDoneMarkerIsRejected() {
        emitRawSse("data: {\"choices\":[{\"text\":\"partial\",\"finish_reason\":null}]}\n");

        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("review", null, "hi"));

        assertTrue(ex.getMessage().contains("missing finish_reason or [DONE]"));
    }

    @Test
    void inBandErrorAfterPartialContentFailsTheRequest() {
        emitRawSse("""
                data: {"choices":[{"text":"partial","finish_reason":null}]}
                data: {"error":{"message":"model route failed","type":"server_error","code":500}}
                """);

        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("review", null, "hi"));

        assertTrue(ex.getMessage().contains("model route failed"));
    }

    @Test
    void errorEventAfterPartialContentFailsTheRequest() {
        emitRawSse("""
                data: {"choices":[{"text":"partial","finish_reason":null}]}
                error: {"message":"model route failed","type":"server_error","code":500}
                """);

        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("review", null, "hi"));

        assertTrue(ex.getMessage().contains("model route failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "data: {}%n" + "data: [DONE]%n",
            "data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":0}}%n"
                    + "data: [DONE]%n"
    })
    void emptyOrMetadataOnlyStreamsAreRejected(String bodyTemplate) {
        emitRawSse(bodyTemplate.formatted());

        assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("review", null, "hi"));
    }

    @Test
    void completedEmptyChoiceUsesTheExistingFallback() {
        emitRawSse("data: {\"choices\":[{\"text\":\"\",\"finish_reason\":\"stop\"}]}%n".formatted());

        assertEquals("Unable to generate review - empty response from AI.",
                client().submitReviewPrompt("review", null, "hi"));
    }

    @Test
    void emptyBodyIsRejectedWithoutNullPointerException() {
        server.createContext("/v1/completions", exchange -> {
            drain(exchange);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("review", null, "hi"));

        assertTrue(ex.getMessage().contains("missing finish_reason or [DONE]"));
    }

    private void emitRawSse(String body) {
        server.createContext("/v1/completions", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
