package org.remus.giteabot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.agent.validation.GitDiffService;
import org.remus.giteabot.azuredevops.AzureDevopsApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Metadata and factory for Azure DevOps repository integration.
 * <p>
 * {@code GitIntegration.url} holds the instance root, in one of three shapes:
 * <pre>
 * https://dev.azure.com                          Azure DevOps Services (multi-organization)
 * https://{organization}.visualstudio.com        Azure DevOps Services, legacy host form
 * https://{host}/{collection}                    Azure DevOps Server (on-premises)
 * </pre>
 * Only the first is organization-agnostic: it carries no organization, so one integration
 * serves any number of them, each resolved per event from the webhook payload. The other
 * two pin the organization (or collection) into the URL itself and therefore need one
 * integration each — {@code AzureDevopsApiClient#scopesOrganization} detects this and
 * omits the organization from request paths so it is not addressed twice.
 * <p>
 * The API and clone base URLs are identical in all three shapes.
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

    @Override
    public RestClient buildRestClient(GitIntegration integration, String decryptedToken) {
        String apiUrl = resolveApiUrl(integration);
        log.debug("Building Azure DevOps RestClient: apiUrl={}", apiUrl);
        return restClientBuilder.getObject()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", buildAuthorizationHeader(decryptedToken))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public RepositoryCredentials createCredentials(GitIntegration integration,
                                                   String decryptedToken) {
        String url = resolveApiUrl(integration);
        return RepositoryCredentials.of(url, url, integration.getUsername(), decryptedToken);
    }

    @Override
    public RepositoryApiClient createClient(RestClient restClient,
                                            RepositoryCredentials credentials) {
        return new AzureDevopsApiClient(restClient, credentials, gitDiffService);
    }
}
