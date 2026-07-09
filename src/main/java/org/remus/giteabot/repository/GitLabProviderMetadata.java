package org.remus.giteabot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.gitlab.GitLabApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Metadata and factory for GitLab repository integration.
 * Handles URL transformations and creates properly configured GitLab API clients.
 * <p>
 * GitLab uses the {@code PRIVATE-TOKEN} header for authentication and
 * URL-encoded project paths ({@code owner%2Frepo}) in its REST API v4.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabProviderMetadata implements RepositoryProviderMetadata {

    private static final String DEFAULT_WEB_URL = "https://gitlab.com";

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public RepositoryType getProviderType() {
        return RepositoryType.GITLAB;
    }

    public String resolveApiUrl(GitIntegration integration) {
        // GitLab's API is at the same base URL; the client adds /api/v4 paths
        return integration.getUrl();
    }

    public String resolveCloneUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_WEB_URL;
        }

        // Remove /api/v4 suffix if present
        if (url.contains("/api/v4")) {
            return url.replaceAll("/api/v4/?$", "");
        }

        return url;
    }

    public String buildAuthorizationHeader(String token) {
        // GitLab uses "PRIVATE-TOKEN: <token>" header format
        return token;
    }

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);

        log.debug("Building GitLab RestClient: apiUrl={}", apiUrl);

        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("PRIVATE-TOKEN", decryptedToken)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public RepositoryCredentials createCredentials(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String cloneUrl = resolveCloneUrl(integration);
        return RepositoryCredentials.of(apiUrl, cloneUrl, decryptedToken);
    }

    @Override
    public RepositoryApiClient createClient(RestClient restClient, RepositoryCredentials credentials) {
        return new GitLabApiClient(restClient, credentials);
    }
}
