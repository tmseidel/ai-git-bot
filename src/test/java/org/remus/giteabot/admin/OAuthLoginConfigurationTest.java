package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthLoginConfigurationTest {

    private OAuthLoginConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new OAuthLoginConfiguration();
        set("clientId", "client-id");
        set("clientSecret", "client-secret");
        set("authorizationUri", "https://login.example.test/authorize");
        set("tokenUri", "https://login.example.test/token");
        set("userInfoUri", "https://login.example.test/userinfo");
        set("jwkSetUri", "https://login.example.test/keys");
        set("userNameAttribute", "sub");
        set("redirectUri", "{baseUrl}/login/oauth2/code/giteabot");
        set("scope", "openid,profile");
    }

    @Test
    void validConfigurationCreatesAuthorizationCodeRegistrationWithOpenIdScopes() {
        configuration.validateConfiguration();

        ClientRegistrationRepository repository = configuration.clientRegistrationRepository();
        var registration = repository.findByRegistrationId(OAuthLoginConfiguration.REGISTRATION_ID);

        assertEquals("client-id", registration.getClientId());
        assertEquals("https://login.example.test/authorize", registration.getProviderDetails().getAuthorizationUri());
        assertEquals("https://login.example.test/token", registration.getProviderDetails().getTokenUri());
        assertEquals("https://login.example.test/userinfo", registration.getProviderDetails().getUserInfoEndpoint().getUri());
        assertEquals("https://login.example.test/keys", registration.getProviderDetails().getJwkSetUri());
        assertEquals("sub", registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
        assertTrue(registration.getScopes().contains("openid"));
        assertTrue(registration.getScopes().contains("profile"));
    }

    @Test
    void missingOAuthValueProducesClearStartupError() {
        set("tokenUri", " ");

        IllegalStateException error = assertThrows(IllegalStateException.class, configuration::validateConfiguration);

        assertTrue(error.getMessage().contains("OAuth login is selected"));
        assertTrue(error.getMessage().contains("giteabot.security.oauth.token-uri"));
    }

    @Test
    void openIdScopeRequiresJwkSetUriForIdTokenValidation() {
        set("jwkSetUri", " ");

        IllegalStateException error = assertThrows(IllegalStateException.class, configuration::validateConfiguration);

        assertTrue(error.getMessage().contains("giteabot.security.oauth.jwk-set-uri"));
    }

    @Test
    void trimsWhitespaceAroundJwkSetUri() {
        set("jwkSetUri", " https://login.example.test/keys\r\n");

        configuration.validateConfiguration();

        assertEquals("https://login.example.test/keys", configuration.clientRegistrationRepository()
                .findByRegistrationId(OAuthLoginConfiguration.REGISTRATION_ID)
                .getProviderDetails().getJwkSetUri());
    }

    @Test
    void keycloakStyleIssuerDiscoveryDoesNotRequireExplicitProviderEndpoints() {
        set("issuerUri", "https://keycloak.example.test/realms/engineering");
        set("authorizationUri", "");
        set("tokenUri", "");
        set("userInfoUri", "");
        set("jwkSetUri", "");

        configuration.validateConfiguration();
    }

    private void set(String field, String value) {
        ReflectionTestUtils.setField(configuration, field, value);
    }
}
