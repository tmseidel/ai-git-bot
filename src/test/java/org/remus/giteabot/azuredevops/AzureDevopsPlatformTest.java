package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureDevopsPlatformTest {

    private static AzureDevopsPlatform platform(String cloneUrl) {
        return new AzureDevopsPlatform("https://dev.azure.com", cloneUrl,
                new HttpCredentials(null, "ado_pat"), null,
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null));
    }

    @Test
    void usesGitAuthorizationHeader() {
        // Azure DevOps Git remotes only accept the PAT as a pre-emptive Basic header.
        assertTrue(platform("https://dev.azure.com").usesGitAuthorizationHeader());
    }

    @Test
    void getRepositoryRemote_usesGitSegment() {
        assertEquals("https://dev.azure.com/contoso/MyProject/_git/my-service",
                platform("https://dev.azure.com").getRepositoryRemote("contoso", "MyProject/my-service"));
    }

    @Test
    void getRepositoryRemote_omitsTheOrganizationWhenTheCloneBaseAlreadyCarriesIt() {
        assertEquals("https://contoso.visualstudio.com/MyProject/_git/my-service",
                platform("https://contoso.visualstudio.com")
                        .getRepositoryRemote("contoso", "MyProject/my-service"));

        assertEquals("https://tfs.example.com/tfs/DefaultCollection/MyProject/_git/my-service",
                platform("https://tfs.example.com/tfs/DefaultCollection")
                        .getRepositoryRemote("DefaultCollection", "MyProject/my-service"));
    }

    @Test
    void getRepositoryRemote_rejectsCredentialBearingHttpBase() {
        // The remote is passed to git as a command-line argument and echoed back in its
        // error output, so a PAT in the clone base URL must not survive into it. The
        // default remote enforces this; the Azure DevOps override builds its own path
        // and must not lose the guarantee along the way.
        AzureDevopsPlatform platform = platform("https://user:pat@dev.azure.com");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> platform.getRepositoryRemote("contoso", "MyProject/my-service"));
        assertEquals("HTTP clone base URL must be credential-free", e.getMessage());
    }

    @Test
    void getRepositoryRemote_rejectsSsh() {
        AzureDevopsPlatform platform = new AzureDevopsPlatform("https://dev.azure.com", "https://dev.azure.com",
                new HttpCredentials(null, "ado_pat"), () -> new SshCredentials("key", "known-hosts"),
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null));

        assertThrows(IllegalStateException.class,
                () -> platform.getRepositoryRemote("contoso", "MyProject/my-service"));
    }
}
