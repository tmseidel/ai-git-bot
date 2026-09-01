package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Unit tests for {@link GiteaApiClient}.
 */
class GiteaApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "gitea-token");

    @Test
    void implementsRepositoryApiClient() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getRepositoryRemote_sshDisabled_buildsCompleteHttpUrl() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);

        assertEquals("https://gitea.example.com/owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_sshDisabled_rejectsCredentialBearingHttpBase() {
        GiteaApiClient client = new GiteaApiClient(null,
                RepositoryCredentials.of("https://gitea.example.com",
                        "https://user:secret@gitea.example.com", "gitea-token"));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_sshDisabled_rejectsNonCanonicalHttpScheme() {
        GiteaApiClient client = new GiteaApiClient(null,
                RepositoryCredentials.of("https://gitea.example.com",
                        "HTTPS://gitea.example.com", "gitea-token"));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
    }

    @Test
    void getRepositoryRemote_sshEnabled_usesRepositorySshUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"ssh_url\":\"git@gitea.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("git@gitea.example.com:owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @Test
    void getRepositoryRemote_acceptsMatchingSshUrlWithCustomPort() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "[gitea.example.com]:2222 ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess(
                        "{\"ssh_url\":\"ssh://git@gitea.example.com:2222/owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("ssh://git@gitea.example.com:2222/owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "https://gitea.example.com/owner/repo.git", "file:///tmp/repo.git",
            "/tmp/repo.git", "C:/repos/repo.git", "C:../repo.git", "-uploader", "not-a-remote",
            "git@gitea.example.com", "ssh:///owner/repo.git",
            "SSH://git@gitea.example.com/owner/repo.git",
            "ssh://%20-option@gitea.example.com/owner/repo.git",
            "git@[gitea.example.com:owner/repo.git", "git@host]:owner/repo.git",
            "git@[host]suffix:owner/repo.git"
    })
    void getRepositoryRemote_rejectsNonSshApiValues(String sshUrl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"" + sshUrl + "\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git@other.example.com:owner/repo.git",
            "ssh://git@gitea.example.com:2222/owner/repo.git"
    })
    void getRepositoryRemote_rejectsCanonicalKnownHostsEndpointMismatch(String sshUrl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", "gitea.example.com ssh-ed25519 host-key"));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"" + sshUrl + "\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "|1|hash|salt ssh-ed25519 host-key",
            "*.example.com ssh-ed25519 host-key"
    })
    void getRepositoryRemote_preservesManualKnownHostsCompatibility(String knownHosts) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                CREDS.withSsh("private-key", knownHosts));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess(
                        "{\"ssh_url\":\"git@other.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("git@other.example.com:owner/repo.git",
                client.getRepositoryRemote("owner", "repo"));
        server.verify();
    }

    @Test
    void getAnySshCloneUrl_returnsUrlFromVisibleRepository() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/search?limit=1&private=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[{\"ssh_url\":\"ssh://git@gitea.example.com:2222/owner/repo.git\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("ssh://git@gitea.example.com:2222/owner/repo.git", client.getAnySshCloneUrl());
        server.verify();
    }

    @Test
    void getAnySshCloneUrl_rejectsNonSshApiValue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/search?limit=1&private=true"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"ssh_url\":\"https://gitea.example.com/owner/repo.git\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, client::getAnySshCloneUrl);
        server.verify();
    }

    @Test
    void getCurrentUserId_returnsAuthenticatedUserId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/user"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":17}", MediaType.APPLICATION_JSON));

        assertEquals(17L, client.getCurrentUserId());
        server.verify();
    }

    @Test
    void getSshKeyIdsByTitle_returnsOnlyExactMatches() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys?page=1&limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":41,\"title\":\"other\"},{\"id\":42,\"title\":\"AI Git Bot: integration-7\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys?page=2&limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(42L), client.getSshKeyIdsByTitle("AI Git Bot: integration-7"));
        server.verify();
    }

    @Test
    void getSshKeyIds_returnsOnlyAuthenticatedUsersKeys() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys?page=1&limit=50"))
                .andRespond(withSuccess("[{\"id\":41,\"title\":\"one\"},{\"id\":42,\"title\":\"two\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys?page=2&limit=50"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(41L, 42L), client.getSshKeyIds());
        server.verify();
    }

    @Test
    void createSshKey_registersWritablePublicKeyAndReturnsId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.title").value("AI Git Bot: production"))
                .andExpect(jsonPath("$.key").value("ssh-ed25519 public-key gitbot"))
                .andExpect(jsonPath("$.read_only").value(false))
                .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

        assertEquals(42L, client.createSshKey("AI Git Bot: production", "ssh-ed25519 public-key gitbot"));
        server.verify();
    }

    @Test
    void deleteSshKey_removesKeyById() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/user/keys/42"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteSshKey(42L);
        server.verify();
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitea.example.com/api/v1/repos/owner/repo/issues/42/comments?limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":401,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(401, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void postReview_approve_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("APPROVED"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }

    @Test
    void assignIssue_patchesAssigneesAndVerifiesAssignment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.assignees[0]").value("alice"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"number\":42,\"assignees\":[{\"login\":\"alice\"}]}",
                        MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void assignIssue_throwsWhenGiteaSilentlyDropsAssignee() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"number\":42,\"assignees\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class,
                () -> client.assignIssue("owner", "repo", 42L, "ghost"));

        server.verify();
    }
}
