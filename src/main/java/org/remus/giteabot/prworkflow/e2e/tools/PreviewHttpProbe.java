package org.remus.giteabot.prworkflow.e2e.tools;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP probe used by the {@code preview-status} PR-workflow tool. Split
 * out as a Spring bean so unit tests can replace it with a stub that does not
 * touch the network.
 */
@Component
public class PreviewHttpProbe {

    private final HttpClient client;
    private final Duration timeout;

    public PreviewHttpProbe() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(10));
    }

    PreviewHttpProbe(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /** Result of one probe. {@link #durationMs} is wall-clock latency in ms. */
    public record ProbeResult(int statusCode, long durationMs, String bodyExcerpt) { }

    /**
     * GETs the given URL. Failures (DNS, connect, timeout, malformed URL) are
     * reported as a non-throwing result with {@code statusCode == -1} and the
     * exception message in {@code bodyExcerpt}, so the tool can surface a
     * structured error to the agent without raising a stack trace.
     */
    public ProbeResult probe(String url) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            String body = response.body() == null ? "" : response.body();
            String excerpt = body.length() > 500 ? body.substring(0, 500) + "…" : body;
            return new ProbeResult(response.statusCode(), durationMs, excerpt);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            return new ProbeResult(-1, durationMs, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
