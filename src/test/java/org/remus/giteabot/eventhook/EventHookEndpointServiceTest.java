package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.EncryptionService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventHookEndpointServiceTest {

    @Mock
    private EventHookEndpointRepository endpointRepository;

    /** Real encryption (test key) so ciphertext round-trips are exercised end to end. */
    private EventHookEndpointService service;

    @BeforeEach
    void setUp() {
        service = new EventHookEndpointService(endpointRepository, new EncryptionService("test-key"));
        lenient().when(endpointRepository.save(any(EventHookEndpoint.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void save_encryptsCredentials_storedValuesAreNotPlaintext() {
        EventHookEndpoint endpoint = new EventHookEndpoint();

        EventHookEndpoint saved = service.save(endpoint, "plain-secret", "Bearer token123456");

        assertNotNull(saved.getSecret());
        assertNotNull(saved.getAuthorizationHeader());
        assertNotEquals("plain-secret", saved.getSecret());
        assertNotEquals("Bearer token123456", saved.getAuthorizationHeader());
    }

    @Test
    void save_thenDecrypt_roundTripsToPlaintext() {
        EventHookEndpoint saved = service.save(new EventHookEndpoint(), "plain-secret", "Bearer token123456");

        assertEquals("plain-secret", service.decryptSecret(saved));
        assertEquals("Bearer token123456", service.decryptAuthorizationHeader(saved));
    }

    @Test
    void save_encryptsCustomHeaders_andDecryptsThemForDelivery() {
        String customHeaders = "{\"X-Api-Key\":\"token123456\"}";

        EventHookEndpoint saved = service.save(new EventHookEndpoint(), null, null, customHeaders);

        assertNotEquals(customHeaders, saved.getCustomHeaders());
        assertEquals(customHeaders, service.decryptCustomHeaders(saved));
    }

    @Test
    void save_blankCredentialsOnEdit_keepExistingCiphertext() {
        EventHookEndpoint endpoint = service.save(new EventHookEndpoint(), "plain-secret", "Bearer token123456");
        String secretCiphertext = endpoint.getSecret();
        String authCiphertext = endpoint.getAuthorizationHeader();

        EventHookEndpoint resaved = service.save(endpoint, "", "   ");

        assertEquals(secretCiphertext, resaved.getSecret());
        assertEquals(authCiphertext, resaved.getAuthorizationHeader());
        assertEquals("plain-secret", service.decryptSecret(resaved));
    }

    @Test
    void save_nullCredentialsOnEdit_keepExistingCiphertext() {
        EventHookEndpoint endpoint = service.save(new EventHookEndpoint(), "plain-secret", "Bearer token123456");
        String secretCiphertext = endpoint.getSecret();
        String authCiphertext = endpoint.getAuthorizationHeader();

        EventHookEndpoint resaved = service.save(endpoint, null, null);

        assertEquals(secretCiphertext, resaved.getSecret());
        assertEquals(authCiphertext, resaved.getAuthorizationHeader());
    }

    @Test
    void save_blankCustomHeadersOnEdit_keepsExistingCiphertext() {
        EventHookEndpoint endpoint = service.save(new EventHookEndpoint(), null, null, "{\"X-Test\":\"value\"}");
        String customHeadersCiphertext = endpoint.getCustomHeaders();

        EventHookEndpoint resaved = service.save(endpoint, null, null, "  ");

        assertEquals(customHeadersCiphertext, resaved.getCustomHeaders());
    }

    @Test
    void save_onlySecretProvided_leavesAuthorizationHeaderNull() {
        EventHookEndpoint saved = service.save(new EventHookEndpoint(), "plain-secret", null);

        assertNotNull(saved.getSecret());
        assertNull(saved.getAuthorizationHeader());
        assertNull(service.decryptAuthorizationHeader(saved));
    }

    @Test
    void decrypt_nullCredentials_returnNull() {
        EventHookEndpoint endpoint = new EventHookEndpoint();

        assertNull(service.decryptSecret(endpoint));
        assertNull(service.decryptAuthorizationHeader(endpoint));
    }

    @Test
    void decrypt_blankCredentials_returnNull() {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setSecret("  ");
        endpoint.setAuthorizationHeader("");

        assertNull(service.decryptSecret(endpoint));
        assertNull(service.decryptAuthorizationHeader(endpoint));
    }

    @Test
    void deleteById_delegatesToRepository() {
        service.deleteById(7L);

        verify(endpointRepository).deleteById(7L);
    }
}
