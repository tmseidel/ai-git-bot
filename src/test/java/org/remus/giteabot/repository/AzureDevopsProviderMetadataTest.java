package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.azuredevops.AzureDevopsApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import static org.junit.jupiter.api.Assertions.*;

class AzureDevopsProviderMetadataTest {

    private final AzureDevopsProviderMetadata metadata =
            new AzureDevopsProviderMetadata(null, null);

    @Test
    void providerType_isAzureDevops() {
        assertEquals(RepositoryType.AZURE_DEVOPS, metadata.getProviderType());
    }

    @Test
    void resolveApiUrl_defaultsToPublicService() {
        GitIntegration integration = new GitIntegration();
        assertEquals("https://dev.azure.com", metadata.resolveApiUrl(integration));
    }

    @Test
    void resolveApiUrl_stripsTrailingSlash() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/");
        assertEquals("https://dev.azure.com", metadata.resolveApiUrl(integration));
    }

    @Test
    void buildAuthorizationHeader_usesBasicWithEmptyUsername() {
        // Azure DevOps PATs authenticate as Basic with an empty username.
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString(":ado_pat".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                metadata.buildAuthorizationHeader("ado_pat"));
    }

    @Test
    void buildAuthorizationHeader_blankTokenYieldsEmpty() {
        assertEquals("", metadata.buildAuthorizationHeader(null));
        assertEquals("", metadata.buildAuthorizationHeader("  "));
    }

    @Test
    void createClient_returnsAzureDevopsClient() {
        RepositoryCredentials creds = RepositoryCredentials.of(
                "https://dev.azure.com", "https://dev.azure.com", "ado_pat");

        assertInstanceOf(AzureDevopsApiClient.class, metadata.createClient(null, creds));
    }
}
