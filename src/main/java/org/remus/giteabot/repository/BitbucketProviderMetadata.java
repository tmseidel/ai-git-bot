package org.remus.giteabot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Metadata and factory for Bitbucket Cloud repository integration.
 * Handles URL transformations between bitbucket.org and api.bitbucket.org,
 * and creates properly configured Bitbucket API clients.
 * <p>
 * Authentication methods supported:
 * <ul>
 *   <li><b>App Passwords</b>: Requires username and app password. The username is stored
 *       separately in the GitIntegration, and combined with the token for Basic authentication.</li>
 *   <li><b>API Tokens (new)</b>: Tokens starting with "ATATT" use Bearer authentication.
 *       No username required.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitbucketProviderMetadata implements RepositoryProviderMetadata {

    private static final String DEFAULT_WEB_URL = "https://bitbucket.org";
    private static final String DEFAULT_API_URL = "https://api.bitbucket.org/2.0";

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public RepositoryType getProviderType() {
        return RepositoryType.BITBUCKET;
    }

    public String resolveApiUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_API_URL;
        }

        // Already an API URL
        if (url.contains("api.bitbucket.org")) {
            return url;
        }

        // Public Bitbucket: bitbucket.org -> api.bitbucket.org/2.0
        if (url.contains("bitbucket.org")) {
            String replaced = url.replace("bitbucket.org", "api.bitbucket.org");
            String baseUrl = replaced.endsWith("/") ? replaced.substring(0, replaced.length() - 1) : replaced;
            return baseUrl + "/2.0";
        }

        // Self-hosted Bitbucket: add /rest/api/1.0 suffix
        String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return baseUrl + "/rest/api/1.0";
    }

    public String resolveCloneUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_WEB_URL;
        }

        // Convert API URL back to web URL
        if (url.contains("api.bitbucket.org")) {
            return url.replaceAll("api\\.bitbucket\\.org(/2\\.0)?", "bitbucket.org")
                    .replaceAll("/$", "");
        }

        // Self-hosted: remove /rest/api/1.0 suffix
        if (url.contains("/rest/api/1.0")) {
            return url.replaceAll("/rest/api/1\\.0/?$", "");
        }

        return url;
    }

    public String buildAuthorizationHeader(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Bitbucket token is empty or null");
            return "";
        }

        // New Atlassian API Tokens (ATATT...) use Bearer authentication
        if (token.startsWith("ATATT")) {
            log.debug("Using Bearer authentication for Atlassian API Token");
            return "Bearer " + token;
        }

        // Token with username:password format uses Basic authentication
        if (token.contains(":")) {
            log.debug("Using Basic authentication for App Password (username:password format)");
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }

        // For tokens without ":" that don't start with ATATT, assume Bearer
        log.debug("Using Bearer authentication (token format not recognized as Basic auth)");
        return "Bearer " + token;
    }

    /**
     * Build the Authorization header using credentials (supports username for App Passwords).
     */
    public String buildAuthorizationHeader(RepositoryCredentials credentials) {
        String token = credentials.token();
        if (token == null || token.isBlank()) {
            log.warn("Bitbucket token is empty or null");
            return "";
        }
        // App Password with separate username
        if (credentials.hasUsername()) {
            log.debug("Using Basic authentication with username '{}' and App Password", credentials.username());
            String combined = credentials.username() + ":" + token;
            String encoded = Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }

        // Token already contains username:password
        if (token.contains(":")) {
            log.debug("Using Basic authentication for App Password (username:password format in token)");
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }

        // For tokens without ":" that don't start with ATATT, assume Bearer
        log.debug("Using Bearer authentication (token format not recognized as Basic auth)");
        return "Bearer " + token;
    }

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        RepositoryCredentials credentials = createCredentials(integration, decryptedToken);
        String authHeader = buildAuthorizationHeader(credentials);

        log.debug("Building Bitbucket RestClient: apiUrl={}", apiUrl);

        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", authHeader)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public RepositoryCredentials createCredentials(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String cloneUrl = resolveCloneUrl(integration);
        String username = integration.getUsername();
        return RepositoryCredentials.of(apiUrl, cloneUrl, username, decryptedToken);
    }

    @Override
    public RepositoryApiClient createClient(RestClient restClient, RepositoryCredentials credentials) {
        return new BitbucketApiClient(restClient, credentials);
    }
}
