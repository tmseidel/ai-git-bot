package org.remus.giteabot.eventhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.EncryptionService;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Drives the worker against a JDK {@link HttpServer} receiver on an ephemeral
 * port (no WireMock — the build has a dependency whitelist).
 */
@ExtendWith(MockitoExtension.class)
class EventHookDeliveryWorkerTest {

    @Mock
    private EventHookDeliveryRepository deliveryRepository;
    @Mock
    private EventHookEndpointRepository endpointRepository;

    private final EventHookProperties properties = new EventHookProperties();
    private final EncryptionService encryptionService = new EncryptionService("test-key");
    private final EventHookSignatureService signatureService = new EventHookSignatureService();

    private HttpServer server;
    private String url;
    private volatile int responseStatus = 200;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    private record ReceivedRequest(String method, Map<String, List<String>> headers, String body) {
    }

    @BeforeEach
    void startReceiver() throws IOException {
        received.clear();
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            received.add(new ReceivedRequest(exchange.getRequestMethod(), headers,
                    new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private EventHookDeliveryWorker newWorker() {
        return new EventHookDeliveryWorker(properties, deliveryRepository, endpointRepository,
                new EventHookEndpointService(endpointRepository, encryptionService),
                signatureService, new ObjectMapper());
    }

    private EventHookEndpoint endpoint(boolean skipTlsVerify) {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setId(1L);
        endpoint.setName("test-endpoint");
        endpoint.setUrl(url);
        endpoint.setEnabled(true);
        endpoint.setEventTypes("PR_WORKFLOW_COMPLETED");
        endpoint.setSkipTlsVerify(skipTlsVerify);
        return endpoint;
    }

    private EventHookDelivery delivery(EventHookEndpoint endpoint) {
        EventHookDelivery delivery = new EventHookDelivery();
        delivery.setId(100L);
        delivery.setDeliveryUuid("uuid-100");
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventType(EventHookEventType.PR_WORKFLOW_COMPLETED.wireValue());
        delivery.setPayloadJson("{\"hello\":\"world\"}");
        delivery.setStatus(DeliveryStatus.PENDING);
        return delivery;
    }

    private void stubLoad(EventHookDelivery delivery, EventHookEndpoint endpoint) {
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        lenient().when(deliveryRepository.save(any(EventHookDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        if (endpoint != null) {
            lenient().when(endpointRepository.findById(endpoint.getId()))
                    .thenReturn(Optional.of(endpoint));
        }
    }

    private static String firstHeader(ReceivedRequest request, String name) {
        List<String> values = request.headers().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    @Test
    void deliver_200_successWithVerifiableSignature() {
        EventHookEndpoint endpoint = endpoint(false);
        endpoint.setSecret(encryptionService.encrypt("s3cret"));
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertEquals(200, delivery.getLastResponseCode());
        assertEquals(1, delivery.getAttempts());
        assertNotNull(delivery.getCompletedAt());

        assertEquals(1, received.size());
        ReceivedRequest request = received.get(0);
        assertEquals("POST", request.method());
        assertEquals(delivery.getPayloadJson(), request.body());
        assertEquals("prworkflow.completed", firstHeader(request, "X-EventHook-Event"));
        assertEquals("uuid-100", firstHeader(request, "X-EventHook-Delivery"));
        // Receiver-side verification: recompute the HMAC over the raw body with the shared secret.
        assertEquals(signatureService.sign(request.body().getBytes(StandardCharsets.UTF_8), "s3cret"),
                firstHeader(request, "X-EventHook-Signature-256"));
    }

    @Test
    void deliver_endpointWithoutSecret_sendsNoSignatureHeader() {
        EventHookEndpoint endpoint = endpoint(false);
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertEquals(1, received.size());
        assertNull(firstHeader(received.get(0), "X-EventHook-Signature-256"));
    }

    @Test
    void deliver_encryptedCustomHeadersAndAuthorizationHeader() {
        EventHookEndpoint endpoint = endpoint(false);
        endpoint.setAuthorizationHeader(encryptionService.encrypt("Bearer token123456"));
        endpoint.setCustomHeaders(encryptionService.encrypt("{\"Authorization\": \"Bearer wrong\", \"X-Custom\": \"yes\"}"));
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        ReceivedRequest request = received.get(0);
        assertEquals("Bearer token123456", firstHeader(request, "Authorization"));
        assertEquals("yes", firstHeader(request, "X-Custom"));
    }

    @Test
    void deliver_500_retryingWithBackoff() {
        responseStatus = 500;
        EventHookEndpoint endpoint = endpoint(false);
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        Instant before = Instant.now();
        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.RETRYING, delivery.getStatus());
        assertEquals(500, delivery.getLastResponseCode());
        assertEquals(1, delivery.getAttempts());
        assertNotNull(delivery.getNextAttemptAt());
        // Default initial backoff is 30s for attempt 1.
        assertFalse(delivery.getNextAttemptAt().isBefore(before.plusSeconds(29)));
        assertFalse(delivery.getNextAttemptAt().isAfter(Instant.now().plusSeconds(31)));
        assertNull(delivery.getCompletedAt());
    }

    @Test
    void deliver_maxAttemptsExhausted_failed() {
        responseStatus = 500;
        EventHookEndpoint endpoint = endpoint(false);
        EventHookDelivery delivery = delivery(endpoint);
        delivery.setAttempts(properties.getRetry().getMaxAttempts() - 1);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(properties.getRetry().getMaxAttempts(), delivery.getAttempts());
        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertNotNull(delivery.getCompletedAt());
    }

    @Test
    void deliver_connectionRefused_retryingWithError_noException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        EventHookEndpoint endpoint = endpoint(false);
        endpoint.setUrl("http://127.0.0.1:" + closedPort + "/hook");
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        assertDoesNotThrow(() -> newWorker().deliverAsync(delivery.getId()));

        assertEquals(DeliveryStatus.RETRYING, delivery.getStatus());
        assertNull(delivery.getLastResponseCode());
        assertNotNull(delivery.getLastError());
        assertNotNull(delivery.getNextAttemptAt());
    }

    @Test
    void deliver_missingEndpoint_markedFailed() {
        EventHookDelivery delivery = delivery(endpoint(false));
        stubLoad(delivery, null);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertEquals("endpoint no longer exists", delivery.getLastError());
        assertNotNull(delivery.getCompletedAt());
    }

    @Test
    void clientFor_skipTlsVerifyRoutesToInsecureClient() {
        RestClient defaultClient = RestClient.builder().build();
        RestClient insecureClient = RestClient.builder().build();
        EventHookDeliveryWorker worker = new EventHookDeliveryWorker(properties, deliveryRepository,
                endpointRepository, new EventHookEndpointService(endpointRepository, encryptionService),
                signatureService, new ObjectMapper(), defaultClient, insecureClient);

        assertSame(insecureClient, worker.clientFor(endpoint(true)));
        assertSame(defaultClient, worker.clientFor(endpoint(false)));
    }

    @Test
    void deliver_skipTlsVerifyEndpoint_deliversViaInsecureClient() {
        // The trust-all client also speaks plain HTTP; a successful round-trip proves
        // the insecure client path is functional end to end.
        EventHookEndpoint endpoint = endpoint(true);
        EventHookDelivery delivery = delivery(endpoint);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertEquals(1, received.size());
    }

    @Test
    void deliver_alreadySuccessfulDelivery_isNoOp() {
        EventHookEndpoint endpoint = endpoint(false);
        EventHookDelivery delivery = delivery(endpoint);
        delivery.setStatus(DeliveryStatus.SUCCESS);
        stubLoad(delivery, endpoint);

        newWorker().deliverAsync(delivery.getId());

        assertEquals(0, received.size());
        verify(deliveryRepository, never()).save(any());
    }
}
