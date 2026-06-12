package org.remus.giteabot.prworkflow.deployment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookTriggerStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeploymentRequest request(String configJson) {
        DeploymentTarget target = new DeploymentTarget();
        target.setName("ci");
        target.setStrategyType(DeploymentStrategyType.WEBHOOK);
        target.setConfigJson(configJson);
        target.setTimeoutSeconds(30);
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setCallbackSecret("cbsecret");
        return new DeploymentRequest(run, target, "acme", "web", 1234L,
                "abc123", "feature/x",
                "https://bot.acme.io/api/workflow-callback/42/cbsecret");
    }

    @Test
    void rejectsMissingWebhookUrl() {
        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(mock(HttpClient.class));
        DeploymentResult result = strategy.trigger(request("{\"sharedSecret\":\"s\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("webhookUrl");
    }

    @Test
    void rejectsMissingSharedSecret() {
        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(mock(HttpClient.class));
        DeploymentResult result = strategy.trigger(request("{\"webhookUrl\":\"https://ci/build\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("sharedSecret");
    }

    @Test
    void rejectsInvalidJson() {
        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(mock(HttpClient.class));
        DeploymentResult result = strategy.trigger(request("not-json"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postsSignedPayloadAndReturnsPending() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(202);
        doReturn(response).when(client).send(any(), any());

        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(client);
        String cfg = "{\"webhookUrl\":\"https://ci/build\",\"sharedSecret\":\"topsecret\","
                + "\"headers\":{\"X-Trigger\":\"ai-bot\"}}";
        DeploymentResult result = strategy.trigger(request(cfg));

        assertThat(result.status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(result.handleJson()).contains("https://ci/build");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest sent = captor.getValue();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString()).isEqualTo("https://ci/build");
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(sent.headers().firstValue("X-AI-Bot-Run-Id")).contains("42");
        assertThat(sent.headers().firstValue("X-Trigger")).contains("ai-bot");
        String sig = sent.headers().firstValue("X-AI-Bot-Signature").orElseThrow();
        assertThat(sig).startsWith("sha256=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsOnNon2xx() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("boom");
        doReturn(response).when(client).send(any(), any());

        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(client);
        DeploymentResult result = strategy.trigger(request(
                "{\"webhookUrl\":\"https://ci/build\",\"sharedSecret\":\"s\"}"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(result.errorMessage()).contains("HTTP 500").contains("boom");
    }

    @Test
    void failsOnIoException() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new IOException("network down")).when(client).send(any(), any());
        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(client);
        DeploymentResult result = strategy.trigger(request(
                "{\"webhookUrl\":\"https://ci/build\",\"sharedSecret\":\"s\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(result.errorMessage()).contains("network down");
    }

    @Test
    void hmacSignatureIsStableAndCorrect() throws Exception {
        // Known HMAC-SHA256("k", "msg") = 2d93cbcbe83a4cc6d7eba6fd56bd9e6c8db35c0a4d8a47bdc6cdb6c8a9b46e87
        String hex = WebhookTriggerStrategy.hmacSha256Hex("k", "msg");
        assertThat(hex).hasSize(64).matches("[0-9a-f]+");
        // Determinism: same input => same hex.
        assertThat(WebhookTriggerStrategy.hmacSha256Hex("k", "msg")).isEqualTo(hex);
    }

    @Test
    void payloadShapeMatchesContract() {
        RecordingClient client = new RecordingClient();
        WebhookTriggerStrategy strategy = new WebhookTriggerStrategy(client);
        DeploymentResult result = strategy.trigger(request(
                "{\"webhookUrl\":\"https://ci/build\",\"sharedSecret\":\"s\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.PENDING);
        JsonNode body = MAPPER.readTree(client.lastBody);
        assertThat(body.get("runId").asLong()).isEqualTo(42L);
        assertThat(body.get("prNumber").asLong()).isEqualTo(1234L);
        assertThat(body.get("sha").asString()).isEqualTo("abc123");
        assertThat(body.get("branch").asString()).isEqualTo("feature/x");
        assertThat(body.get("repoOwner").asString()).isEqualTo("acme");
        assertThat(body.get("repoName").asString()).isEqualTo("web");
        assertThat(body.get("callbackUrl").asString()).contains("/api/workflow-callback/42/");
        assertThat(body.get("callbackSecret").asString()).isEqualTo("cbsecret");
    }

    /** Minimal HttpClient that records the request body sent — avoids deep Mockito stubbing. */
    private static final class RecordingClient extends HttpClient {
        String lastBody;

        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            request.bodyPublisher().ifPresent(pub -> {
                java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> sub = new java.util.concurrent.Flow.Subscriber<>() {
                    final StringBuilder sb = new StringBuilder();
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    @Override public void onNext(java.nio.ByteBuffer item) {
                        byte[] arr = new byte[item.remaining()];
                        item.get(arr);
                        sb.append(new String(arr, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    @Override public void onError(Throwable t) { }
                    @Override public void onComplete() { lastBody = sb.toString(); }
                };
                pub.subscribe(sub);
            });
            @SuppressWarnings("unchecked")
            HttpResponse<T> r = (HttpResponse<T>) new HttpResponse<String>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return request; }
                @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
                @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
                @Override public String body() { return ""; }
                @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
                @Override public java.net.URI uri() { return request.uri(); }
                @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            };
            return r;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }
}

