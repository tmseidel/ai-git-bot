package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestCommit;
import org.remus.giteabot.repository.model.PullRequestHead;
import org.remus.giteabot.repository.model.GitUser;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
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
    void addPullRequestReaction_postsEyesToMergeRequestAwardEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/42/award_emoji"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("eyes"))
                .andRespond(withSuccess());

        client.addPullRequestReaction("owner", "repo", 42L, "eyes");

        server.verify();
    }

    @Test
    void addIssueReaction_postsEyesToIssueAwardEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/12/award_emoji"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("eyes"))
                .andRespond(withSuccess());

        client.addIssueReaction("owner", "repo", 12L, "eyes");

        server.verify();
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
    void getRepositoryTree_mapsBlobAndTreeEntries() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/repository/tree"
                        + "?recursive=true&ref=main&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":"t1","name":"src","type":"tree","path":"src","mode":"040000"},
                         {"id":"b1","name":"App.java","type":"blob","path":"src/App.java","mode":"100644"}]
                        """, MediaType.APPLICATION_JSON));

        List<RepositoryTreeEntry> tree = client.getRepositoryTree("owner", "repo", "main");

        server.verify();
        assertEquals(List.of(
                new RepositoryTreeEntry("src", RepositoryTreeEntry.Type.DIRECTORY),
                new RepositoryTreeEntry("src/App.java", RepositoryTreeEntry.Type.FILE)), tree);
    }

    @Test
    void getPullRequestCommits_mapsIdAndMessage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/7/commits"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":"abc1234567890","short_id":"abc1234","title":"Add feature",
                          "message":"Add feature\\n\\nDetails"}]
                        """, MediaType.APPLICATION_JSON));

        List<PullRequestCommit> commits = client.getPullRequestCommits("owner", "repo", 7L);

        server.verify();
        assertEquals(List.of(new PullRequestCommit("abc1234567890", "Add feature\n\nDetails")), commits);
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

    @Test
    void assignIssue_resolvesUserThenUpdatesIssue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/users?username=alice"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":7,\"username\":\"alice\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/issues/42"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.assignee_ids[0]").value(7))
                .andRespond(withSuccess("{\"iid\":42}", MediaType.APPLICATION_JSON));

        client.assignIssue("owner", "repo", 42L, "alice");

        server.verify();
    }

    @Test
    void assignIssue_unknownUserThrowsWithoutUpdatingIssue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/users?username=ghost"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class,
                () -> client.assignIssue("owner", "repo", 42L, "ghost"));

        server.verify();
    }

    @Test
    void requiresAuthoritativePullRequestHead_isEnabled() {
        assertTrue(new GitLabApiClient(null, CREDS).requiresAuthoritativePullRequestHead());
    }

    @Test
    void getPullRequestHeadRef_isTheMergeRequestHeadRef() {
        // GitLab publishes the head commit under its own ref namespace; the
        // inherited refs/pull/<n>/head does not exist there.
        assertEquals("refs/merge-requests/7/head",
                new GitLabApiClient(null, CREDS).getPullRequestHeadRef(7L));
    }

    @Test
    void getPullRequestHead_resolvesForkedProjectPath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/base%2Fproject/merge_requests/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/login","sha":"abc123",
                         "source_project_id":12,"target_project_id":7}
                        """, MediaType.APPLICATION_JSON));
        // The merge request only names the source project *id*; its cloneable path
        // is a second lookup — the endpoint that makes GitLab differ from Gitea/GitHub.
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/12"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"path_with_namespace\":\"contributor/project\"}",
                        MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("base", "project", 7L, "feature/login");

        assertEquals("contributor", head.owner());
        assertEquals("project", head.repository());
        assertEquals("feature/login", head.branch());
        assertEquals("abc123", head.sha());
        server.verify();
    }

    @Test
    void getPullRequestHead_splitsNestedNamespaceOfForkedProject() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/group%2Fproject/merge_requests/7"))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/login","sha":"abc123",
                         "source_project_id":12,"target_project_id":7}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/12"))
                .andRespond(withSuccess("{\"path_with_namespace\":\"contributor/sub/project\"}",
                        MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("group", "project", 7L, "feature/login");

        assertEquals("contributor/sub", head.owner());
        assertEquals("project", head.repository());
        server.verify();
    }

    @Test
    void getPullRequestHead_sameProjectSkipsTheExtraProjectLookup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        // Identical source and target ids: no fork, so no second request. The
        // single expectation makes MockRestServiceServer fail on any extra call.
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/group%2Fproject/merge_requests/7"))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/login","sha":"abc123",
                         "source_project_id":7,"target_project_id":7}
                        """, MediaType.APPLICATION_JSON));

        PullRequestHead head = client.getPullRequestHead("group", "project", 7L, "feature/login");

        assertEquals("group", head.owner());
        assertEquals("project", head.repository());
        server.verify();
    }

    @Test
    void getPullRequestHead_rejectsBranchMismatch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/group%2Fproject/merge_requests/7"))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/source","sha":"abc123",
                         "source_project_id":7,"target_project_id":7}
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("group", "project", 7L, "main"));

        assertTrue(error.getMessage().contains("does not match"));
        server.verify();
    }

    @Test
    void getPullRequestHead_failsWhenTheSourceProjectPathIsMissing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        // Different source and target ids, so this is a fork — but the project
        // lookup answers without a path. Reporting the target instead would make
        // WorkspaceService believe this is a same-repository MR.
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/group%2Fproject/merge_requests/7"))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/login","sha":"abc123",
                         "source_project_id":12,"target_project_id":7}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/12"))
                .andRespond(withSuccess("{\"id\":12}", MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("group", "project", 7L, "feature/login"));

        assertTrue(error.getMessage().contains("did not report the project path"));
        server.verify();
    }

    @Test
    void getPullRequestHead_failsWithoutASourceProjectId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        // Without the source project id the head repository cannot be named at all;
        // assuming the target would hide a possible fork.
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/group%2Fproject/merge_requests/7"))
                .andRespond(withSuccess("""
                        {"source_branch":"feature/login","sha":"abc123","target_project_id":7}
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.getPullRequestHead("group", "project", 7L, "feature/login"));

        assertTrue(error.getMessage().contains("did not report a source project id"));
        server.verify();
    }

    @Test
    void getAuthenticatedUser_prefersCommitEmail() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CREDS.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(CREDS.baseUrl() + "/api/v4/user"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":7,\"username\":\"ai_bot\",\"name\":\"AI Bot\","
                        + "\"email\":\"private@example.com\",\"commit_email\":\"commits@example.com\"}",
                        MediaType.APPLICATION_JSON));

        GitUser user = new GitLabApiClient(builder.build(), CREDS).getAuthenticatedUser();

        assertEquals(new GitUser("7", "ai_bot", "AI Bot", "commits@example.com"), user);
        server.verify();
    }
}
