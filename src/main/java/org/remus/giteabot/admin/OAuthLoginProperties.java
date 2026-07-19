package org.remus.giteabot.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the mutually exclusive OAuth/OIDC web login mode.
 *
 * <p>Provider endpoints can either be discovered from {@link #issuerUri} or
 * supplied explicitly for OAuth 2.0 providers without OIDC discovery.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "giteabot.security.oauth")
public class OAuthLoginProperties {

    private String clientId;
    private String clientSecret;
    private String issuerUri;
    private String authorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String jwkSetUri;
    private String userNameAttribute = "sub";
    private String redirectUri = "{baseUrl}/login/oauth2/code/giteabot";
    private String scope = "openid,profile";
    private DebugLogging debugLogging = new DebugLogging();

    @Getter
    @Setter
    public static class DebugLogging {
        private boolean enabled;
        private int maxBodyLength = 16_384;
    }
}
