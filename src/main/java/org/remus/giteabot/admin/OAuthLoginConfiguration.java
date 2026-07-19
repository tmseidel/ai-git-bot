package org.remus.giteabot.admin;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates the single OAuth client registration used by the web UI.
 *
 * <p>OAuth users are deliberately not looked up in {@link AdminUserRepository}:
 * a successfully authenticated user is an application administrator.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "oauth")
public class OAuthLoginConfiguration {

    public static final String REGISTRATION_ID = "giteabot";

    @Value("${giteabot.security.oauth.client-id:}")
    private String clientId;

    @Value("${giteabot.security.oauth.client-secret:}")
    private String clientSecret;

    @Value("${giteabot.security.oauth.issuer-uri:}")
    private String issuerUri;

    @Value("${giteabot.security.oauth.authorization-uri:}")
    private String authorizationUri;

    @Value("${giteabot.security.oauth.token-uri:}")
    private String tokenUri;

    @Value("${giteabot.security.oauth.user-info-uri:}")
    private String userInfoUri;

    @Value("${giteabot.security.oauth.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${giteabot.security.oauth.user-name-attribute:sub}")
    private String userNameAttribute;

    @Value("${giteabot.security.oauth.redirect-uri:{baseUrl}/login/oauth2/code/giteabot}")
    private String redirectUri;

    @Value("${giteabot.security.oauth.scope:openid,profile}")
    private String scope;

    @PostConstruct
    void validateConfiguration() {
        jwkSetUri = trim(jwkSetUri);
        issuerUri = trim(issuerUri);
        List<String> missing = Stream.of(
                required("giteabot.security.oauth.client-id", clientId),
                required("giteabot.security.oauth.client-secret", clientSecret),
                required("giteabot.security.oauth.user-name-attribute", userNameAttribute),
                required("giteabot.security.oauth.redirect-uri", redirectUri)
        ).filter(Objects::nonNull).toList();
        if (!issuerDiscoveryEnabled()) {
            missing = Stream.concat(missing.stream(), Stream.of(
                    required("giteabot.security.oauth.authorization-uri", authorizationUri),
                    required("giteabot.security.oauth.token-uri", tokenUri),
                    required("giteabot.security.oauth.user-info-uri", userInfoUri)
            ).filter(Objects::nonNull)).toList();
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("OAuth login is selected, but required configuration is missing: "
                    + String.join(", ", missing));
        }
        if (!issuerDiscoveryEnabled() && scopes().contains("openid") && (jwkSetUri == null || jwkSetUri.isBlank())) {
            throw new IllegalStateException("OAuth login requests the 'openid' scope, but required configuration is missing: "
                    + "giteabot.security.oauth.jwk-set-uri");
        }
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        ClientRegistration registration = issuerDiscoveryEnabled()
                ? discoveredRegistration()
                : explicitRegistration();
        return new InMemoryClientRegistrationRepository(registration);
    }

    private ClientRegistration discoveredRegistration() {
        try {
            return ClientRegistrations.fromIssuerLocation(issuerUri)
                    .registrationId(REGISTRATION_ID)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(redirectUri)
                    .scope(scopes())
                    .userNameAttributeName(userNameAttribute)
                    .clientName("AI Git Bot")
                    .build();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("OAuth issuer discovery failed for giteabot.security.oauth.issuer-uri '"
                    + issuerUri + "'", ex);
        }
    }

    private ClientRegistration explicitRegistration() {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(scopes())
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .userInfoUri(userInfoUri)
                .userNameAttributeName(userNameAttribute)
                .clientName("AI Git Bot")
                .build();
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            registration = ClientRegistration.withClientRegistration(registration)
                    .jwkSetUri(jwkSetUri)
                    .build();
        }
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "giteabot.security.oauth.debug-logging", name = "enabled", havingValue = "true")
    public OAuthDebugLoggingFilter oauthDebugLoggingFilter(
            @Value("${giteabot.security.oauth.debug-logging.max-body-length:16384}") int maxBodyLength) {
        return new OAuthDebugLoggingFilter(maxBodyLength);
    }

    private Set<String> scopes() {
        Set<String> scopes = new LinkedHashSet<>();
        Arrays.stream(scope.split("[\\s,]+"))
                .filter(value -> !value.isBlank())
                .forEach(scopes::add);
        return scopes;
    }

    private boolean issuerDiscoveryEnabled() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    private static String required(String property, String value) {
        return value == null || value.isBlank() ? property : null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
