package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GitLabApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitLabApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitlab.example.com", "https://gitlab.example.com", "glpat-token123");

    @Test
    void implementsRepositoryApiClient() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("glpat-token123", client.getToken());
    }

    @Test
    void constructorWithSelfHostedUrl() {
        var selfHostedCreds = RepositoryCredentials.of(
                "https://git.mycompany.com", "https://git.mycompany.com", "glpat-abc");
        GitLabApiClient client = new GitLabApiClient(null, selfHostedCreds);
        assertEquals("https://git.mycompany.com", client.getBaseUrl());
        assertEquals("https://git.mycompany.com", client.getCloneUrl());
    }

    @Test
    void encodeProjectPath_buildsProjectPath() {
        assertEquals("owner/repo", GitLabApiClient.encodeProjectPath("owner", "repo"));
        assertEquals("my-org/my-project", GitLabApiClient.encodeProjectPath("my-org", "my-project"));
    }

    @Test
    void postReviewActionRequestChanges_callsGitLabRequestChangesEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/request_changes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.postReviewAction("owner", "repo", 7L, PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void searchIssues_returnsIssueList() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues?search=authentication%20bug&scope=all"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"iid\":1,\"title\":\"Auth bug\",\"description\":\"Login fails\",\"state\":\"opened\"}]",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> issues = client.searchIssues("owner", "repo", "authentication bug");

        server.verify();
        assertEquals(1, issues.size());
        assertEquals("Auth bug", issues.getFirst().get("title"));
    }

    @Test
    void searchIssues_returnsEmptyListForEmptyResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues?search=&scope=all"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> issues = client.searchIssues("owner", "repo", "");

        server.verify();
        assertTrue(issues.isEmpty());
    }

    @Test
    void getIssueComments_fetchesIssueNotesWithPageLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/42/notes?per_page=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":201,\"body\":\"First note\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(201, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First note", comments.getFirst().get("body"));
    }

    @Test
    void createIssue_returnsIssueIid() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"iid\":42,\"title\":\"My Issue\",\"description\":\"Issue body\"}",
                        MediaType.APPLICATION_JSON));

        Long issueNumber = client.createIssue("owner", "repo", "My Issue", "Issue body");

        server.verify();
        assertEquals(42L, issueNumber);
    }
}
