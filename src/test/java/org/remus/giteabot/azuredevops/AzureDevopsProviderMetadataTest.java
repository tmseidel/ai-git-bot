package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.agent.validation.GitDiffService;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureDevopsProviderMetadataTest {

    private final AzureDevopsProviderMetadata metadata =
            new AzureDevopsProviderMetadata(builderProvider(), null);

    private static ObjectProvider<RestClient.Builder> builderProvider() {
        return new ObjectProvider<>() {
            @Override
            public RestClient.Builder getObject() {
                return RestClient.builder();
            }
        };
    }

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
    void createPlatform_returnsAzureDevopsPlatformWithItsClient() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/");

        var platform = metadata.createPlatform(integration, "ado_pat",
                new HttpCredentials(null, "ado_pat"), null);

        assertInstanceOf(AzureDevopsPlatform.class, platform);
        assertInstanceOf(AzureDevopsApiClient.class, platform.getClient());
        assertEquals("https://dev.azure.com", platform.getBaseUrl());
        assertEquals("https://dev.azure.com", platform.getCloneUrl());
        assertTrue(platform.usesGitAuthorizationHeader());
    }

    @Test
    void createPlatform_bindsTheClientToThePlatformItDiffsThrough() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), anything()).andRespond(withSuccess(
                "{\"count\":1,\"value\":[{\"id\":1,"
                        + "\"commonRefCommit\":{\"commitId\":\"1111111111111111111111111111111111111111\"},"
                        + "\"sourceRefCommit\":{\"commitId\":\"3333333333333333333333333333333333333333\"}}]}",
                MediaType.APPLICATION_JSON));
        GitDiffService gitDiffService = mock(GitDiffService.class);
        AzureDevopsProviderMetadata bindingMetadata = new AzureDevopsProviderMetadata(new ObjectProvider<>() {
            @Override
            public RestClient.Builder getObject() {
                return builder;
            }
        }, gitDiffService);
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com");

        var platform = bindingMetadata.createPlatform(integration, "ado_pat",
                new HttpCredentials(null, "ado_pat"), null);
        when(gitDiffService.diffCommits(platform, "contoso", "MyProject/my-service",
                "1111111111111111111111111111111111111111", "3333333333333333333333333333333333333333"))
                .thenReturn("diff");

        assertEquals("diff", platform.getClient().getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }
}
