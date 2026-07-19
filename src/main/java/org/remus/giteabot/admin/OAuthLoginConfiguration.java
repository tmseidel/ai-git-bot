package org.remus.giteabot.admin;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(OAuthLoginProperties.class)
public class OAuthLoginConfiguration {

    public static final String REGISTRATION_ID = "giteabot";

    private final OAuthLoginProperties properties;

    public OAuthLoginConfiguration(OAuthLoginProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validateConfiguration() {
        properties.setJwkSetUri(trim(properties.getJwkSetUri()));
        properties.setIssuerUri(trim(properties.getIssuerUri()));

        List<String> missing = Stream.of(
                required("giteabot.security.oauth.client-id", properties.getClientId()),
                required("giteabot.security.oauth.client-secret", properties.getClientSecret()),
                required("giteabot.security.oauth.user-name-attribute", properties.getUserNameAttribute()),
                required("giteabot.security.oauth.redirect-uri", properties.getRedirectUri())
        ).filter(Objects::nonNull).toList();
        if (!issuerDiscoveryEnabled()) {
            missing = Stream.concat(missing.stream(), Stream.of(
                    required("giteabot.security.oauth.authorization-uri", properties.getAuthorizationUri()),
                    required("giteabot.security.oauth.token-uri", properties.getTokenUri()),
                    required("giteabot.security.oauth.user-info-uri", properties.getUserInfoUri())
            ).filter(Objects::nonNull)).toList();
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("OAuth login is selected, but required configuration is missing: "
                    + String.join(", ", missing));
        }
        if (!issuerDiscoveryEnabled() && scopes().contains("openid")
                && (properties.getJwkSetUri() == null || properties.getJwkSetUri().isBlank())) {
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
            return ClientRegistrations.fromIssuerLocation(properties.getIssuerUri())
                    .registrationId(REGISTRATION_ID)
                    .clientId(properties.getClientId())
                    .clientSecret(properties.getClientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(properties.getRedirectUri())
                    .scope(scopes())
                    .userNameAttributeName(properties.getUserNameAttribute())
                    .clientName("AI Git Bot")
                    .build();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("OAuth issuer discovery failed for giteabot.security.oauth.issuer-uri '"
                    + properties.getIssuerUri() + "'", ex);
        }
    }

    private ClientRegistration explicitRegistration() {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.getRedirectUri())
                .scope(scopes())
                .authorizationUri(properties.getAuthorizationUri())
                .tokenUri(properties.getTokenUri())
                .userInfoUri(properties.getUserInfoUri())
                .userNameAttributeName(properties.getUserNameAttribute())
                .clientName("AI Git Bot")
                .build();
        if (properties.getJwkSetUri() != null && !properties.getJwkSetUri().isBlank()) {
            registration = ClientRegistration.withClientRegistration(registration)
                    .jwkSetUri(properties.getJwkSetUri())
                    .build();
        }
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "giteabot.security.oauth.debug-logging", name = "enabled", havingValue = "true")
    public OAuthDebugLoggingFilter oauthDebugLoggingFilter() {
        return new OAuthDebugLoggingFilter(properties.getDebugLogging().getMaxBodyLength());
    }

    private Set<String> scopes() {
        Set<String> scopes = new LinkedHashSet<>();
        Arrays.stream(properties.getScope().split("[\\s,]+"))
                .filter(value -> !value.isBlank())
                .forEach(scopes::add);
        return scopes;
    }

    private boolean issuerDiscoveryEnabled() {
        return properties.getIssuerUri() != null && !properties.getIssuerUri().isBlank();
    }

    private static String required(String property, String value) {
        return value == null || value.isBlank() ? property : null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
