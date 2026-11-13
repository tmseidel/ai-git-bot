package org.remus.giteabot.gitlab;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

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

    private RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);

        log.debug("Building GitLab RestClient: apiUrl={}", apiUrl);

        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("PRIVATE-TOKEN", decryptedToken)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public HttpCredentials createHttpCredentials(GitIntegration integration, String decryptedToken) {
        return new HttpCredentials(null, decryptedToken);
    }

    @Override
    public RepositoryPlatform createPlatform(GitIntegration integration, String decryptedToken,
                                             HttpCredentials httpCredentials,
                                             @Nullable Supplier<SshCredentials> sshCredentials,
                                             Duration userTtl) {
        RestClient restClient = buildRestClient(integration, decryptedToken);
        String cloneUrl = resolveCloneUrl(integration);
        return new GitLabPlatform(resolveApiUrl(integration), cloneUrl, httpCredentials, sshCredentials,
                new GitLabApiClient(restClient), userTtl);
    }
}
