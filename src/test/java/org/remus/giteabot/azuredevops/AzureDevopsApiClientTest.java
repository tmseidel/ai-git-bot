package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.review.enrichment.CommitMessagesEnricher;
import org.remus.giteabot.review.enrichment.EnrichmentContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureDevopsApiClientTest {

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://dev.azure.com", "https://dev.azure.com", "ado_pat");
    }

    @Test
    void implementsRepositoryApiClient() {
        assertInstanceOf(RepositoryApiClient.class,
                new AzureDevopsApiClient(null, creds(), null));
    }

    // ---- Organization scoping: dev.azure.com needs it in the path, the other two don't ----

    private static RepositoryCredentials credsFor(String url) {
        return RepositoryCredentials.of(url, url, "ado_pat");
    }

    @Test
    void requestPath_onServices_carriesTheOrganization() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor("https://dev.azure.com"), null)
                .postPullRequestComment("contoso", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void requestPath_onLegacyHost_omitsTheOrganization() {
        // https://contoso.visualstudio.com already identifies the organization. Repeating
        // it would put "contoso" where the server expects a collection, and 404.
        String base = "https://contoso.visualstudio.com";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/MyProject/_apis/git/repositories/my-service"
                        + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postPullRequestComment("contoso", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void requestPath_onServerWithCollectionInBaseUrl_omitsTheOrganization() {
        String base = "https://tfs.example.com/tfs/DefaultCollection";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/MyProject/_apis/git/repositories/my-service"
                        + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postPullRequestComment("DefaultCollection", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void requestPath_onServerWithoutCollectionInBaseUrl_keepsTheOrganization() {
        // The operator configured only the virtual directory, so the collection still has
        // to be addressed as a path segment. Deciding this from the URL rather than from
        // a host allow-list is what makes both Server layouts work.
        String base = "https://tfs.example.com/tfs";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/DefaultCollection/MyProject/_apis/git/repositories/"
                        + "my-service/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postPullRequestComment("DefaultCollection", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void requestPath_onServerHostLabelMatchingTheCollection_stillKeepsTheOrganization() {
        // Host "tfs.example.com" serving a collection literally named "tfs". Only
        // *.visualstudio.com encodes the organization in the hostname, so the collection
        // must still be addressed as a path segment here.
        String base = "https://tfs.example.com";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/tfs/MyProject/_apis/git/repositories/my-service"
                        + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postPullRequestComment("tfs", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void requestPath_onServicesUrlWronglyIncludingTheOrganization_doesNotDoubleIt() {
        // The setup guide tells operators not to enter https://dev.azure.com/contoso.
        // If they do anyway, the organization is already the last path segment, so it is
        // not appended a second time.
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/MyProject/_apis/git/repositories/my-service"
                        + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postPullRequestComment("contoso", "MyProject/my-service", 7L, "hi");

        server.verify();
    }

    @Test
    void connectionData_followsTheSameOrganizationScoping() {
        String base = "https://contoso.visualstudio.com";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"guid-1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/MyProject/_apis/git/repositories/my-service"
                        + "/pullRequests/7/reviewers/guid-1?api-version=6.0"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), credsFor(base), null)
                .postReviewAction("contoso", "MyProject/my-service", 7L,
                        PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void getRepositoryRemote_omitsTheOrganizationWhenTheCloneBaseAlreadyCarriesIt() {
        assertEquals("https://contoso.visualstudio.com/MyProject/_git/my-service",
                new AzureDevopsApiClient(RestClient.builder().build(),
                        credsFor("https://contoso.visualstudio.com"), null)
                        .getRepositoryRemote("contoso", "MyProject/my-service"));

        assertEquals("https://tfs.example.com/tfs/DefaultCollection/MyProject/_git/my-service",
                new AzureDevopsApiClient(RestClient.builder().build(),
                        credsFor("https://tfs.example.com/tfs/DefaultCollection"), null)
                        .getRepositoryRemote("DefaultCollection", "MyProject/my-service"));
    }

    @Test
    void getRepositoryRemote_rejectsCredentialBearingHttpBase() {
        // The remote is passed to git as a command-line argument and echoed back in its
        // error output, so a PAT in the clone base URL must not survive into it. The
        // interface default enforces this; the Azure DevOps override builds its own path
        // and must not lose the guarantee along the way.
        AzureDevopsApiClient client = new AzureDevopsApiClient(
                RestClient.builder().build(),
                RepositoryCredentials.of("https://dev.azure.com",
                        "https://user:pat@dev.azure.com", "ado_pat"), null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("contoso", "MyProject/my-service"));
        assertEquals("HTTP clone base URL must be credential-free", e.getMessage());
    }

    @Test
    void getRepositoryRemote_rejectsUnsupportedScheme() {
        AzureDevopsApiClient client = new AzureDevopsApiClient(
                RestClient.builder().build(),
                RepositoryCredentials.of("https://dev.azure.com",
                        "ssh://dev.azure.com", "ado_pat"), null);

        assertThrows(IllegalStateException.class,
                () -> client.getRepositoryRemote("contoso", "MyProject/my-service"));
    }

    @Test
    void getRepositoryRemote_usesGitSegment() {
        AzureDevopsApiClient client = new AzureDevopsApiClient(null, creds(), null);

        assertEquals("https://dev.azure.com/contoso/MyProject/_git/my-service",
                client.getRepositoryRemote("contoso", "MyProject/my-service"));
    }

    @Test
    void postPullRequestComment_doesNotEncodeProjectSeparator() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.comments[0].content").value("hello"))
                .andExpect(jsonPath("$.comments[0].commentType").value(1))
                .andExpect(jsonPath("$.status").value(1))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .postPullRequestComment("contoso", "MyProject/my-service", 42L, "hello");

        server.verify();
    }

    @Test
    void postInlineReviewComment_sendsThreadContext() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.threadContext.filePath").value("/src/Foo.java"))
                .andExpect(jsonPath("$.threadContext.rightFileStart.line").value(12))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .postInlineReviewComment("contoso", "MyProject/my-service", 42L,
                        "src/Foo.java", 12, "please rename");

        server.verify();
    }

    // ---- versionDescriptor.versionType: Azure DevOps defaults it to "branch" ----

    @Test
    void getFileContent_withCommitSha_asksForACommitVersion() {
        // UnitTestService passes the PR head sha. Without versionType=commit the server
        // looks the sha up as a branch name and finds nothing.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("versionDescriptor.versionType", "commit"))
                .andExpect(queryParam("versionDescriptor.version",
                        "aaaa1111bbbb2222cccc3333dddd4444eeee5555"))
                .andRespond(withSuccess("{\"content\":\"hi\"}", MediaType.APPLICATION_JSON));

        String content = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getFileContent("contoso", "MyProject/my-service", "/src/Foo.java",
                        "aaaa1111bbbb2222cccc3333dddd4444eeee5555");

        assertEquals("hi", content);
        server.verify();
    }

    @Test
    void getFileContent_withBranchName_asksForABranchVersion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("versionDescriptor.versionType", "branch"))
                .andExpect(queryParam("versionDescriptor.version", "feature/login"))
                .andRespond(withSuccess("{\"content\":\"hi\"}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .getFileContent("contoso", "MyProject/my-service", "/src/Foo.java",
                        "feature/login");

        server.verify();
    }

    @Test
    void getFileContent_makesTheItemPathAbsolute() {
        // Diff parsing and the normalized repository tree both yield slash-free paths,
        // but Azure DevOps addresses items from the repository root.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("path", "/src/Foo.java"))
                .andRespond(withSuccess("{\"content\":\"hi\"}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .getFileContent("contoso", "MyProject/my-service", "src/Foo.java", "main");

        server.verify();
    }

    @Test
    void getRepositoryTree_withCommitSha_asksForACommitVersion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("versionDescriptor.versionType", "commit"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .getRepositoryTree("contoso", "MyProject/my-service",
                        "aaaa1111bbbb2222cccc3333dddd4444eeee5555");

        server.verify();
    }

    // ---- Tree entries must arrive in the shape every consumer reads ----

    @Test
    void getRepositoryTree_normalizesPathAndTypeForConsumers() {
        // RepositoryTreeEnricher and AgentPromptBuilder#buildTreeContext read
        // entry.getOrDefault("type", "blob") and "path". Azure DevOps sends neither:
        // its items carry gitObjectType/isFolder and an absolute path.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"path":"/","isFolder":true,"gitObjectType":"tree"},
                          {"path":"/src","isFolder":true,"gitObjectType":"tree"},
                          {"path":"/src/Foo.java","gitObjectType":"blob","objectId":"abc"}
                        ]}""", MediaType.APPLICATION_JSON));

        List<java.util.Map<String, Object>> tree = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getRepositoryTree("contoso", "MyProject/my-service", "main");

        // The scopePath root is dropped; the rest keep their order.
        assertEquals(2, tree.size());
        assertEquals("src", tree.get(0).get("path"));
        assertEquals("tree", tree.get(0).get("type"));
        assertEquals("src/Foo.java", tree.get(1).get("path"));
        assertEquals("blob", tree.get(1).get("type"));
        // Native fields survive alongside the normalized ones.
        assertEquals("abc", tree.get(1).get("objectId"));
        server.verify();
    }

    // ---- Inline comment thread context ----

    @Test
    void getInlineThreadContext_readsFileAnchorFromThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/threads/5?api-version=6.0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":5,"threadContext":{"filePath":"/src/Foo.java",
                         "rightFileStart":{"line":12,"offset":1}}}""",
                        MediaType.APPLICATION_JSON));

        var context = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getInlineThreadContext("contoso", "MyProject/my-service", 42L, 5L);

        assertNotNull(context);
        // Repository-root-relative, like diff and tree paths — Azure DevOps sends
        // "/src/Foo.java", but the anchor is compared against and printed next to paths
        // that carry no leading slash.
        assertEquals("src/Foo.java", context.path());
        assertEquals(12, context.line());
        server.verify();
    }

    @Test
    void getReviewComments_flattensThreadsAndUsesRelativePaths() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"threadContext":{"filePath":"/src/Foo.java",
                                            "rightFileStart":{"line":12}},
                           "comments":[{"id":1,"content":"first",
                                        "author":{"uniqueName":"dev@contoso.com"}},
                                       {"id":2,"content":"second","author":{"displayName":"Dev"}}]},
                          {"comments":[{"id":3,"content":"top level"}]}
                        ]}""", MediaType.APPLICATION_JSON));

        List<ReviewComment> comments = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getReviewComments("contoso", "MyProject/my-service", 42L, null);

        assertEquals(3, comments.size());
        assertEquals("src/Foo.java", comments.get(0).getPath());
        assertEquals(12, comments.get(0).getLine());
        assertEquals("dev@contoso.com", comments.get(0).getUserLogin());
        // Every comment of a thread inherits the thread's anchor.
        assertEquals("src/Foo.java", comments.get(1).getPath());
        assertEquals("Dev", comments.get(1).getUserLogin());
        // A thread without a threadContext is a top-level conversation comment.
        assertNull(comments.get(2).getPath());
        assertNull(comments.get(2).getLine());
        server.verify();
    }

    @Test
    void getFileContent_rejectsABlankPathInsteadOfRequestingSlashNull() {
        // "/" + null would be sent as the literal path "/null" and answered with an
        // opaque 404; AzureDevopsAddress.parse fails the same way for a blank repo.
        AzureDevopsApiClient client = new AzureDevopsApiClient(
                RestClient.builder().baseUrl("https://dev.azure.com").build(), creds(), null);

        assertThrows(IllegalArgumentException.class,
                () -> client.getFileContent("contoso", "MyProject/my-service", null, "main"));
        assertThrows(IllegalArgumentException.class,
                () -> client.postInlineReviewComment("contoso", "MyProject/my-service", 42L,
                        "  ", 3, "body"));
    }

    @Test
    void getInlineThreadContext_returnsNullForTopLevelThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":5}", MediaType.APPLICATION_JSON));

        assertNull(new AzureDevopsApiClient(builder.build(), creds(), null)
                .getInlineThreadContext("contoso", "MyProject/my-service", 42L, 5L));
        server.verify();
    }

    @Test
    void getDefaultBranch_stripsRefsHeadsPrefix() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"defaultBranch\":\"refs/heads/main\"}",
                        MediaType.APPLICATION_JSON));

        String branch = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getDefaultBranch("contoso", "MyProject/my-service");

        assertEquals("main", branch);
        server.verify();
    }

    @Test
    void getReviews_mapsReviewerVotes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"reviewers":[
                          {"id":"g1","vote":10,"uniqueName":"a@contoso.com","displayName":"A"},
                          {"id":"g2","vote":-10,"uniqueName":"b@contoso.com","displayName":"B"}
                        ]}""", MediaType.APPLICATION_JSON));

        List<Review> reviews = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getReviews("contoso", "MyProject/my-service", 42L);

        assertEquals(2, reviews.size());
        assertEquals("APPROVED", reviews.get(0).getState());
        assertEquals("a@contoso.com", reviews.get(0).getUserLogin());
        assertEquals("REJECTED", reviews.get(1).getState());
        server.verify();
    }

    @Test
    void getPullRequestCommits_normalizesToShaAndMessage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"commitId":"abc1234def5678","comment":"Fix the thing",
                           "author":{"name":"Dev"}}
                        ]}""", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> commits = new AzureDevopsApiClient(builder.build(), creds(), null)
                .getPullRequestCommits("contoso", "MyProject/my-service", 42L);

        assertEquals(1, commits.size());
        assertEquals("abc1234def5678", commits.get(0).get("sha"));
        assertEquals("Fix the thing", commits.get(0).get("message"));
        // The native Azure DevOps fields survive alongside the normalized ones.
        assertEquals("abc1234def5678", commits.get(0).get("commitId"));
        assertEquals("Fix the thing", commits.get(0).get("comment"));
        server.verify();
    }

    @Test
    void getPullRequestCommits_reachesItsOnlyConsumerAsCommitMessages() {
        // The shape above is not cosmetic: CommitMessagesEnricher reads "sha"/"id" and
        // "message"/"commit.message", none of which Azure DevOps sends. Handing it the
        // raw entries yields a "Commit messages:" heading with nothing under it, in
        // every Azure DevOps review prompt, with no error anywhere.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"commitId":"abc1234def5678","comment":"Fix the thing"},
                          {"commitId":"9876543210fedc","comment":"Add a test\\nwith a body"}
                        ]}""", MediaType.APPLICATION_JSON));

        String enriched = new CommitMessagesEnricher(
                new AzureDevopsApiClient(builder.build(), creds(), null), new ReviewConfigProperties())
                .enrich(new EnrichmentContext("contoso", "MyProject/my-service", 42L,
                        "", "feature", null));

        assertTrue(enriched.contains("- abc1234 Fix the thing"), enriched);
        assertTrue(enriched.contains("- 9876543 Add a test"), enriched);
        // Only the first line of a multi-line message is included.
        assertFalse(enriched.contains("with a body"), enriched);
        server.verify();
    }

    @Test
    void postReviewAction_skipsSilentlyWhenIdentityUnresolved() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // connectionData answers without an identity, so there is nobody to vote as and
        // no PUT may follow — a second queued request would fail the mock.
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> new AzureDevopsApiClient(builder.build(), creds(), null)
                .postReviewAction("contoso", "MyProject/my-service", 42L,
                        PostReviewAction.APPROVE));

        server.verify();
    }

    @Test
    void formatPullRequestReference_usesHash() {
        assertEquals("#42", new AzureDevopsApiClient(null, creds(), null)
                .formatPullRequestReference(42L));
    }

    @Test
    void postReviewAction_castsApproveVoteWhenIdentityResolved() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"reviewer-guid-1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/reviewers/reviewer-guid-1?api-version=6.0"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vote").value(10))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .postReviewAction("contoso", "MyProject/my-service", 42L,
                        PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReviewAction_resolvesTheIdentityPerOrganization() {
        // One integration on dev.azure.com serves many organizations, and an Azure DevOps
        // identity id is issued per organization. Caching a single GUID for the client
        // would send fabrikam the identity contoso answered with, which addresses no
        // reviewer there.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(requestTo("https://dev.azure.com/contoso/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"guid-contoso\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dev.azure.com/fabrikam/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"guid-fabrikam\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/reviewers/guid-contoso?api-version=6.0"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://dev.azure.com/fabrikam/OtherProject/_apis/git/repositories/other-service"
                                + "/pullRequests/7/reviewers/guid-fabrikam?api-version=6.0"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), creds(), null);
        client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE);
        client.postReviewAction("fabrikam", "OtherProject/other-service", 7L,
                PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReviewAction_doesNotRepeatADefinitivelyFailedIdentityLookup() {
        // An answered-but-empty connectionData is an answer, not an outage. Only one
        // lookup is queued; a repeat on the second vote would fail the mock.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dev.azure.com/contoso/_apis/connectionData"
                        + "?api-version=6.0-preview.1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(),
                RepositoryCredentials.of("https://dev.azure.com", "https://dev.azure.com",
                        "bot@contoso.com", "ado_pat"), null);
        client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE);
        client.postReviewAction("contoso", "MyProject/my-service", 43L, PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReviewAction_retriesTheIdentityLookupAfterATransportFailure() {
        // A 503 says nothing about whether the token has an identity here, so the next
        // vote must look again rather than reuse a cached "no identity".
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        AtomicInteger connectionDataCalls = new AtomicInteger();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            connectionDataCalls.incrementAndGet();
            return withServerError().createResponse(request);
        });

        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), creds(), null);
        client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE);
        client.postReviewAction("contoso", "MyProject/my-service", 43L, PostReviewAction.APPROVE);

        assertEquals(2, connectionDataCalls.get(),
                "a transport failure must not be cached as 'no identity'");
    }

    @Test
    void postReviewAction_resolvesTheIdentityOnlyOncePerOrganization() {
        // The second vote in the same organization must reuse the cached GUID: only one
        // connectionData lookup is expected, and a third request would fail the mock.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dev.azure.com/contoso/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"guid-contoso\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.PUT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.PUT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), creds(), null);
        client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE);
        client.postReviewAction("Contoso", "MyProject/my-service", 43L,
                PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void postReviewAction_castsRejectVoteForRequestChanges() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"authenticatedUser\":{\"id\":\"reviewer-guid-1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vote").value(-10))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), creds(), null)
                .postReviewAction("contoso", "MyProject/my-service", 42L,
                        PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }
}
