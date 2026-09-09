package org.remus.giteabot.ai.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming merge behaviour of {@link LlamaCppClient}: it now sends
 * {@code stream:true} to {@code /completion} and reassembles the SSE chunks
 * ({@code data: {json}}) into a single {@link LlamaCppResponse}. These tests
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
        return new LlamaCppClient(timedClient(server.getAddress().getPort(), 5000),
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

        @Override
        public void recordError(Throwable error) {
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
        server.createContext("/completion", exchange -> {
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
    // AC 2: SSE merge — content concatenated, grammar output intact,
    // usage counters + stoppedLimit from the final (stop:true) chunk.
    // -----------------------------------------------------------------

    @Test
    void sseMerge_concatenatesGrammarJsonAndTakesCountersFromFinalChunk() throws Exception {
        // A GBNF-constrained JSON response, split across chunks exactly as
        // llama.cpp would emit it token by token, with the usage counters and
        // stoppedLimit on the final chunk (stop:true).
        String json = "{\"fileChanges\":[{\"file\":\"a.java\",\"content\":\"int x = 1;\"}],"
                + "\"message\":\"looks good\",\"done\":true}";
        // Split into a few pieces; the merge must reproduce the exact JSON.
        String a = "{\"fileChanges\":[{\"file\":\"a.java\",\"cont";
        String b = "ent\":\"int x = 1;\"}],\"message\":\"look";
        String c = "s good\",\"done\":true}";

        String mid1 = "{\"content\":\"" + j(a) + "\",\"model\":\"qwen\",\"stop\":false,"
                + "\"stopped_eos\":false,\"stopped_limit\":false,\"stopped_word\":false,"
                + "\"tokens_evaluated\":0,\"tokens_predicted\":0,\"truncated\":false}";
        String mid2 = "{\"content\":\"" + j(b) + "\",\"model\":\"qwen\",\"stop\":false,"
                + "\"stopped_eos\":false,\"stopped_limit\":false,\"stopped_word\":false,"
                + "\"tokens_evaluated\":0,\"tokens_predicted\":0,\"truncated\":false}";
        String fin = "{\"content\":\"" + j(c) + "\",\"model\":\"qwen\",\"stop\":true,"
                + "\"stopped_eos\":false,\"stopped_limit\":true,\"stopped_word\":false,"
                + "\"tokens_evaluated\":16,\"tokens_predicted\":491,\"truncated\":false,"
                + "\"timings\":{\"prompt_n\":16,\"prompt_ms\":0.3,\"predicted_n\":491,\"predicted_ms\":20.3}}";

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
    // AC 5 / edge case: per-chunk counters must not be summed.
    // -----------------------------------------------------------------

    @Test
    void usageParity_recordsFinalChunkCountersNotASum() {
        String mid1 = "{\"content\":\"a\",\"model\":\"m\",\"stop\":false,"
                + "\"tokens_evaluated\":0,\"tokens_predicted\":7}";
        String mid2 = "{\"content\":\"b\",\"model\":\"m\",\"stop\":false,"
                + "\"tokens_evaluated\":0,\"tokens_predicted\":12}";
        String fin = "{\"content\":\"\",\"model\":\"m\",\"stop\":true,"
                + "\"stopped_limit\":false,\"tokens_evaluated\":22,\"tokens_predicted\":19}";

        emitSse(mid1, mid2, fin);

        CapturingRecorder recorder = new CapturingRecorder();
        LlamaCppClient client = client();
        client.setAuditRecorder(recorder);

        String out = client.submitReviewPrompt("review", null, "hi");

        assertEquals("ab", out);
        // 19, not 7+12+19.
        assertEquals(22L, recorder.input);
        assertEquals(19L, recorder.output, "output tokens = final chunk tokens_predicted (not a sum)");
    }

    // -----------------------------------------------------------------
    // edge case: empty stream (0 chunks) -> existing empty-response fallback.
    // -----------------------------------------------------------------

    @Test
    void emptyStream_returnsExistingEmptyResponseFallback() {
        server.createContext("/completion", exchange -> {
            try {
                drain(exchange);
                exchange.sendResponseHeaders(200, 0); // empty 200, no body
            } catch (IOException e) {
                // best effort
            } finally {
                exchange.close();
            }
        });
        server.start();

        String out = client().submitReviewPrompt("review", null, "hi");
        assertTrue(out.startsWith("Unable to generate review - empty response"),
                "expected the historical empty-response fallback, got: " + out);
    }
}
