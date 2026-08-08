package org.remus.giteabot.eventhook;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronous HTTP fan-out for persisted {@link EventHookDelivery} rows.
 * Re-loads the delivery by id inside its own transaction (entities never
 * cross the {@code @Async} boundary), POSTs the payload via {@link RestClient}
 * over Apache HttpClient 5, and records the outcome on the row.
 *
 * <p>Success = any 2xx. Everything else (non-2xx, timeouts, TLS failures,
 * unknown hosts) is a failed attempt: the row goes {@code RETRYING} with an
 * exponential-backoff {@code nextAttemptAt}, or {@code FAILED} once
 * {@code retry.max-attempts} is exhausted. The sweeper drives retries, so
 * delivery is at-least-once.
 *
 * <p>Credentials: the optional HMAC signature and static Authorization header
 * are decrypted at delivery time only, never logged at INFO+ and never copied
 * into the delivery row.
 */
@Slf4j
@Service
public class EventHookDeliveryWorker {

    private final EventHookProperties properties;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookEndpointRepository endpointRepository;
    private final EventHookEndpointService endpointService;
    private final EventHookSignatureService signatureService;
    private final ObjectMapper objectMapper;
    private final RestClient defaultClient;
    private final RestClient insecureClient;

    /** Endpoints already WARN-logged for disabled TLS verification (log once per endpoint). */
    private final Set<Long> tlsWarnedEndpoints = ConcurrentHashMap.newKeySet();

    @Autowired
    public EventHookDeliveryWorker(EventHookProperties properties,
                                   EventHookDeliveryRepository deliveryRepository,
                                   EventHookEndpointRepository endpointRepository,
                                   EventHookEndpointService endpointService,
                                   EventHookSignatureService signatureService,
                                   ObjectMapper objectMapper) {
        this(properties, deliveryRepository, endpointRepository, endpointService,
                signatureService, objectMapper,
                buildClient(properties, false), buildClient(properties, true));
    }

    /** Test seam: injects pre-built clients so routing decisions can be verified. */
    EventHookDeliveryWorker(EventHookProperties properties,
                            EventHookDeliveryRepository deliveryRepository,
                            EventHookEndpointRepository endpointRepository,
                            EventHookEndpointService endpointService,
                            EventHookSignatureService signatureService,
                            ObjectMapper objectMapper,
                            RestClient defaultClient,
                            RestClient insecureClient) {
        this.properties = properties;
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.endpointService = endpointService;
        this.signatureService = signatureService;
        this.objectMapper = objectMapper;
        this.defaultClient = defaultClient;
        this.insecureClient = insecureClient;
    }

    @Async
    @Transactional
    public void deliverAsync(Long deliveryId) {
        EventHookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == DeliveryStatus.SUCCESS) {
            return;
        }
        EventHookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            log.warn("Delivery {} references missing endpoint {}; marking FAILED",
                    deliveryId, delivery.getEndpointId());
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setLastError("endpoint no longer exists");
            delivery.setCompletedAt(Instant.now());
            deliveryRepository.save(delivery);
            return;
        }

        byte[] body = delivery.getPayloadJson().getBytes(StandardCharsets.UTF_8);
        delivery.setAttempts(delivery.getAttempts() + 1);
        // Credentials are decrypted at delivery time only; never logged, never persisted on the row.
        String secret = endpointService.decryptSecret(endpoint);
        String authorization = endpointService.decryptAuthorizationHeader(endpoint);
        try {
            RestClient.RequestBodySpec request = clientFor(endpoint).post()
                    .uri(URI.create(endpoint.getUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(EventHookSignatureService.EVENT_HEADER, delivery.getEventType())
                    .header(EventHookSignatureService.DELIVERY_HEADER, delivery.getDeliveryUuid())
                    .headers(h -> {
                        // custom_headers are stored encrypted - decrypt only at delivery time.
                        String customHeadersJson = endpointService.decryptCustomHeaders(endpoint);
                        if (customHeadersJson != null && !customHeadersJson.isBlank()) {
                            endpoint.parsedCustomHeaders(objectMapper, customHeadersJson).forEach(h::add);
                        }
                        // Static Authorization wins over any conflicting custom header.
                        if (authorization != null) {
                            h.set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    })
                    .body(body);
            // Optional HMAC signature — skipped entirely when the endpoint has no secret.
            if (secret != null) {
                request = request.header(EventHookSignatureService.SIGNATURE_HEADER,
                        signatureService.sign(body, secret));
            }
            ResponseEntity<Void> response = request.retrieve().toBodilessEntity();
            delivery.setStatus(DeliveryStatus.SUCCESS);
            delivery.setLastResponseCode(response.getStatusCode().value());
            delivery.setCompletedAt(Instant.now());
            delivery.setLastError(null);
        } catch (Exception e) {
            Integer code = (e instanceof RestClientResponseException rce)
                    ? rce.getStatusCode().value() : null;
            markFailure(delivery, code, e.getMessage());
            log.debug("Webhook delivery {} failed (attempt {}): {}",
                    delivery.getDeliveryUuid(), delivery.getAttempts(), e.getMessage());
        }
        deliveryRepository.save(delivery);
    }

    RestClient clientFor(EventHookEndpoint endpoint) {
        if (endpoint.isSkipTlsVerify()) {
            if (tlsWarnedEndpoints.add(endpoint.getId())) {
                log.warn("Endpoint '{}' ({}) has TLS certificate verification DISABLED — "
                        + "insecure, use only for self-signed/internal targets",
                        endpoint.getName(), endpoint.getUrl());
            }
            return insecureClient;
        }
        return defaultClient;
    }

    private void markFailure(EventHookDelivery d, Integer code, String message) {
        d.setLastResponseCode(code);
        d.setLastError(message == null ? "unknown"
                : message.substring(0, Math.min(message.length(), 2000)));
        if (d.getAttempts() >= properties.getRetry().getMaxAttempts()) {
            d.setStatus(DeliveryStatus.FAILED);
            d.setCompletedAt(Instant.now());
        } else {
            d.setStatus(DeliveryStatus.RETRYING);
            d.setNextAttemptAt(Instant.now().plus(properties.backoffForAttempt(d.getAttempts())));
        }
    }

    private static RestClient buildClient(EventHookProperties properties, boolean trustAll) {
        try {
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(Timeout.of(properties.getConnectTimeout()))
                    .setResponseTimeout(Timeout.of(properties.getReadTimeout()))
                    .build();
            var builder = HttpClients.custom().setDefaultRequestConfig(requestConfig);
            if (trustAll) {
                SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                        .build();
                builder.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                .setSslContext(sslContext)
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                .build())
                        .build());
            }
            CloseableHttpClient httpClient = builder.build();
            return RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build webhook HTTP client", e);
        }
    }
}
