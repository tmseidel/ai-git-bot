package org.remus.giteabot.ai;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Shared line-oriented streaming transport for local-provider AI clients
 * (Ollama, llama.cpp).
 *
 * <p>Posts a request body and reads the streaming response line by line,
 * handing every non-blank line to a consumer. Reading line by line keeps
 * {@code spring.http.clients.read-timeout} acting as a <em>per-chunk stall
 * detector</em> rather than a whole-request budget: each socket read is bounded
 * by the timeout, so a slow-but-alive model (chunks keep arriving within the
 * window) never trips it, while a genuinely wedged server (no byte for the whole
 * window) still fails fast. This fixes the intermittent "Read timed out"
 * failures on local Ollama / llama.cpp, where a non-streamed request blocks the
 * socket until the entire generation — model load + prompt eval + token
 * generation — completes.</p>
 *
 * <p>The helper is format-agnostic: it only strips line terminators and skips
 * blank lines. The consumer is responsible for any framing it needs — Ollama
 * emits NDJSON (one JSON object per line) and llama.cpp's {@code /completion}
 * with {@code stream:true} emits SSE ({@code data: {json}} per chunk) — and for
 * parsing / reassembling the chunks into the provider-specific response DTO.</p>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>Model-load time still counts against the read timeout: Ollama emits no
 *       chunks while loading, so a load longer than the timeout still trips it.
 *       That is expected and documented; streaming removes the timeout pressure
 *       for the (much larger) prompt-eval + generation phases.</li>
 *   <li>I/O failures mid-stream (connection drop, read timeout) surface as
 *       {@link ResourceAccessException}, which {@code AgentLoop.callAiWithRetry}
 *       already treats as transient.</li>
 *   <li>Malformed lines are surfaced to the consumer; the client fails the
 *       request (does not silently skip) when a line does not parse.</li>
 *   <li>No new dependencies: only the auto-configured {@link RestClient} and JDK.</li>
 * </ul>
 */
public final class StreamingLineReader {

    private StreamingLineReader() {
        // utility class
    }

    /**
     * POSTs {@code body} to {@code uri} (relative to the client's base URL) and
     * streams the response, invoking {@code lineConsumer} for each non-blank
     * line of the body.
     *
     * <p>The response is closed by the underlying {@link RestClient}
     * {@code exchange} call after this method returns (or after an exception
     * propagates), so callers do not need to close anything. The stream is
     * drained fully (to EOF) before returning, which is also what keeps the
     * per-read socket timeout active for the entire generation.</p>
     *
     * @param restClient   the configured client (base URL, headers, read timeout)
     * @param uri          the endpoint, relative to the client's base URL
     * @param body         the request payload
     * @param lineConsumer receives each non-blank response line (may throw to
     *                     fail the request, e.g. on a malformed line)
     * @throws ResourceAccessException on I/O errors, including a read timeout
     *         before or mid-stream
     * @throws org.springframework.web.client.HttpClientErrorException on 4xx
     * @throws org.springframework.web.client.HttpServerErrorException on 5xx
     */
    public static void streamLines(RestClient restClient, String uri, Object body,
                                   Consumer<String> lineConsumer) {
        restClient.post()
                .uri(uri)
                .body(body)
                .exchange((request, response) -> {
                    // getStatusCode() / getBody() throw IOException on I/O
                    // trouble; Spring's exchange wrapper converts those to a
                    // ResourceAccessException, so we let them propagate.
                    HttpStatusCode status = response.getStatusCode();
                    if (status.isError()) {
                        // createException() builds the correct
                        // HttpClientErrorException / HttpServerErrorException from
                        // the status + body. It is a RuntimeException, so Spring
                        // rethrows it as-is (not wrapped in ResourceAccessException).
                        throw response.createException();
                    }
                    InputStream raw = response.getBody();
                    // No try-with-resources: the underlying InputStream is the
                    // response body, which the exchange call closes in its own
                    // finally block (single close). Reading to EOF (readLine()
                    // returns null) or throwing (mid-stream error) both leave the
                    // socket to that finally close.
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(raw, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) {
                            lineConsumer.accept(line);
                        }
                    }
                    return null;
                }, true);
    }
}
