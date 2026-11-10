package org.remus.giteabot.gitea;

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

    private RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
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
        return new GiteaPlatform(resolveApiUrl(integration), cloneUrl, httpCredentials, sshCredentials,
                new GiteaApiClient(restClient), integration.getSshKnownHosts(), userTtl);
    }
}


