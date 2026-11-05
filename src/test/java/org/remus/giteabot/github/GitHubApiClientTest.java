package org.remus.giteabot.github;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestCommit;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestHead;
import org.remus.giteabot.repository.model.PullRequestState;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;
import org.springframework.http.HttpMethod;
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

/**
 * Unit tests for {@link GitHubApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitHubApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://api.github.com", "https://github.com", "ghp_token");

    @Test
    void implementsRepositoryApiClient() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://api.github.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://github.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("ghp_token", client.getToken());
    }

    @Test
    void constructorWithEnterpriseUrl() {
        var enterpriseCreds = RepositoryCredentials.of(
                "https://github.example.com/api/v3", "https://github.example.com", "token123");
        GitHubApiClient client = new GitHubApiClient(null, enterpriseCreds);
        assertEquals("https://github.example.com/api/v3", client.getBaseUrl());
        assertEquals("https://github.example.com", client.getCloneUrl());
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithPageLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/comments?per_page=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":101,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(101, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void addPullRequestReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void getRepositoryTree_mapsBlobTreeAndSubmoduleEntries() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/git/trees/main?recursive=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"sha":"abc","truncated":false,"tree":[
                          {"path":"src","mode":"040000","type":"tree","sha":"t1"},
                          {"path":"src/App.java","mode":"100644","type":"blob","sha":"b1","size":42},
                          {"path":"vendor/lib","mode":"160000","type":"commit","sha":"c1"}]}
                        """, MediaType.APPLICATION_JSON));

        List<RepositoryTreeEntry> tree = client.getRepositoryTree("owner", "repo", "main");

        server.verify();
        assertEquals(List.of(
                new RepositoryTreeEntry("src", RepositoryTreeEntry.Type.DIRECTORY),
                new RepositoryTreeEntry("src/App.java", RepositoryTreeEntry.Type.FILE),
                new RepositoryTreeEntry("vendor/lib", RepositoryTreeEntry.Type.OTHER)), tree);
    }

    @Test
    void getPullRequestCommits_mapsShaAndNestedMessage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42/commits"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sha":"abc1234567890","commit":{"message":"Add login","author":{"name":"Jane"}},
                          "author":{"login":"jane"}}]
                        """, MediaType.APPLICATION_JSON));

        List<PullRequestCommit> commits = client.getPullRequestCommits("owner", "repo", 42L);

        server.verify();
        assertEquals(List.of(new PullRequestCommit("abc1234567890", "Add login")), commits);
    }

    @Test
    void getPullRequestDetails_mapsTitleBodyAndHead() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"number":42,"state":"open","merged":false,"title":"Add login","body":"Adds the login page",
                         "head":{"ref":"feature/login","sha":"abc123","repo":{"full_name":"fork/repo"}},
                         "base":{"ref":"main","sha":"def456"}}
                        """, MediaType.APPLICATION_JSON));

        PullRequestDetails details = client.getPullRequestDetails("owner", "repo", 42L).orElseThrow();

        assertEquals(new PullRequestDetails("Add login", "Adds the login page", PullRequestState.OPEN,
                "feature/login", "abc123", "main", "def456"),
                details);
        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesToIssueReactionEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/12/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
    }

    @Test
    void postReview_requestChanges_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }

    @Test
    void assignIssue_postsAssignees() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues/42/assignees"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assignees[0]").value("alice"))
                .andRespond(withSuccess("{\"number\":42}", MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void requiresAuthoritativePullRequestHead_isEnabled() {
        assertTrue(new GitHubApiClient(null, CREDS).requiresAuthoritativePullRequestHead());
    }

    @Test
    void getPullRequestHead_resolvesForkRepositoryAndBranch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/base/project/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"head":{"ref":"bugfix/clone-budget","sha":"abc123","repo":{
                          "name":"project","full_name":"contributor/project",
                          "owner":{"login":"contributor"}}},
                         "base":{"ref":"develop","sha":"def456"}}
                        """, MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("base", "project", 7L, "bugfix/clone-budget");

        assertEquals("contributor", head.owner());
        assertEquals("project", head.repository());
        assertEquals("bugfix/clone-budget", head.branch());
        assertEquals("abc123", head.sha());
        server.verify();
    }

    @Test
    void getPullRequestHead_fillsCoordinatesFromFullNameOnly() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/base/project/pulls/7"))
                .andRespond(withSuccess("""
                        {"head":{"ref":"main","sha":"abc123","repo":{"full_name":"contributor/project"}}}
                        """, MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("base", "project", 7L, "main");

        assertEquals("contributor", head.owner());
        assertEquals("project", head.repository());
        server.verify();
    }

    @Test
    void getPullRequestHead_rejectsBranchMismatch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/base/project/pulls/7"))
                .andRespond(withSuccess("""
                        {"head":{"ref":"feature/source","sha":"abc123","repo":{
                          "name":"project","owner":{"login":"contributor"}}}}
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("base", "project", 7L, "main"));

        assertTrue(error.getMessage().contains("does not match"));
        server.verify();
    }

    @Test
    void getPullRequestHead_rejectsMissingHeadRepository() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/base/project/pulls/7"))
                .andRespond(withSuccess("{\"head\":{\"ref\":\"main\",\"sha\":\"abc123\"}}",
                        MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("base", "project", 7L, "main"));
        server.verify();
    }
}
