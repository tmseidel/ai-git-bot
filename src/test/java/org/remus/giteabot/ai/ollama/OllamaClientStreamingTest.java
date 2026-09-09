package org.remus.giteabot.ai.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming merge behaviour of {@link OllamaClient}: it now sends
 * {@code stream:true} to {@code /api/chat} and reassembles the NDJSON chunks
 * into a single {@link OllamaResponse}. These tests drive the real client
 * against a JDK {@link HttpServer} stub that emits the same chunk sequences a
 * live Ollama server produces.
 */
class OllamaClientStreamingTest {

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

    private OllamaClient client() {
        return new OllamaClient(timedClient(server.getAddress().getPort(), 5000),
                "test-model", 128, true);
    }

    /** Captures the last (input, output) token pair passed to reportUsage. */
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

    private void attachRecorder(OllamaClient client, CapturingRecorder recorder) {
        client.setAuditRecorder(recorder);
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    /** Emits the given NDJSON lines (one OllamaResponse per line) then closes. */
    private void emitNdjson(String... lines) {
        server.createContext("/api/chat", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0); // chunked
                OutputStream os = exchange.getResponseBody();
                for (String line : lines) {
                    os.write(line.getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                    os.flush();
                }
            } catch (IOException e) {
                // best effort
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    // -----------------------------------------------------------------
    // AC 1: NDJSON merge — concatenated text + counters from the final chunk
    // -----------------------------------------------------------------

    @Test
    void ndjsonMerge_concatenatesContentAndTakesCountersFromFinalChunk() {
        // The reference chunk stream from the incident report, with the final
        // chunk carrying the usage counters (prompt_eval_count / eval_count).
        emitNdjson(
                "{\"model\":\"qwen3.8:latest\",\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Orange\"},\"done\":false}",
                "{\"model\":\"qwen3.8:latest\",\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\" wavelengths\"},\"done\":false}",
                "{\"model\":\"qwen3.8:latest\",\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\" dominate.\"},\"done\":false}",
                "{\"model\":\"qwen3.8:latest\",\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                        + "\"done_reason\":\"stop\",\"prompt_eval_count\":16,\"eval_count\":491}"
        );

        CapturingRecorder recorder = new CapturingRecorder();
        OllamaClient client = client();
        attachRecorder(client, recorder);

        String review = client.submitReviewPrompt("You are a code reviewer.", null, "review this PR");

        assertEquals("Orange wavelengths dominate.", review);
        // Counters come from the single done:true chunk (not summed from others).
        assertEquals(16L, recorder.input, "prompt tokens from final chunk");
        assertEquals(491L, recorder.output, "eval tokens from final chunk");
        assertEquals(1, recorder.invocations, "usage reported exactly once");
    }

    // -----------------------------------------------------------------
    // AC 6: tool calls are emitted complete in the final chunk
    // -----------------------------------------------------------------

    @Test
    void toolCalls_streamedFromFinalChunk_andSynthesizeIds() {
        emitNdjson(
                "{\"model\":\"m\",\"created_at\":\"t\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}",
                "{\"model\":\"m\",\"created_at\":\"t\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"search\","
                        + "\"arguments\":{\"query\":\"npe\"}}}]},"
                        + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}"
        );

        OllamaClient client = client();
        CapturingRecorder recorder = new CapturingRecorder();
        attachRecorder(client, recorder);

        ChatTurn turn = client.chatWithTools(
                List.of(), "please search",
                List.of(new ToolDescriptor("search", "search the code base", null)),
                "You are an agent.", null, null);

        assertTrue(turn.hasToolCalls(), "turn should carry the streamed tool call");
        assertEquals(1, turn.toolCalls().size());
        ToolCall call = turn.toolCalls().get(0);
        assertEquals("search", call.name(), "sanitised name is desanitised back on read");
        // Ollama supplies no call id; the client synthesises "<name>:<index>".
        assertEquals("search:0", call.id());
        assertEquals(StopReason.TOOL_USE, turn.stopReason());
        assertEquals(10L, turn.inputTokens());
        assertEquals(5L, turn.outputTokens());
    }

    // -----------------------------------------------------------------
    // AC 5: usage parity — recorded totals are the final chunk's counters,
    // not the sum of per-chunk counters.
    // -----------------------------------------------------------------

    @Test
    void usageParity_recordsFinalChunkCountersNotASum() {
        // Deliberately put (fake) per-chunk counters on the intermediate chunks.
        // The merge must use the final chunk's values, not sum the stream.
        emitNdjson(
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"a\"},\"done\":false,\"eval_count\":5}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"b\"},\"done\":false,\"eval_count\":10}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                        + "\"done_reason\":\"stop\",\"prompt_eval_count\":16,\"eval_count\":491}"
        );

        OllamaClient client = client();
        CapturingRecorder recorder = new CapturingRecorder();
        attachRecorder(client, recorder);

        String out = client.submitReviewPrompt("review", null, "hi");

        assertEquals("ab", out);
        assertEquals(16L, recorder.input, "input tokens = final chunk prompt_eval_count");
        assertEquals(491L, recorder.output, "output tokens = final chunk eval_count (not 5+10+491)");
    }

    // -----------------------------------------------------------------
    // edge case: empty stream (0 chunks) -> existing empty-response fallback
    // -----------------------------------------------------------------

    @Test
    void emptyStream_returnsExistingEmptyResponseFallback() {
        server.createContext("/api/chat", exchange -> {
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

        OllamaClient client = client();
        String out = client.submitReviewPrompt("review", null, "hi");
        assertTrue(out.startsWith("Unable to generate review - empty response"),
                "expected the historical empty-response fallback, got: " + out);
    }
}
