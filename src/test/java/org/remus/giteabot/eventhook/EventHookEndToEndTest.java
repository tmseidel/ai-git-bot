package org.remus.giteabot.eventhook;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Full-stack integration test: {@link EventHookPublisher} → durable delivery
 * row (H2) → async {@link EventHookDeliveryWorker} → live HTTP receiver (JDK
 * {@link HttpServer} on an ephemeral port). Complements
 * {@code EventHookDeliveryWorkerTest}, which drives the worker in isolation
 * with mocked repositories — here the whole chain runs unwired from mocks,
 * including AES-GCM encrypt-at-rest / decrypt-on-read of the endpoint secret
 * (the test profile sets {@code app.encryption-key}).
 */
@SpringBootTest
@ActiveProfiles("test")
class EventHookEndToEndTest {

    private static final String SECRET = "it-secret";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private EventHookPublisher publisher;
    @Autowired
    private EventHookEndpointService endpointService;
    @Autowired
    private EventHookEndpointRepository endpointRepository;
    @Autowired
    private EventHookDeliveryRepository deliveryRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private final BlockingQueue<RecordedRequest> received = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String receiverUrl;

    record RecordedRequest(String body, Headers headers) {
    }

    @BeforeEach
    void startReceiver() throws IOException {
        clearTables();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new RecordedRequest(new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders()));
            byte[] ok = "{\"ok\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        receiverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void cleanUp() {
        server.stop(0);
        clearTables();
    }

    private void clearTables() {
        deliveryRepository.deleteAll();
        endpointRepository.deleteAll();
    }

    @Test
    void publish_deliversSignedEventEndToEnd() throws Exception {
        endpointService.save(endpoint(receiverUrl), SECRET, null);

        String storedSecret = endpointRepository.findAll().getFirst().getSecret();
        assertNotEquals(SECRET, storedSecret, "secret must be encrypted at rest");
        assertNotNull(storedSecret);

        publisher.publish(EventHookEventType.PR_WORKFLOW_COMPLETED, bot(), "acme", "payments-service",
                42L, null, Map.of("workflowKey", "review", "runId", 9182, "status", "SUCCESS"));

        RecordedRequest request = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(request, "no delivery arrived at the receiver within " + TIMEOUT);

        assertEquals("prworkflow.completed", request.headers().getFirst("X-EventHook-Event"));
        String expectedSignature = "sha256=" + hmacSha256Hex(SECRET,
                request.body().getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedSignature, request.headers().getFirst("X-EventHook-Signature-256"),
                "wire signature must match the plaintext secret although the DB holds ciphertext");

        JsonNode envelope = objectMapper.readTree(request.body());
        assertEquals(1, envelope.get("schemaVersion").asInt());
        assertEquals("prworkflow.completed", envelope.get("eventType").asText());
        assertEquals(request.headers().getFirst("X-EventHook-Delivery"), envelope.get("id").asText(),
                "delivery header and envelope id must agree (dedup key)");
        assertEquals("it-bot", envelope.at("/actor/id").asText());
        assertEquals("acme", envelope.at("/repository/owner").asText());
        assertEquals("payments-service", envelope.at("/repository/name").asText());
        assertEquals(42, envelope.at("/repository/pullRequest").asLong());
        assertEquals("review", envelope.at("/data/workflowKey").asText());

        EventHookDelivery delivery = awaitStatus(DeliveryStatus.SUCCESS);
        assertEquals(1, delivery.getAttempts());
        assertEquals(200, delivery.getLastResponseCode());
        assertNotNull(delivery.getCompletedAt());
    }

    @Test
    void publish_receiverDown_marksRetryingWithoutThrowing() throws IOException {
        // Bind-then-close yields a port that refuses connections.
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        endpointService.save(endpoint("http://127.0.0.1:" + closedPort + "/hook"), null, null);

        publisher.publish(EventHookEventType.ISSUE_ASSIGNMENT_STARTED, bot(), "acme", "payments-service",
                null, 12L, Map.of("issueNumber", 12, "issueTitle", "Vague issue"));

        EventHookDelivery delivery = awaitStatus(DeliveryStatus.RETRYING);
        assertEquals(1, delivery.getAttempts());
        assertNotNull(delivery.getNextAttemptAt(), "retry must be scheduled");
        assertNotNull(delivery.getLastError(), "failure reason must be recorded");
    }

    private EventHookDelivery awaitStatus(DeliveryStatus status) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            List<EventHookDelivery> all = deliveryRepository.findAll();
            if (!all.isEmpty() && all.getFirst().getStatus() == status) {
                return all.getFirst();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("delivery did not reach status " + status + " within " + TIMEOUT
                + " (rows: " + deliveryRepository.findAll() + ")");
        return null; // unreachable
    }

    private static EventHookEndpoint endpoint(String url) {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setName("IT receiver");
        endpoint.setUrl(url);
        endpoint.setEventTypes("PR_WORKFLOW_COMPLETED,ISSUE_ASSIGNMENT_STARTED");
        endpoint.setEnabled(true);
        return endpoint;
    }

    /** Transient bot — the publisher only reads it (id, name, git integration). */
    private static Bot bot() {
        Bot bot = new Bot();
        bot.setName("it-bot");
        return bot;
    }

    private static String hmacSha256Hex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
