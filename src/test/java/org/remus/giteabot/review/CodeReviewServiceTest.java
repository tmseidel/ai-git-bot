package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewServiceTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    @Mock
    private AiClient aiClient;

    @Mock
    private SessionService sessionService;

    private static final String TEST_PROMPT = "test prompt";
    private static final String SESSION_PROMPT_KEY = "system-prompt:1";

    private CodeReviewService codeReviewService;

    @BeforeEach
    void setUp() {
        codeReviewService = new CodeReviewService(repositoryClient, aiClient,
                sessionService, "ai_bot", new ReviewConfigProperties(), SESSION_PROMPT_KEY, TEST_PROMPT,
                120000, 8, 60000);
    }

    @Test
    void reviewPullRequest_postsReview() {
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(repositoryClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(aiClient.submitReviewPrompt(eq(TEST_PROMPT), isNull(), anyString()))
                .thenReturn("Looks good!");

        codeReviewService.reviewPullRequest(payload, null);

        verify(repositoryClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Looks good!"));
        verify(sessionService).getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY);
        verify(sessionService, times(2)).addMessage(any(), anyString(), anyString());
    }

    @Test
    void reviewPullRequest_emptyDiff_skipsReview() {
        WebhookPayload payload = createTestPayload();

        when(repositoryClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("");

        codeReviewService.reviewPullRequest(payload, null);

        verify(aiClient, never()).submitReviewPrompt(anyString(), anyString(), anyString());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void reviewPullRequest_withPromptName_usesConfiguredBotPrompt() {
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, "security");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(repositoryClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(aiClient.submitReviewPrompt(eq(TEST_PROMPT), isNull(), anyString()))
                .thenReturn("Bot prompt used.");

        codeReviewService.reviewPullRequest(payload, "security");

        verify(repositoryClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Bot prompt used."));
    }

    @Test
    void reviewPullRequest_withConfiguredSystemPrompt_usesBotPrompt() {
        codeReviewService = new CodeReviewService(repositoryClient, aiClient,
                sessionService, "ai_bot", new ReviewConfigProperties(), SESSION_PROMPT_KEY, "Configured review prompt",
                120000, 8, 60000);
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(repositoryClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(aiClient.submitReviewPrompt(eq("Configured review prompt"), isNull(), anyString()))
                .thenReturn("Configured prompt used.");

        codeReviewService.reviewPullRequest(payload, null);

        verify(repositoryClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Configured prompt used."));
    }

    @Test
    void reviewPullRequest_existingSession_usesChat() {
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Previous question");
        session.addMessage("assistant", "Previous answer");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAiMessages(session)).thenReturn(List.of(
                AiMessage.builder().role("user").content("Previous question").build(),
                AiMessage.builder().role("assistant").content("Previous answer").build()
        ));
        when(repositoryClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("new diff content");
        when(aiClient.chat(anyList(), anyString(), eq(TEST_PROMPT), isNull()))
                .thenReturn("Updated review");

        codeReviewService.reviewPullRequest(payload, null);

        verify(aiClient).chat(anyList(), anyString(), eq(TEST_PROMPT), isNull());
        verify(aiClient, never()).submitReviewPrompt(anyString(), anyString(), anyString());
    }

    @Test
    void handleBotCommand_addsReactionAndResponds() {
        WebhookPayload payload = createCommentPayload("@ai_bot explain this");
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Initial context");
        session.addMessage("assistant", "Initial review");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAiMessages(session)).thenReturn(List.of(
                AiMessage.builder().role("user").content("Initial context").build(),
                AiMessage.builder().role("assistant").content("Initial review").build()
        ));
        when(aiClient.chat(anyList(), eq("@ai_bot explain this"), eq(TEST_PROMPT), isNull()))
                .thenReturn("Here's my explanation");

        codeReviewService.handleBotCommand(payload, null);

        verify(repositoryClient).addReaction("testowner", "testrepo", 42L, "eyes");
        verify(repositoryClient).postPullRequestComment(eq("testowner"), eq("testrepo"), eq(1L), contains("Here's my explanation"));
    }

    @Test
    void handlePrClosed_deletesSession() {
        WebhookPayload payload = createTestPayload();

        codeReviewService.handlePrClosed(payload);

        verify(sessionService).deleteSession("testowner", "testrepo", 1L);
    }

    @Test
    void formatReviewComment_containsHeader() {
        String result = codeReviewService.formatReviewComment("some review text");
        assertTrue(result.contains("🤖 AI Code Review"));
        assertTrue(result.contains("some review text"));
        assertTrue(result.contains("Automated review by AI Git Bot"));
    }

    @Test
    void formatBotResponse_containsHeader() {
        String result = codeReviewService.formatBotResponse("some response");
        assertTrue(result.contains("🤖 Bot Response"));
        assertTrue(result.contains("some response"));
        assertTrue(result.contains("Response by AI Git Bot"));
    }

    @Test
    void handleInlineComment_postsInlineReviewComment() {
        WebhookPayload payload = createInlineCommentPayload(
                "@ai_bot explain this", "src/main/java/Foo.java",
                "@@ -10,7 +10,7 @@\n code context", 15);
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Initial context");
        session.addMessage("assistant", "Initial review");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAiMessages(session)).thenReturn(List.of(
                AiMessage.builder().role("user").content("Initial context").build(),
                AiMessage.builder().role("assistant").content("Initial review").build()
        ));
        when(aiClient.chat(anyList(), contains("src/main/java/Foo.java"), eq(TEST_PROMPT), isNull()))
                .thenReturn("Here's the explanation");

        codeReviewService.handleInlineComment(payload, null);

        verify(repositoryClient).addReaction("testowner", "testrepo", 55L, "eyes");
        verify(repositoryClient).postInlineReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L),
                eq("src/main/java/Foo.java"), eq(15),
                contains("Here's the explanation"));
    }

    @Test
    void handleInlineComment_fallsBackToRegularComment_whenNoLine() {
        WebhookPayload payload = createInlineCommentPayload(
                "@ai_bot explain this", "src/main/java/Foo.java",
                "@@ -10,7 +10,7 @@\n code context", null);
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Initial context");
        session.addMessage("assistant", "Initial review");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAiMessages(session)).thenReturn(List.of(
                AiMessage.builder().role("user").content("Initial context").build(),
                AiMessage.builder().role("assistant").content("Initial review").build()
        ));
        when(aiClient.chat(anyList(), anyString(), eq(TEST_PROMPT), isNull()))
                .thenReturn("Explanation without line");

        codeReviewService.handleInlineComment(payload, null);

        verify(repositoryClient, never()).postInlineReviewComment(anyString(), anyString(), anyLong(),
                anyString(), anyInt(), anyString());
        verify(repositoryClient).postPullRequestComment(eq("testowner"), eq("testrepo"), eq(1L),
                contains("Explanation without line"));
    }

    @Test
    void buildInlineCommentContext_includesFileAndDiffHunk() {
        String result = codeReviewService.buildInlineCommentContext(
                "src/Main.java", "@@ -1,5 +1,5 @@\n code", "@ai_bot explain this");
        assertTrue(result.contains("src/Main.java"));
        assertTrue(result.contains("@@ -1,5 +1,5 @@"));
        assertTrue(result.contains("@ai_bot explain this"));
    }

    @Test
    void buildInlineCommentContext_withoutDiffHunk() {
        String result = codeReviewService.buildInlineCommentContext(
                "src/Main.java", null, "@ai_bot what is this?");
        assertTrue(result.contains("src/Main.java"));
        assertFalse(result.contains("diff hunk"));
        assertTrue(result.contains("@ai_bot what is this?"));
    }

    @Test
    void resolvePrNumber_fromIssue() {
        WebhookPayload payload = createCommentPayload("test");
        Long result = codeReviewService.resolvePrNumber(payload);
        assertEquals(1L, result);
    }

    @Test
    void resolvePrNumber_fromPullRequest() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(5L);
        payload.setPullRequest(pr);
        Long result = codeReviewService.resolvePrNumber(payload);
        assertEquals(5L, result);
    }

    @Test
    void handleReviewSubmitted_fetchesAndProcessesBotMentions() {
        WebhookPayload payload = createReviewSubmittedPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 2L, null);
        session.addMessage("user", "Initial context");
        session.addMessage("assistant", "Initial review");

        when(sessionService.getOrCreateSession("testowner", "testrepo", 2L, SESSION_PROMPT_KEY)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAiMessages(session)).thenReturn(List.of(
                AiMessage.builder().role("user").content("Initial context").build(),
                AiMessage.builder().role("assistant").content("Initial review").build()
        ));

        // Set up review fetching
        GiteaReview review = new GiteaReview();
        review.setId(10L);
        review.setState("COMMENT");
        when(repositoryClient.getReviews("testowner", "testrepo", 2L))
                .thenReturn(List.of(review));

        // Set up review comments - one with bot mention, one without
        GiteaReviewComment botComment = new GiteaReviewComment();
        botComment.setId(100L);
        botComment.setBody("@ai_bot explain this");
        botComment.setPath("src/main/java/Foo.java");
        botComment.setDiffHunk("@@ -10,7 +10,7 @@\n code");
        botComment.setLine(15);

        GiteaReviewComment normalComment = new GiteaReviewComment();
        normalComment.setId(101L);
        normalComment.setBody("just a regular comment");
        normalComment.setPath("src/main/java/Bar.java");
        normalComment.setLine(5);

        when(repositoryClient.getReviewComments("testowner", "testrepo", 2L, 10L))
                .thenReturn(List.of(botComment, normalComment));

        when(aiClient.chat(anyList(), contains("src/main/java/Foo.java"), eq(TEST_PROMPT), isNull()))
                .thenReturn("Here's the explanation");

        codeReviewService.handleReviewSubmitted(payload, null);

        // Should only process the bot-mentioning comment
        verify(repositoryClient).addReaction("testowner", "testrepo", 100L, "eyes");
        verify(repositoryClient).postInlineReviewComment(
                eq("testowner"), eq("testrepo"), eq(2L),
                eq("src/main/java/Foo.java"), eq(15),
                contains("Here's the explanation"));

        // Should NOT react to the normal comment
        verify(repositoryClient, never()).addReaction("testowner", "testrepo", 101L, "eyes");
    }

    @Test
    void handleReviewSubmitted_noReviews_doesNothing() {
        WebhookPayload payload = createReviewSubmittedPayload();

        when(repositoryClient.getReviews("testowner", "testrepo", 2L))
                .thenReturn(List.of());

        codeReviewService.handleReviewSubmitted(payload, null);

        verify(aiClient, never()).chat(anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void handleReviewSubmitted_noBotMentions_doesNothing() {
        WebhookPayload payload = createReviewSubmittedPayload();


        GiteaReview review = new GiteaReview();
        review.setId(10L);
        when(repositoryClient.getReviews("testowner", "testrepo", 2L))
                .thenReturn(List.of(review));

        GiteaReviewComment normalComment = new GiteaReviewComment();
        normalComment.setId(101L);
        normalComment.setBody("just a regular comment");
        when(repositoryClient.getReviewComments("testowner", "testrepo", 2L, 10L))
                .thenReturn(List.of(normalComment));

        codeReviewService.handleReviewSubmitted(payload, null);

        verify(aiClient, never()).chat(anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void handleReviewSubmitted_botOwnComments_filtered() {
        WebhookPayload payload = createReviewSubmittedPayload();


        GiteaReview review = new GiteaReview();
        review.setId(10L);
        when(repositoryClient.getReviews("testowner", "testrepo", 2L))
                .thenReturn(List.of(review));

        // Bot's own comment that mentions itself (e.g. from its formatted response)
        GiteaReviewComment botOwnComment = new GiteaReviewComment();
        botOwnComment.setId(200L);
        botOwnComment.setBody("## 🤖 Bot Response\n\nSome answer mentioning @ai_bot");
        botOwnComment.setPath("src/main/java/Foo.java");
        botOwnComment.setLine(10);
        GiteaReview.GiteaUser botUser = new GiteaReview.GiteaUser();
        botUser.setLogin("ai_bot");
        botOwnComment.setUser(botUser);

        when(repositoryClient.getReviewComments("testowner", "testrepo", 2L, 10L))
                .thenReturn(List.of(botOwnComment));

        codeReviewService.handleReviewSubmitted(payload, null);

        // Bot's own comment should be filtered out — no AI call, no reaction
        verify(aiClient, never()).chat(anyList(), anyString(), anyString(), anyString());
        verify(repositoryClient, never()).addReaction(anyString(), anyString(), anyLong(), anyString());
    }

    private WebhookPayload createTestPayload() {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("opened");

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(1L);
        pr.setTitle("Test PR");
        pr.setBody("Test body");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature-branch");
        pr.setHead(head);
        payload.setPullRequest(pr);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }

    private WebhookPayload createCommentPayload(String body) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(42L);
        comment.setBody(body);
        WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
        commentUser.setLogin("testuser");
        comment.setUser(commentUser);
        payload.setComment(comment);

        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(1L);
        issue.setTitle("Test PR");
        issue.setBody("Test body");
        issue.setPullRequest(new WebhookPayload.IssuePullRequest());
        payload.setIssue(issue);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }

    private WebhookPayload createInlineCommentPayload(String body, String path, String diffHunk, Integer line) {
        WebhookPayload payload = createCommentPayload(body);
        payload.getComment().setId(55L);
        payload.getComment().setPath(path);
        payload.getComment().setDiffHunk(diffHunk);
        payload.getComment().setLine(line);
        return payload;
    }

    private WebhookPayload createReviewSubmittedPayload() {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("reviewed");

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(2L);
        pr.setTitle("Test PR");
        pr.setBody("Test body");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature-branch");
        pr.setHead(head);
        payload.setPullRequest(pr);

        WebhookPayload.Review review = new WebhookPayload.Review();
        review.setType("pull_request_review_comment");
        review.setContent("");
        payload.setReview(review);

        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("tom");
        payload.setSender(sender);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }
}
