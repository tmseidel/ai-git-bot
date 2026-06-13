package org.remus.giteabot.prworkflow.deployment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Deployment strategy for projects that already auto-provision a per-PR
 * preview URL (Vercel, Netlify, Render, GitLab review apps). The strategy
 * does NOT trigger anything; it just resolves the operator-configured URL
 * template and optionally probes a readiness endpoint until the preview is
 * reachable.
 *
 * <p>Strategy configuration JSON:</p>
 * <pre>
 * {
 *   "healthcheckPath": "/healthz",          // optional, default "/" (omit probe by setting "" )
 *   "expectedStatus": 200,                  // optional, default 200
 *   "intervalSeconds": 5,                   // poll interval, default 5
 *   "extraHeaders": { "X-Probe": "ai-bot" } // optional
 * }
 * </pre>
 *
 * <p>The URL template comes from {@code DeploymentTarget.previewUrlTemplate}
 * and supports the placeholders {@code {prNumber}}, {@code {sha}} and
 * {@code {branch}}.</p>
 */
@Slf4j
@Component
public class StaticPreviewUrlStrategy implements DeploymentStrategy {

    public static final String CONFIG_HEALTHCHECK_PATH = "healthcheckPath";
    public static final String CONFIG_EXPECTED_STATUS = "expectedStatus";
    public static final String CONFIG_INTERVAL_SECONDS = "intervalSeconds";
    public static final String CONFIG_EXTRA_HEADERS = "extraHeaders";

    private final HttpClient httpClient;
    private final Sleeper sleeper;

    public StaticPreviewUrlStrategy() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Thread::sleep);
    }

    /** Test seam. */
    StaticPreviewUrlStrategy(HttpClient httpClient, Sleeper sleeper) {
        this.httpClient = httpClient;
        this.sleeper = sleeper;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public DeploymentStrategyType typeKey() {
        return DeploymentStrategyType.STATIC;
    }

    @Override
    public DeploymentResult trigger(DeploymentRequest request) {
        String template = request.target().getPreviewUrlTemplate();
        if (template == null || template.isBlank()) {
            return DeploymentResult.rejected(
                    "Deployment target is STATIC but has no preview URL template");
        }
        String previewUrl = template
                .replace("{prNumber}", String.valueOf(request.prNumber()))
                .replace("{sha}", request.sha() == null ? "" : request.sha())
                .replace("{branch}", request.branch() == null ? "" : request.branch());

        JsonNode config;
        try {
            String json = request.target().getConfigJson();
            config = (json == null || json.isBlank())
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            return DeploymentResult.rejected("Deployment target config is not valid JSON: " + e.getMessage());
        }

        String healthcheckPath = textOr(config, CONFIG_HEALTHCHECK_PATH, "/");
        if (healthcheckPath.isEmpty()) {
            // Operator opted out of the probe — trust the URL template.
            log.info("StaticPreviewUrlStrategy resolved preview URL {} for run id={} pr=#{} (no probe)",
                    previewUrl, request.run().getId(), request.prNumber());
            return DeploymentResult.ready(previewUrl, "{}");
        }
        int expectedStatus = intOr(config, CONFIG_EXPECTED_STATUS, 200);
        int intervalSeconds = Math.max(1, intOr(config, CONFIG_INTERVAL_SECONDS, 5));
        int timeoutSeconds = Math.max(intervalSeconds, request.target().getTimeoutSeconds());

        String probeUrl = previewUrl + (healthcheckPath.startsWith("/") ? healthcheckPath : "/" + healthcheckPath);
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(probeUrl))
                        .timeout(Duration.ofSeconds(Math.min(10, intervalSeconds)))
                        .GET();
                JsonNode extra = config.get(CONFIG_EXTRA_HEADERS);
                if (extra != null && extra.isObject()) {
                    extra.properties().iterator().forEachRemaining(entry ->
                            builder.header(entry.getKey(), entry.getValue().asString()));
                }
                HttpResponse<Void> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == expectedStatus) {
                    log.info("StaticPreviewUrlStrategy preview {} ready after {} probe(s)",
                            previewUrl, attempts);
                    return DeploymentResult.ready(previewUrl, "{}");
                }
                log.debug("Probe {} returned HTTP {} (expected {}), retrying", probeUrl,
                        response.statusCode(), expectedStatus);
            } catch (java.io.IOException ignored) {
                // expected during warm-up; fall through to sleep
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DeploymentResult.failed("Readiness probe interrupted", "{}");
            }
            try {
                sleeper.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DeploymentResult.failed("Readiness probe interrupted", "{}");
            }
        }
        return DeploymentResult.failed(
                "Preview URL " + probeUrl + " did not become ready within " + timeoutSeconds + "s",
                "{}");
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? fallback : v.asString();
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull() || !v.canConvertToInt()) ? fallback : v.asInt();
    }

    /** Test seam so unit tests can avoid real sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}




