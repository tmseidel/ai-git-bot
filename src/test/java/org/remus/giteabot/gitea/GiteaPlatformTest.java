package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GiteaPlatformTest {

    private static GiteaPlatform httpPlatform(String cloneUrl) {
        return new GiteaPlatform("https://gitea.example.com", cloneUrl,
                new HttpCredentials(null, "gitea-token"), null, new GiteaApiClient(null), null);
    }

    @Test
    void getRepositoryRemote_buildsCompleteHttpUrl() {
        assertEquals("https://gitea.example.com/owner/repo.git",
                httpPlatform("https://gitea.example.com").getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_rejectsCredentialBearingHttpBase() {
        GiteaPlatform platform = httpPlatform("https://user:secret@gitea.example.com");

        assertThrows(IllegalStateException.class, () -> platform.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_acceptsCaseInsensitiveHttpScheme() {
        assertEquals("https://gitea.example.com/owner/repo.git",
                httpPlatform("HTTPS://gitea.example.com").getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_rejectsUnsupportedScheme() {
        GiteaPlatform platform = httpPlatform("ftp://gitea.example.com");

        assertThrows(IllegalStateException.class, () -> platform.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_overSshRejectsAnSshUrlOutsideThePinnedHosts() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"git@other.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));
        String knownHosts = "gitea.example.com ssh-ed25519 host-key";
        GiteaPlatform platform = new GiteaPlatform("https://gitea.example.com", "https://gitea.example.com",
                new HttpCredentials(null, "gitea-token"), () -> new SshCredentials("private-key", knownHosts),
                new GiteaApiClient(builder.build()), knownHosts);

        assertThrows(IllegalStateException.class, () -> platform.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @Test
    void getRepositoryRemote_overHttpIgnoresThePinnedHosts() {
        GiteaPlatform platform = new GiteaPlatform("https://gitea.example.com", "https://gitea.example.com",
                new HttpCredentials(null, "gitea-token"), null, new GiteaApiClient(null),
                "other.example.com ssh-ed25519 host-key");

        assertEquals("https://gitea.example.com/owner/repo.git", platform.getRepositoryRemote("owner", "repo"));
    }
}
