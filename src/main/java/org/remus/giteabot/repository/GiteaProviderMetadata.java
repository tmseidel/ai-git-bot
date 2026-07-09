package org.remus.giteabot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Metadata and factory for Gitea repository integration.
 * Handles URL transformations and creates properly configured Gitea API clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiteaProviderMetadata implements RepositoryProviderMetadata {

    private static final String DEFAULT_WEB_URL = "https://gitea.example.com";

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public RepositoryType getProviderType() {
        return RepositoryType.GITEA;
    }

    public String resolveApiUrl(GitIntegration integration) {
        // Gitea's API is at the same base URL, just with /api/v1 paths
        // The RestClient uses the base URL and adds /api/v1 in each request
        return integration.getUrl();
    }

    public String resolveCloneUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_WEB_URL;
        }

        // Remove /api/v1 suffix if present
        if (url.contains("/api/v1")) {
            return url.replaceAll("/api/v1/?$", "");
        }

        return url;
    }

    public String buildAuthorizationHeader(String token) {
        // Gitea uses "token <token>" format
        return "token " + token;
    }

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        String authHeader = buildAuthorizationHeader(decryptedToken);

        log.debug("Building Gitea RestClient: apiUrl={}", apiUrl);

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
        return new GiteaApiClient(restClient, credentials);
    }
}


