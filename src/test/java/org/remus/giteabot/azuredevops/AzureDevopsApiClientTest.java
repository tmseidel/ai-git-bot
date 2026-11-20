package org.remus.giteabot.azuredevops;

import org.remus.giteabot.repository.OwnerRequiredException;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.azuredevops.model.ThreadCommentId;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.PullRequestCommit;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.review.enrichment.CommitMessagesEnricher;
import org.remus.giteabot.review.enrichment.EnrichmentContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureDevopsApiClientTest {

    private static final String URL = "https://dev.azure.com";

    @Test
    void implementsRepositoryApiClient() {
        assertInstanceOf(RepositoryApiClient.class,
                new AzureDevopsApiClient(null, URL, null));
    }

    // ---- Organization scoping: dev.azure.com needs it in the path, the other two don't ----

    @Test
    void requestPath_onServices_carriesTheOrganization() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/7/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), "https://dev.azure.com", null)
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

        new AzureDevopsApiClient(builder.build(), base, null)
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

        new AzureDevopsApiClient(builder.build(), base, null)
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

        new AzureDevopsApiClient(builder.build(), base, null)
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

        new AzureDevopsApiClient(builder.build(), base, null)
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

        new AzureDevopsApiClient(builder.build(), base, null)
                .postPullRequestComment("contoso", "MyProject/my-service", 7L, "hi");

        server.verify();
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
                .andExpect(jsonPath("$.comments[0].commentType").value("text"))
                .andExpect(jsonPath("$.status").value("closed"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), URL, null)
                .postPullRequestComment("contoso", "MyProject/my-service", 42L, "hello");

        server.verify();
    }

    @Test
    void postPullRequestComment_returnsThreadAndCommentId() {
        // Comment ids are only unique within a thread, so the id must carry both.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":5,\"comments\":[{\"id\":1,\"content\":\"hello\"}]}",
                        MediaType.APPLICATION_JSON));

        CommentId id = new AzureDevopsApiClient(builder.build(), URL, null)
                .postPullRequestComment("contoso", "MyProject/my-service", 42L, "hello");

        assertEquals(ThreadCommentId.of(5L, 1L), id);
        server.verify();
    }

    @Test
    void postPullRequestComment_threadWithoutComments_returnsNoId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":5,\"comments\":[]}", MediaType.APPLICATION_JSON));

        assertNull(new AzureDevopsApiClient(builder.build(), URL, null)
                .postPullRequestComment("contoso", "MyProject/my-service", 42L, "hello"));
        server.verify();
    }

    @Test
    void updatePullRequestComment_patchesTheCommentInItsThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/threads/5/comments/1?api-version=6.0"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.content").value("Review completed"))
                .andRespond(withSuccess());

        new AzureDevopsApiClient(builder.build(), URL, null)
                .updatePullRequestComment("contoso", "MyProject/my-service", 42L,
                        ThreadCommentId.of(5L, 1L), "Review completed");

        server.verify();
    }

    @Test
    void updatePullRequestComment_errorsReachTheCaller() {
        // Like the other providers: the progress boundary, not the client, decides
        // whether a failed update matters.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.PATCH)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), URL, null);
        assertThrows(HttpClientErrorException.class, () -> client.updatePullRequestComment(
                "contoso", "MyProject/my-service", 42L, ThreadCommentId.of(5L, 1L), "Review completed"));

        server.verify();
    }

    @Test
    void postReviewComment_postsActiveThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/threads?api-version=6.0"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.comments[0].content").value("summary"))
                .andExpect(jsonPath("$.status").value("active"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), URL, null)
                .postReviewComment("contoso", "MyProject/my-service", 42L, "summary");

        server.verify();
    }

    @Test
    void postInlineReviewComment_sendsThreadContext() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.threadContext.filePath").value("/src/Foo.java"))
                .andExpect(jsonPath("$.threadContext.rightFileStart.line").value(12))
                // Unset left-side anchors are left out rather than sent as null.
                .andExpect(jsonPath("$.threadContext.leftFileStart").doesNotExist())
                .andExpect(jsonPath("$.threadContext.leftFileEnd").doesNotExist())
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), URL, null)
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

        String content = new AzureDevopsApiClient(builder.build(), URL, null)
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

        new AzureDevopsApiClient(builder.build(), URL, null)
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

        new AzureDevopsApiClient(builder.build(), URL, null)
                .getFileContent("contoso", "MyProject/my-service", "src/Foo.java", "main");

        server.verify();
    }

    @Test
    void getRepositoryTree_withCommitSha_asksForACommitVersion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("versionDescriptor.versionType", "commit"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        new AzureDevopsApiClient(builder.build(), URL, null)
                .getRepositoryTree("contoso", "MyProject/my-service",
                        "aaaa1111bbbb2222cccc3333dddd4444eeee5555");

        server.verify();
    }

    // ---- Tree entries must arrive in the shape every consumer reads ----

    @Test
    void getRepositoryTree_normalizesPathAndTypeForConsumers() {
        // Azure DevOps items carry gitObjectType/isFolder and an absolute path.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"path":"/","isFolder":true,"gitObjectType":"tree"},
                          {"path":"/src","isFolder":true,"gitObjectType":"tree"},
                          {"path":"/src/Foo.java","gitObjectType":"blob","objectId":"abc"}
                        ]}""", MediaType.APPLICATION_JSON));

        List<RepositoryTreeEntry> tree = new AzureDevopsApiClient(builder.build(), URL, null)
                .getRepositoryTree("contoso", "MyProject/my-service", "main");

        // The scopePath root is dropped; the rest keep their order.
        assertEquals(List.of(
                new RepositoryTreeEntry("src", RepositoryTreeEntry.Type.DIRECTORY),
                new RepositoryTreeEntry("src/Foo.java", RepositoryTreeEntry.Type.FILE)), tree);
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

        var context = new AzureDevopsApiClient(builder.build(), URL, null)
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
                                        "author":{"id":"guid-dev","uniqueName":"dev@contoso.com"}},
                                       {"id":2,"content":"second","author":{"displayName":"Dev"}}]},
                          {"comments":[{"id":3,"content":"top level"}]}
                        ]}""", MediaType.APPLICATION_JSON));

        List<ReviewComment> comments = new AzureDevopsApiClient(builder.build(), URL, null)
                .getReviewComments("contoso", "MyProject/my-service", 42L, null);

        assertEquals(3, comments.size());
        assertEquals("src/Foo.java", comments.get(0).getPath());
        assertEquals(12, comments.get(0).getLine());
        assertEquals("guid-dev", comments.get(0).getUserId());
        // Every comment of a thread inherits the thread's anchor.
        assertEquals("src/Foo.java", comments.get(1).getPath());
        // displayName is user-editable and never stands in for the author's identity.
        assertNull(comments.get(1).getUserId());
        // A thread without a threadContext is a top-level conversation comment.
        assertNull(comments.get(2).getPath());
        assertNull(comments.get(2).getLine());
        server.verify();
    }

    @Test
    void getReviewComments_unknownStatusAndCommentType_keepTheComments() {
        // One value newer than this client must not fail the whole thread list, which
        // getReviewComments would otherwise silently turn into an empty list.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"value":[
                          {"id":5,"status":"somethingNew",
                           "comments":[{"id":1,"content":"first","commentType":"somethingElse"}]}
                        ]}""", MediaType.APPLICATION_JSON));

        List<ReviewComment> comments = new AzureDevopsApiClient(builder.build(), URL, null)
                .getReviewComments("contoso", "MyProject/my-service", 42L, null);

        assertEquals(1, comments.size());
        assertEquals(ThreadCommentId.of(5L, 1L), comments.get(0).getId());
        assertEquals("first", comments.get(0).getBody());
        server.verify();
    }

    @Test
    void getFileContent_rejectsABlankPathInsteadOfRequestingSlashNull() {
        // "/" + null would be sent as the literal path "/null" and answered with an
        // opaque 404; AzureDevopsAddress.parse fails the same way for a blank repo.
        AzureDevopsApiClient client = new AzureDevopsApiClient(
                RestClient.builder().baseUrl("https://dev.azure.com").build(), URL, null);

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

        assertNull(new AzureDevopsApiClient(builder.build(), URL, null)
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

        String branch = new AzureDevopsApiClient(builder.build(), URL, null)
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

        List<Review> reviews = new AzureDevopsApiClient(builder.build(), URL, null)
                .getReviews("contoso", "MyProject/my-service", 42L);

        assertEquals(2, reviews.size());
        assertEquals("APPROVED", reviews.get(0).getState());
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

        List<PullRequestCommit> commits = new AzureDevopsApiClient(builder.build(), URL, null)
                .getPullRequestCommits("contoso", "MyProject/my-service", 42L);

        assertEquals(List.of(new PullRequestCommit("abc1234def5678", "Fix the thing")), commits);
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
                new AzureDevopsApiClient(builder.build(), URL, null), new ReviewConfigProperties())
                .enrich(new EnrichmentContext("contoso", "MyProject/my-service", 42L,
                        "", "feature", null));

        assertTrue(enriched.contains("- abc1234 Fix the thing"), enriched);
        assertTrue(enriched.contains("- 9876543 Add a test"), enriched);
        // Only the first line of a multi-line message is included.
        assertFalse(enriched.contains("with a body"), enriched);
        server.verify();
    }

    @Test
    void postReviewAction_castsApproveVoteAsTheBotsIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://dev.azure.com/contoso/MyProject/_apis/git/repositories/my-service"
                                + "/pullRequests/42/reviewers/reviewer-guid-1?api-version=6.0"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vote").value(10))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        clientVotingAs(builder, "reviewer-guid-1")
                .postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReviewAction_castsWaitingForAuthorVoteForRequestChanges() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vote").value(-5))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        clientVotingAs(builder, "reviewer-guid-1")
                .postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.REQUEST_CHANGES);

        server.verify();
    }

    @Test
    void postReviewAction_unresolvableIdentity_skipsTheVoteWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureDevopsPlatform platform = mock(AzureDevopsPlatform.class);
        when(platform.getUser(anyString())).thenThrow(new IllegalStateException("connectionData failed"));
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), URL, null);
        client.bindPlatform(platform);

        assertDoesNotThrow(() ->
                client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.APPROVE));

        server.verify();
    }

    @Test
    void postReviewAction_noneNeitherAsksForTheIdentityNorVotes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureDevopsPlatform platform = mock(AzureDevopsPlatform.class);
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), URL, null);
        client.bindPlatform(platform);

        client.postReviewAction("contoso", "MyProject/my-service", 42L, PostReviewAction.NONE);

        verifyNoInteractions(platform);
        server.verify();
    }

    /**
     * A client bound to a platform whose bot identity in the organization {@code contoso} has the
     * id {@code identityId}. Asked in any other organization it has none, so the vote is skipped
     * and the expected request never arrives.
     */
    private static AzureDevopsApiClient clientVotingAs(RestClient.Builder builder, String identityId) {
        AzureDevopsPlatform platform = mock(AzureDevopsPlatform.class);
        when(platform.getUser("contoso")).thenReturn(new RepositoryUser(identityId, "bot@contoso.com", null, null));
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), URL, null);
        client.bindPlatform(platform);
        return client;
    }

    private static final String IDENTITY = "{\"authenticatedUser\":{\"id\":\"guid-1\","
            + "\"providerDisplayName\":\"AI Bot\","
            + "\"properties\":{\"Account\":{\"$type\":\"System.String\",\"$value\":\"bot@contoso.com\"}}}}";

    private static final String IDENTITIES = "{\"count\":1,\"value\":[{\"id\":\"guid-1\","
            + "\"properties\":{\"Mail\":{\"$type\":\"System.String\",\"$value\":\"bot@example.com\"}}}]}";

    @Test
    void getAuthenticatedUser_asksInsideTheEventsOrganizationOnBareDevAzureCom() {
        // No account-level lookup: a token scoped to the event's organization is enough.
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dev.azure.com/contoso/_apis/connectionData?api-version=6.0-preview.1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vssps.dev.azure.com/contoso/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), URL, null).getAuthenticatedUser("contoso");

        assertEquals(new RepositoryUser("guid-1", "bot@contoso.com", "AI Bot", "bot@example.com"), user);
        server.verify();
    }

    @Test
    void getAuthenticatedUser_asksTheOrganizationNamedInTheUrl() {
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vssps.dev.azure.com/contoso/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("contoso");

        assertEquals("bot@contoso.com", user.username());
        assertEquals("bot@example.com", user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_asksTheLegacyHost() {
        String base = "https://contoso.visualstudio.com";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://contoso.vssps.visualstudio.com/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("contoso");

        assertEquals("bot@example.com", user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_asksTheEventsCollectionOnAServerWithoutCollection() {
        String base = "https://tfs.example.com/tfs";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/DefaultCollection/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/DefaultCollection/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("DefaultCollection");

        assertEquals("bot@contoso.com", user.username());
        assertEquals("bot@example.com", user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_asksTheServerCollectionNamedInTheUrl() {
        String base = "https://tfs.example.com/tfs/DefaultCollection";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/_apis/identities?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("DefaultCollection");

        assertEquals("bot@example.com", user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_withoutOwner_asksTheOrganizationNamedInTheUrl() {
        // At startup and on save there is no event, so the integration URL names the organization.
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vssps.dev.azure.com/contoso/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withSuccess(IDENTITIES, MediaType.APPLICATION_JSON));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser(null);

        assertEquals("guid-1", user.id());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_withoutOwnerOnBareDevAzureCom_failsWithoutAsking() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), URL, null);

        assertThrows(OwnerRequiredException.class, () -> client.getAuthenticatedUser(null));
        server.verify();
    }

    @Test
    void getAuthenticatedUser_fallsBackWhenTheTokenMayNotReadTheEmail() {
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vssps.dev.azure.com/contoso/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("contoso");

        assertEquals(RepositoryUser.FALLBACK_EMAIL, user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_fallsBackWhenTheEmailCannotBeRead() {
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess(IDENTITY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vssps.dev.azure.com/contoso/_apis/identities"
                        + "?identityIds=guid-1&queryMembership=None&api-version=6.0"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        RepositoryUser user = new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("contoso");

        assertEquals("guid-1", user.id());
        assertEquals(RepositoryUser.FALLBACK_EMAIL, user.email());
        server.verify();
    }

    @Test
    void getAuthenticatedUser_failsWithoutAnIdentity() {
        String base = "https://dev.azure.com/contoso";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(base + "/_apis/connectionData?api-version=6.0-preview.1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> new AzureDevopsApiClient(builder.build(), base, null).getAuthenticatedUser("contoso"));
    }

    @Test
    void getAuthenticatedUser_failsWithoutAnOrganization() {
        AzureDevopsApiClient client = new AzureDevopsApiClient(null, URL, null);

        assertThrows(OwnerRequiredException.class, () -> client.getAuthenticatedUser(null));
        assertThrows(OwnerRequiredException.class, () -> client.getAuthenticatedUser(" "));
    }
}
