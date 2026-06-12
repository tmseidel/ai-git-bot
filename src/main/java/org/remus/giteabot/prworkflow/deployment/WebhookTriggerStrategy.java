package org.remus.giteabot.prworkflow.deployment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Deployment strategy that POSTs a JSON envelope to an operator-configured
 * webhook URL and then awaits an asynchronous callback delivered back to
 * {@code POST /api/workflow-callback/{runId}/{secret}}.
 *
 * <p>Strategy configuration JSON (the cleartext value of
 * {@link org.remus.giteabot.prworkflow.config.DeploymentTarget#getConfigJson()}):</p>
 * <pre>
 * {
 *   "webhookUrl": "https://ci.acme.io/jobs/preview/build",
 *   "sharedSecret": "…hex or arbitrary string used for HMAC-SHA256…",
 *   "headers": { "X-Trigger-Source": "ai-git-bot" }   // optional, free-form
 * }
 * </pre>
 *
 * <p>The HTTP request:</p>
 * <pre>
 * POST {webhookUrl}
 * Content-Type: application/json
 * X-AI-Bot-Signature: sha256=&lt;hex hmac-sha256 of body using sharedSecret&gt;
 * X-AI-Bot-Run-Id:   {runId}
 *
 * { "runId": 42, "prNumber": 1234, "sha": "abc…", "branch": "feature/x",
 *   "callbackUrl": "https://bot.acme.io/api/workflow-callback/42/&lt;callbackSecret&gt;",
 *   "callbackSecret": "&lt;callbackSecret&gt;" }
 * </pre>
 */
@Slf4j
@Component
public class WebhookTriggerStrategy implements DeploymentStrategy {

    public static final String CONFIG_WEBHOOK_URL = "webhookUrl";
    public static final String CONFIG_SHARED_SECRET = "sharedSecret";
    public static final String CONFIG_HEADERS = "headers";
    public static final String SIGNATURE_HEADER = "X-AI-Bot-Signature";
    public static final String RUN_ID_HEADER = "X-AI-Bot-Run-Id";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public WebhookTriggerStrategy() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Test seam. */
    WebhookTriggerStrategy(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public DeploymentStrategyType typeKey() {
        return DeploymentStrategyType.WEBHOOK;
    }

    @Override
    public boolean awaitsCallback() {
        return true;
    }

    @Override
    public DeploymentResult trigger(DeploymentRequest request) {
        JsonNode config;
        try {
            config = OBJECT_MAPPER.readTree(request.target().getConfigJson());
        } catch (Exception e) {
            return DeploymentResult.rejected("Deployment target config is not valid JSON: " + e.getMessage());
        }
        String webhookUrl = textOrNull(config, CONFIG_WEBHOOK_URL);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return DeploymentResult.rejected("Deployment target config is missing '" + CONFIG_WEBHOOK_URL + "'");
        }
        String sharedSecret = textOrNull(config, CONFIG_SHARED_SECRET);
        if (sharedSecret == null || sharedSecret.isBlank()) {
            return DeploymentResult.rejected("Deployment target config is missing '" + CONFIG_SHARED_SECRET + "'");
        }

        String body;
        try {
            ObjectNode payload = OBJECT_MAPPER.createObjectNode();
            payload.put("runId", request.run().getId());
            payload.put("prNumber", request.prNumber());
            payload.put("sha", request.sha());
            payload.put("branch", request.branch());
            payload.put("repoOwner", request.repoOwner());
            payload.put("repoName", request.repoName());
            payload.put("callbackUrl", request.callbackUrl());
            payload.put("callbackSecret", request.run().getCallbackSecret());
            body = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return DeploymentResult.rejected("Failed to serialise webhook payload: " + e.getMessage());
        }

        String signature;
        try {
            signature = "sha256=" + hmacSha256Hex(sharedSecret, body);
        } catch (NoSuchAlgorithmException e) {
            return DeploymentResult.rejected("HMAC-SHA256 unavailable in this JVM: " + e.getMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(Math.clamp(request.target().getTimeoutSeconds(), 5, 60)))
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, signature)
                .header(RUN_ID_HEADER, String.valueOf(request.run().getId()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        JsonNode headers = config.get(CONFIG_HEADERS);
        if (headers != null && headers.isObject()) {
            headers.properties().iterator().forEachRemaining(entry ->
                    builder.header(entry.getKey(), entry.getValue().asString()));
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return DeploymentResult.failed("Webhook returned HTTP " + response.statusCode()
                        + ": " + truncate(response.body()), buildHandleJson(webhookUrl));
            }
        } catch (java.io.IOException e) {
            return DeploymentResult.failed("Webhook POST failed: " + e.getMessage(), buildHandleJson(webhookUrl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeploymentResult.failed("Webhook POST interrupted", buildHandleJson(webhookUrl));
        }

        log.info("WebhookTriggerStrategy POSTed deployment trigger for run id={} pr=#{} to {}",
                request.run().getId(), request.prNumber(), webhookUrl);
        return DeploymentResult.pending(buildHandleJson(webhookUrl));
    }

    private String buildHandleJson(String webhookUrl) {
        try {
            ObjectNode handle = OBJECT_MAPPER.createObjectNode();
            handle.put("webhookUrl", webhookUrl);
            handle.put("strategy", DeploymentStrategyType.WEBHOOK.key());
            return OBJECT_MAPPER.writeValueAsString(handle);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 256 ? body.substring(0, 256) + "…" : body;
    }

    static String hmacSha256Hex(String secret, String body) throws NoSuchAlgorithmException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC key", e);
        }
    }
}




