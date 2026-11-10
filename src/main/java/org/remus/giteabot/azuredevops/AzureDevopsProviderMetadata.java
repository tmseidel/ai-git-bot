package org.remus.giteabot.azuredevops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.agent.validation.GitDiffService;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * Metadata and factory for Azure DevOps repository integration.
 * <p>
 * {@code GitIntegration.url} holds the instance root, in one of five shapes:
 * <pre>
 * https://dev.azure.com                          Azure DevOps Services (many organizations)
 * https://dev.azure.com/{organization}           Azure DevOps Services (one organization)
 * https://{organization}.visualstudio.com        Azure DevOps Services, legacy host (one organization)
 * https://{host}/tfs                             Azure DevOps Server, no collection (many collections)
 * https://{host}/tfs/{collection}                Azure DevOps Server, with collection (one collection)
 * </pre>
 * The first and fourth carry no organization (collection), so one integration serves any
 * number of them, each resolved per event from the webhook payload. The token only has to reach
 * the organizations that send events: everything, the bot's own identity included, is asked
 * inside the event's organization. The other shapes pin the organization (or collection) into
 * the URL itself and therefore need one integration each —
 * {@link AzureDevopsAddress#isOrganizationScopedBy} detects this and omits the organization from
 * request paths so it is not addressed twice.
 * <p>
 * The API and clone base URLs are identical in all shapes.
 * <p>
 * Personal Access Tokens authenticate as HTTP Basic with an empty username.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureDevopsProviderMetadata implements RepositoryProviderMetadata {

    private static final String DEFAULT_URL = "https://dev.azure.com";

    private final ObjectProvider<RestClient.Builder> restClientBuilder;
    private final GitDiffService gitDiffService;

    @Override
    public RepositoryType getProviderType() {
        return RepositoryType.AZURE_DEVOPS;
    }

    public String resolveApiUrl(GitIntegration integration) {
        String url = integration.getUrl();
        if (url == null || url.isBlank()) {
            return DEFAULT_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public String buildAuthorizationHeader(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Azure DevOps token is empty or null");
            return "";
        }
        String encoded = Base64.getEncoder()
                .encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        log.debug("Building Azure DevOps RestClient: apiUrl={}", apiUrl);
        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", buildAuthorizationHeader(decryptedToken))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public HttpCredentials createHttpCredentials(GitIntegration integration, String decryptedToken) {
        return new HttpCredentials(integration.getUsername(), decryptedToken);
    }

    @Override
    public RepositoryPlatform createPlatform(GitIntegration integration, String decryptedToken,
                                             HttpCredentials httpCredentials,
                                             @Nullable Supplier<SshCredentials> sshCredentials,
                                             Duration userTtl) {
        RestClient restClient = buildRestClient(integration, decryptedToken);
        String url = resolveApiUrl(integration);
        AzureDevopsApiClient client = new AzureDevopsApiClient(restClient, url, gitDiffService);
        AzureDevopsPlatform platform = new AzureDevopsPlatform(url, url, httpCredentials, sshCredentials, client,
                userTtl);
        client.bindPlatform(platform);
        return platform;
    }
}
