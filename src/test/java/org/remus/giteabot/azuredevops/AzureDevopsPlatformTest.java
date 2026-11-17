package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.TestPlatforms;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AzureDevopsPlatformTest {

    private static AzureDevopsPlatform platform(String cloneUrl) {
        return new AzureDevopsPlatform("https://dev.azure.com", cloneUrl,
                new HttpCredentials(null, "ado_pat"), null,
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null), TestPlatforms.USER_TTL);
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
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null), TestPlatforms.USER_TTL);

        assertThrows(IllegalStateException.class,
                () -> platform.getRepositoryRemote("contoso", "MyProject/my-service"));
    }

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void getFileWebUrl_pinsTheCommitAndSelectsTheLineRange() {
        assertEquals(Optional.of("https://dev.azure.com/contoso/MyProject/_git/my-service"
                        + "?path=/src/Foo.java&version=GC" + SHA
                        + "&line=10&lineEnd=13&lineStartColumn=1&lineEndColumn=1&lineStyle=plain&_a=contents"),
                platform("https://dev.azure.com")
                        .getFileWebUrl("contoso", "MyProject/my-service", SHA, "src/Foo.java", 10, 12));
    }

    @Test
    void getFileWebUrl_singleLineAndWholeFile() {
        AzureDevopsPlatform platform = platform("https://dev.azure.com/");

        assertEquals(Optional.of("https://dev.azure.com/contoso/P/_git/r?path=/a.txt&version=GC" + SHA
                        + "&line=7&lineEnd=8&lineStartColumn=1&lineEndColumn=1&lineStyle=plain&_a=contents"),
                platform.getFileWebUrl("contoso", "P/r", SHA, "/a.txt", 7, 7));
        assertEquals(Optional.of("https://dev.azure.com/contoso/P/_git/r?path=/a.txt&version=GC" + SHA
                        + "&_a=contents"),
                platform.getFileWebUrl("contoso", "P/r", SHA, "a.txt", null, null));
    }

    @Test
    void getFileWebUrl_omitsTheOrganizationWhenTheCloneBaseAlreadyCarriesIt() {
        assertEquals(Optional.of("https://contoso.visualstudio.com/P/_git/r?path=/a&version=GC" + SHA
                        + "&_a=contents"),
                platform("https://contoso.visualstudio.com")
                        .getFileWebUrl("contoso", "P/r", SHA, "a", null, null));
        assertEquals(Optional.of("https://tfs.example.com/tfs/DefaultCollection/P/_git/r?path=/a&version=GC" + SHA
                        + "&_a=contents"),
                platform("https://tfs.example.com/tfs/DefaultCollection")
                        .getFileWebUrl("DefaultCollection", "P/r", SHA, "a", null, null));
    }

    @Test
    void getFileWebUrl_encodesProjectRepositoryAndPath() {
        assertEquals(Optional.of("https://dev.azure.com/contoso/My%20Project/_git/my%20repo"
                        + "?path=/docs/R%26D%20notes%2B%231.md&version=GC" + SHA + "&_a=contents"),
                platform("https://dev.azure.com")
                        .getFileWebUrl("contoso", "My Project/my repo", SHA, "docs/R&D notes+#1.md", null, null));
    }

    @Test
    void getFileWebUrl_isEmptyWhenTheAddressCannotBeResolved() {
        assertFalse(platform("https://dev.azure.com")
                .getFileWebUrl("contoso", "no-project-separator", SHA, "a", 1, 1).isPresent());
        assertFalse(platform("https://user:pat@dev.azure.com")
                .getFileWebUrl("contoso", "P/r", SHA, "a", 1, 1).isPresent());
        assertFalse(platform("https://dev.azure.com")
                .getFileWebUrl("contoso", "P/r", null, "a", 1, 1).isPresent());
    }
}
