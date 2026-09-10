package org.remus.giteabot.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the streaming read-timeout behaviour of
 * {@link OllamaClient}. It drives the real client (not a mock) against a JDK
 * {@link HttpServer} stub with a real Apache-HttpComponents read timeout of 2s,
 * mirroring how {@code spring.http.clients.read-timeout} is applied to the
 * production client (Boot 4 {@code HttpClientSettings} → HC5 {@code setResponseTimeout}).
 *
 * <p>Proves the three timeout cases required by the streaming fix:
 * <ul>
 *   <li>AC 3 — slow-but-alive: total generation &gt; read timeout, but every
 *       inter-chunk gap &lt; timeout → the request <em>completes</em> (the read
 *       timeout is a per-chunk stall detector, not a whole-request budget).</li>
 *   <li>AC 4 — silent mid-stream: chunks stop arriving → fails with
 *       {@link ResourceAccessException}/{@link SocketTimeoutException} after ~
 *       the read timeout.</li>
 *   <li>Pre-first-chunk stall: no chunks emitted (model load) → same read-timeout
 *       failure as before the streaming change (the load still counts against
 *       the timeout — documented limitation).</li>
 * </ul>
 */
class OllamaStreamingTimeoutIT {

    private static final long READ_TIMEOUT_MS = 2000;

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

    private OllamaClient client() {
        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(2, TimeUnit.SECONDS)
                .setConnectionRequestTimeout(2, TimeUnit.SECONDS)
                .setResponseTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        CloseableHttpClient hc = HttpClients.custom().setDefaultRequestConfig(cfg).build();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(hc))
                .defaultHeader("Content-Type", "application/json")
                .build();
        return new OllamaClient(restClient, "test-model", 128, true);
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    // -----------------------------------------------------------------
    // AC 3: slow-but-alive — total generation time exceeds the read timeout,
    // but chunks keep arriving within the timeout window → completes.
    // -----------------------------------------------------------------

    @Test
    void slowButAlive_totalGenerationExceedsTimeoutButChunksKeepFlowing_completes() {
        // 6 chunks, first one quick, then a 1s gap between each (all gaps < 2s).
        // Total ≈ 5s, which is well beyond the 2s read timeout. Because a byte
        // arrives within the window on every read, no single read times out and
        // the whole generation completes.
        server.createContext("/api/chat", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0); // chunked
                OutputStream os = exchange.getResponseBody();
                int total = 6;
                for (int i = 0; i < total; i++) {
                    boolean done = (i == total - 1);
                    String line = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"w\"},"
                            + (done
                                ? "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":16,\"eval_count\":42}"
                                : "\"done\":false}");
                    os.write(line.getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                    os.flush();
                    if (!done) {
                        Thread.sleep(1000); // gap < 2s read timeout
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        long t0 = System.nanoTime();
        String review = client().submitReviewPrompt("You are a code reviewer.", null, "review");
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        assertEquals("wwwwww", review, "merged content from all 6 chunks");
        // Proves the request genuinely took longer than the read timeout yet
        // still succeeded — the whole point of per-chunk stall detection.
        assertTrue(elapsed >= READ_TIMEOUT_MS,
                "expected total to exceed the " + READ_TIMEOUT_MS + "ms read timeout, took " + elapsed + "ms");
    }

    // -----------------------------------------------------------------
    // AC 4: silent mid-stream — chunks stop arriving → read timeout ~2s.
    // -----------------------------------------------------------------

    @Test
    void silentMidStream_failsWithResourceAccessExceptionAtReadTimeout() {
        server.createContext("/api/chat", exchange -> {
            try {
                drain(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0); // chunked
                OutputStream os = exchange.getResponseBody();
                os.write("{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"a\"},\"done\":false}\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"b\"},\"done\":false}\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                // Now go completely silent (never close) → the next read blocks
                // until the 2s read timeout fires.
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        long t0 = System.nanoTime();
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("You are a code reviewer.", null, "review"));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsed < 9_000, "should fail near the 2s read timeout, took " + elapsed + "ms");
        assertTrue(isReadTimeout(ex), "expected a read timeout, got: " + ex
                + " (cause: " + ex.getCause() + ")");
    }

    // -----------------------------------------------------------------
    // Pre-first-chunk stall: no chunks (model load) → read timeout ~2s.
    // This preserves the behaviour of the pre-streaming OllamaReadTimeoutIT.
    // -----------------------------------------------------------------

    @Test
    void silentBeforeFirstChunk_stillFailsAtReadTimeout() {
        server.createContext("/api/chat", exchange -> {
            try {
                // Model load: emit nothing for a long time before the first
                // chunk (or response). The read timeout still applies — this is
                // the documented limitation of streaming (load time counts).
                Thread.sleep(20_000);
                exchange.sendResponseHeaders(200, 0); // too late
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        long t0 = System.nanoTime();
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client().submitReviewPrompt("You are a code reviewer.", null, "review"));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsed < 9_000, "should fail near the 2s read timeout, took " + elapsed + "ms");
        assertTrue(isReadTimeout(ex), "expected a read timeout, got: " + ex
                + " (cause: " + ex.getCause() + ")");
    }

    private static boolean isReadTimeout(ResourceAccessException ex) {
        if (ex.getCause() instanceof SocketTimeoutException) {
            return true;
        }
        String msg = String.valueOf(ex.getMessage());
        return msg != null && msg.toLowerCase().contains("timed out");
    }
}
