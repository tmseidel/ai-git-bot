package org.remus.giteabot.ai.openrouter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationRepository;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OpenRouterConfigurationTest {
    private MockRestServiceServer server;
    @Mock private AiIntegrationRepository repository;
    @Mock private EncryptionService encryption;
    @Mock private ObjectProvider<RestClient.Builder> builders;
    private AiIntegrationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        when(builders.getObject()).thenReturn(http);
        service = new AiIntegrationService(repository, encryption,
                new AiProviderRegistry(List.of(new OpenRouterProviderMetadata(builders, HttpClientSettings.defaults()))));
        server = MockRestServiceServer.bindTo(http).build();
    }

    @Test
    void saveChecksTheKeyAtTheOfficialHost() {
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andRespond(withSuccess("""
                        {"data":{"is_management_key":false,"is_provisioning_key":false,
                         "allowed_data_regions":["global","europe","us"],"unknown_field":"ignored"}}
                        """, MediaType.APPLICATION_JSON));
        when(encryption.encrypt("test-key")).thenReturn("encrypted-key");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        AiIntegration integration = integration();

        AiIntegration saved = service.save(integration);

        assertThat(saved.getApiUrl()).isEqualTo("https://openrouter.ai/api");
        assertThat(saved.getApiKey()).isEqualTo("encrypted-key");
        verify(encryption).encrypt("test-key");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"data\":{}}",
            "{\"data\":{\"is_management_key\":true,\"is_provisioning_key\":false}}",
            "{\"data\":{\"is_management_key\":false,\"is_provisioning_key\":true}}",
            "{\"data\":{\"is_management_key\":\"false\",\"is_provisioning_key\":false}}",
            "{\"data\":{\"is_management_key\":false}}"
    })
    void unverifiedOrAdministrativeKeysAreNotEncryptedOrSaved(String response) {
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.save(integration())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inference key");

        verifyNoInteractions(encryption, repository);
        server.verify();
    }

    @Test
    void globalRouteNeedsAccountEligibility() {
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
                .andRespond(withSuccess("""
                        {"data":{"is_management_key":false,"is_provisioning_key":false,"allowed_data_regions":["europe"]}}
                        """, MediaType.APPLICATION_JSON));
        AiIntegration integration = integration();

        assertThatThrownBy(() -> service.save(integration)).hasMessageContaining("global routing");

        verifyNoInteractions(encryption, repository);
        server.verify();
    }

    @Test
    void keyErrorsExposeOnlyASafeStatus() {
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive-account-and-key-data"));

        assertThatThrownBy(() -> service.save(integration())).hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("sensitive").hasNoCause();

        verifyNoInteractions(encryption, repository);
        server.verify();
    }

    @Test
    void explicitClearRemovesTheKeyWithoutMakingAnAuthenticatedRequest() {
        AiIntegration integration = integration();
        integration.setId(1L);
        integration.setApiKey("");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        AiIntegration saved = service.save(integration, true);

        assertThat(saved.getApiKey()).isNull();
        assertThat(saved.getApiUrl()).isEqualTo("https://openrouter.ai/api");
        verifyNoInteractions(encryption);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "cap", "context", "flavor", "key"})
    void invalidSettingsDoNotSendCredentialsOrPersist(String invalidSetting) {
        AiIntegration integration = integration();
        switch (invalidSetting) {
            case "model" -> integration.setModel("");
            case "cap" -> integration.setMaxTokens(0);
            case "context" -> integration.setContextWindowTokens(0);
            case "flavor" -> integration.setModelFlavor("no_reasoning");
            case "key" -> integration.setApiKey("");
            default -> throw new AssertionError(invalidSetting);
        }

        assertThatThrownBy(() -> service.save(integration)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, encryption);
        server.verify();
    }

    @Test
    void blankKeyRevalidatesTheStoredKeyWithoutEncryptingItAgain() {
        AiIntegration existing = integration();
        existing.setApiKey("stored-ciphertext");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(encryption.decrypt("stored-ciphertext")).thenReturn("retained-key");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer retained-key"))
                .andRespond(withSuccess("""
                        {"data":{"is_management_key":false,"is_provisioning_key":false,"allowed_data_regions":["global"]}}
                        """, MediaType.APPLICATION_JSON));
        AiIntegration updated = integration();
        updated.setId(1L);
        updated.setApiKey("");

        assertThat(service.save(updated).getApiKey()).isEqualTo("stored-ciphertext");

        verify(encryption, never()).encrypt(any());
        server.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redirectCannotForwardTheKeyEvenWhenGlobalRedirectsAreEnabled() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger forwarded = new AtomicInteger();
        local.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://localhost:" + local.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        local.createContext("/target", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        local.start();
        try {
            ObjectProvider<RestClient.Builder> builders = mock(ObjectProvider.class);
            when(builders.getObject()).thenReturn(RestClient.builder());
            var provider = new OpenRouterProviderMetadata(builders,
                    HttpClientSettings.defaults().withRedirects(HttpRedirects.FOLLOW));
            var client = provider.buildRestClient(integration(), "test-key");

            // Use an absolute local URI to exercise the real transport without contacting OpenRouter.
            assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:" + local.getAddress().getPort() + "/redirect")
                    .retrieve().body(String.class)).hasMessage("OpenRouter redirects are not allowed");
            assertThat(forwarded).hasValue(0);
        } finally {
            local.stop(0);
        }
    }

    private static AiIntegration integration() {
        AiIntegration integration = new AiIntegration();
        integration.setProviderType("openrouter");
        integration.setName("OpenRouter test");
        integration.setModel("author/test-model");
        integration.setApiUrl("https://untrusted.example");
        integration.setApiKey("test-key");
        return integration;
    }
}
