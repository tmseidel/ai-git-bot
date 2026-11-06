package org.remus.giteabot.bitbucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Metadata and factory for Bitbucket Cloud repository integration.
 * Handles URL transformations between bitbucket.org and api.bitbucket.org,
 * and creates properly configured Bitbucket API clients.
 * <p>
 * Authentication uses Atlassian API tokens:
 * <ul>
 *   <li><b>REST API</b>: Bearer with the API token.</li>
 *   <li><b>Git over HTTPS</b>: the fixed username {@value #GIT_API_TOKEN_USERNAME}
 *       with the API token.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitbucketProviderMetadata implements RepositoryProviderMetadata {

    /** Static Git username Bitbucket Cloud expects for HTTPS operations authenticated with an API token. */
    public static final String GIT_API_TOKEN_USERNAME = "x-bitbucket-api-token-auth";

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

    /**
     * Builds the API {@code Authorization} header: Bearer with the Atlassian account API token.
     */
    public String buildAuthorizationHeader(@Nullable String token) {
        if (token == null || token.isBlank()) {
            log.warn("Bitbucket API token is empty or null");
            return "";
        }
        return "Bearer " + token;
    }

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String authHeader = buildAuthorizationHeader(decryptedToken);

        log.debug("Building Bitbucket RestClient: apiUrl={}", apiUrl);

        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", authHeader)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Git operations authenticate with the fixed {@value #GIT_API_TOKEN_USERNAME} username and
     * the API token.
     */
    @Override
    public RepositoryCredentials createCredentials(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String cloneUrl = resolveCloneUrl(integration);
        return RepositoryCredentials.of(apiUrl, cloneUrl, GIT_API_TOKEN_USERNAME, decryptedToken);
    }

    @Override
    public RepositoryApiClient createClient(RestClient restClient, RepositoryCredentials credentials) {
        return new BitbucketApiClient(restClient, credentials);
    }
}
