package org.remus.giteabot.eventhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for {@link EventHookEndpoint}, handling the {@code secret} and
 * {@code authorizationHeader} credentials exactly like
 * {@code AiIntegrationService} handles {@code apiKey}: encrypt on save,
 * decrypt on read, via the shared {@link EncryptionService}.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EventHookEndpointService {

    private final EventHookEndpointRepository endpointRepository;
    private final EncryptionService encryptionService;

    @Transactional(readOnly = true)
    public List<EventHookEndpoint> findAll() {
        return endpointRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<EventHookEndpoint> findById(Long id) {
        return endpointRepository.findById(id);
    }

    /** Backwards-compatible overload without custom headers. */
    public EventHookEndpoint save(EventHookEndpoint endpoint,
                                  String plainSecret, String plainAuthorizationHeader) {
        return save(endpoint, plainSecret, plainAuthorizationHeader, null);
    }

    /**
     * Blank credential inputs on edit mean "keep current" (mirrors the
     * ai-integrations form contract). All three credentials are optional: an
     * endpoint may have none, any, or all of them.
     */
    public EventHookEndpoint save(EventHookEndpoint endpoint,
                                  String plainSecret, String plainAuthorizationHeader,
                                  String plainCustomHeaders) {
        if (plainSecret != null && !plainSecret.isBlank()) {
            endpoint.setSecret(encryptionService.encrypt(plainSecret));
        }
        if (plainAuthorizationHeader != null && !plainAuthorizationHeader.isBlank()) {
            endpoint.setAuthorizationHeader(encryptionService.encrypt(plainAuthorizationHeader));
        }
        if (plainCustomHeaders != null && !plainCustomHeaders.isBlank()) {
            endpoint.setCustomHeaders(encryptionService.encrypt(plainCustomHeaders));
        }
        return endpointRepository.save(endpoint);
    }

    public void deleteById(Long id) {
        endpointRepository.deleteById(id);
    }

    /** Plaintext secret for HMAC signing, or null when the endpoint signs nothing. */
    public String decryptSecret(EventHookEndpoint endpoint) {
        String secret = endpoint.getSecret();
        return (secret == null || secret.isBlank()) ? null : encryptionService.decrypt(secret);
    }

    /** Plaintext static Authorization header value (e.g. "Bearer token123456"), or null when unset. */
    public String decryptAuthorizationHeader(EventHookEndpoint endpoint) {
        String header = endpoint.getAuthorizationHeader();
        return (header == null || header.isBlank()) ? null : encryptionService.decrypt(header);
    }

    /** Plaintext custom-headers JSON object, or null when unset. */
    public String decryptCustomHeaders(EventHookEndpoint endpoint) {
        String headers = endpoint.getCustomHeaders();
        return (headers == null || headers.isBlank()) ? null : encryptionService.decrypt(headers);
    }
}
