package org.remus.giteabot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Metadata and factory for GitHub repository integration.
 * Handles URL transformations between github.com and api.github.com,
 * and creates properly configured GitHub API clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubProviderMetadata implements RepositoryProviderMetadata {

    private static final String DEFAULT_WEB_URL = "https://github.com";
    private static final String DEFAULT_API_URL = "https://api.github.com";

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public RepositoryType getProviderType() {
        return RepositoryType.GITHUB;
    }

    public String resolveApiUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_API_URL;
        }

        // Already an API URL
        if (url.contains("api.github.com") || url.contains("/api/v3")) {
            return url;
        }

        // Public GitHub: github.com -> api.github.com
        if (url.contains("github.com")) {
            return url.replace("github.com", "api.github.com");
        }

        // GitHub Enterprise: add /api/v3 suffix
        String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return baseUrl + "/api/v3";
    }

    public String resolveCloneUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_WEB_URL;
        }

        // Convert API URL back to web URL
        if (url.contains("api.github.com")) {
            return url.replace("api.github.com", "github.com");
        }

        // GitHub Enterprise: remove /api/v3 suffix
        if (url.contains("/api/v3")) {
            return url.replaceAll("/api/v3/?$", "");
        }

        return url;
    }

    public String buildAuthorizationHeader(String token) {
        return "Bearer " + token;
    }

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String authHeader = buildAuthorizationHeader(decryptedToken);

        log.debug("Building GitHub RestClient: apiUrl={}", apiUrl);

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
        return RepositoryCredentials.of(apiUrl, cloneUrl, decryptedToken);
    }

    @Override
    public RepositoryApiClient createClient(RestClient restClient, RepositoryCredentials credentials) {
        return new GitHubApiClient(restClient, credentials);
    }
}


