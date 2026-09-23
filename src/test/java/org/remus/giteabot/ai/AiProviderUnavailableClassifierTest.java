package org.remus.giteabot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiRetryProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default {@link AiClient#isProviderUnavailableError(Throwable)} classification. */
class AiProviderUnavailableClassifierTest {

    private final AiClient client = new RetryAiClient(new NoopAiClient(),
            new AiRetryProperties(), new NoopNotifier());

    /** Leans on the interface default, exactly like the concrete provider clients do. */
    private static class NoopAiClient implements AiClient {
        @Override
        public String submitReviewPrompt(String s, String m, String u) {
            return "";
        }

        @Override
        public String chat(List<AiMessage> h, String u, String s, String m) {
            return "";
        }

        @Override
        public String chat(List<AiMessage> h, String u, String s, String m, Integer t) {
            return "";
        }

        @Override
        public void reportError(Throwable error) {
        }

        @Override
        public String getModel() {
            return "";
        }
    }

    private static class NoopNotifier implements ProviderRetryNotifier {
        @Override
        public void retryScheduled(Event event) {
        }

        @Override
        public void retriesExhausted(Event event) {
        }
    }

    @AfterEach
    void clearContext() {
        AiRetryContext.clear();
    }

    private static HttpServerErrorException serverError(HttpStatusCode status, String body) {
        return HttpServerErrorException.create(status, status.toString(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.toString(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void http503IsUnavailable() {
        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatus.SERVICE_UNAVAILABLE, "upstream down")));
    }

    @Test
    void http529IsUnavailable() {
        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatusCode.valueOf(529), "{\"error\":{\"type\":\"overloaded_error\"}}")));
    }

    @Test
    void googleStyleUnavailableStatusIsUnavailable() {
        String body = """
                { "error": { "code": 503, "message": "This model is currently experiencing high \
                demand.", "status": "UNAVAILABLE" }}""";

        assertTrue(client.isProviderUnavailableError(serverError(
                HttpStatus.SERVICE_UNAVAILABLE, body)));
    }

    @Test
    void overloadMarkersOnOther5xxAreUnavailable() {
        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatus.INTERNAL_SERVER_ERROR, "the model is overloaded, retry later")));
        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatus.BAD_GATEWAY, "Service Temporarily Unavailable")));
        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatus.INTERNAL_SERVER_ERROR, "Over capacity — please try again")));
    }

    @Test
    void googleStyleUnavailableStatusOnA500IsUnavailable() {
        // A gateway that downgrades the transient Google status to 500.
        String body = """
                { "error": { "code": 503, "message": "This model is currently experiencing high \
                demand.", "status": "UNAVAILABLE" }}""";

        assertTrue(client.isProviderUnavailableError(
                serverError(HttpStatus.INTERNAL_SERVER_ERROR, body)));
    }

    @Test
    void bareUnavailableOnA500IsNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(serverError(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"error\":{\"message\":\"requested model unavailable\"}}")));
        assertFalse(client.isProviderUnavailableError(serverError(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"error\":{\"message\":\"feature unavailable for this account\"}}")));
    }

    @Test
    void fourXxIsNeverUnavailableEvenWithOverloadWording() {
        assertFalse(client.isProviderUnavailableError(clientError(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"type\":\"overloaded_error\"}}")));
        assertFalse(client.isProviderUnavailableError(clientError(HttpStatus.NOT_FOUND,
                "{\"error\":{\"message\":\"model unavailable\"}}")));
        assertFalse(client.isProviderUnavailableError(clientError(HttpStatus.TOO_MANY_REQUESTS,
                "the provider is overloaded — rate limit exceeded")));
    }

    @Test
    void wrappedCauseIsInspected() {
        RuntimeException wrapped = new IllegalStateException("Google AI request failed: 503",
                serverError(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));

        assertTrue(client.isProviderUnavailableError(wrapped));
    }

    @Test
    void plainMessageNaming503IsUnavailable() {
        assertTrue(client.isProviderUnavailableError(
                new IllegalStateException("503 Service Unavailable: upstream overloaded")));
    }

    @Test
    void rateLimitsAreNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(clientError(HttpStatus.TOO_MANY_REQUESTS,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")));
    }

    @Test
    void promptTooLongIsNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(clientError(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"prompt is too long: 250000 tokens\"}}")));
    }

    @Test
    void authFailuresAreNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(
                clientError(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"invalid api key\"}}")));
    }

    @Test
    void unrelatedServerErrorIsNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(
                serverError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"NullPointerException\"}")));
    }

    @Test
    void transientNetworkFailuresAreNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(new ResourceAccessException("Read timed out")));
    }

    @Test
    void plainMessageWithoutAStatusIsNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(new IllegalStateException("AI client unavailable")));
    }

    @Test
    void nullErrorIsNotUnavailable() {
        assertFalse(client.isProviderUnavailableError(null));
    }
}
