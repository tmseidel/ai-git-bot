package org.remus.giteabot.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.TestBotAccounts;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.model.WebhookPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link GitHubWebhookTranslator} turns GitHub webhooks into {@link WebhookEvent}s.
 * What the bot then does with each kind is covered by {@code WebhookEventDispatcherTest}.
 */
class GitHubWebhookTranslatorTest {

    private GitHubWebhookTranslator translator;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new GitHubWebhookTranslator(TestBotAccounts.resolver("ai_bot"));
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        bot.setGitIntegration(gitIntegration);
    }

    private WebhookEvent translate(String eventType, Map<String, Object> payload) {
        return translator.translate(bot, Map.of(GitHubWebhookTranslator.EVENT_HEADER, eventType), payload);
    }

    // ---- Issues ----

    @Test
    void issueAssignedToBot_withCustomIssueRef_carriesTheRef() {
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "ai_bot", "release/1.2"));

        WebhookEvent event = translate("issues", payload);

        assertEquals(WebhookEventKind.ISSUE_ASSIGNED, event.kind());
        assertEquals("release/1.2", event.payload().getIssue().getRef());
    }

    @Test
    void unresolvableBotAccount_ignoresEvent() {
        translator = new GitHubWebhookTranslator(TestBotAccounts.unresolvable("401 Bad credentials"));
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "ai_bot", null));

        assertEquals(WebhookEventKind.IGNORED, translate("issues", payload).kind());
    }

    @Test
    void translatePayload_issueWithoutRef_keepsRefNull() {
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "ai_bot", null));

        WebhookPayload translated = translator.translatePayload("issues", payload);

        assertNull(translated.getIssue().getRef());
    }

    @Test
    void issueAssignedToSomeoneElse_isIgnored() {
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "someone_else", null));

        assertEquals(WebhookEventKind.IGNORED, translate("issues", payload).kind());
    }

    @Test
    void issueAssignedToBotAsSecondAssignee_isIssueAssigned() {
        // issue.assignee stays the first assignee; the top-level assignee names the new one.
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "someone_else", null));
        payload.put("assignee", ownerMap("ai_bot"));

        assertEquals(WebhookEventKind.ISSUE_ASSIGNED, translate("issues", payload).kind());
    }

    @Test
    void issueAssignedToSomeoneElseWhileBotIsFirstAssignee_isIgnored() {
        Map<String, Object> payload = issuePayload("assigned", issueMapWithAssigneeAndRef(42L, "ai_bot", null));
        payload.put("assignee", ownerMap("someone_else"));

        assertEquals(WebhookEventKind.IGNORED, translate("issues", payload).kind());
    }

    @Test
    void issueOpened_isIssueOpened() {
        Map<String, Object> payload = issuePayload("opened", issueMapWithAssigneeAndRef(42L, null, null));

        assertEquals(WebhookEventKind.ISSUE_OPENED, translate("issues", payload).kind());
    }

    @Test
    void issueReopened_isIssueReopened() {
        Map<String, Object> payload = issuePayload("reopened", issueMapWithAssigneeAndRef(42L, null, null));

        assertEquals(WebhookEventKind.ISSUE_REOPENED, translate("issues", payload).kind());
    }

    // ---- Pull request lifecycle ----

    @Test
    void pullRequestOpenedWithBotReviewer_isPrOpenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", prPayload("opened", List.of(ownerMap("ai_bot"))));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertTrue(event.botIsReviewer());
        assertFalse(event.fromBot());
    }

    @Test
    void pullRequestOpenedWithoutBotReviewer_isPrOpenedWithoutTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", prPayload("opened", List.of(ownerMap("human"))));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @Test
    void pullRequestOpenedWithBotAssignee_isNotAnAssignment() {
        // Only assigning the bot to an existing pull request counts.
        Map<String, Object> payload = prPayload("opened", List.of());
        pullRequest(payload).put("assignees", List.of(ownerMap("ai_bot")));

        WebhookEvent event = translate("pull_request", payload);

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @Test
    void pullRequestOpenedByTheBot_isFromTheBot() {
        Map<String, Object> payload = prPayload("opened", List.of());
        payload.put("sender", ownerMap("ai_bot"));

        assertTrue(translate("pull_request", payload).fromBot());
    }

    @Test
    void pullRequestSynchronize_isPrSynchronized() {
        WebhookEvent event = translate("pull_request", prPayload("synchronize", List.of(ownerMap("ai_bot"))));

        assertEquals(WebhookEventKind.PR_SYNCHRONIZED, event.kind());
    }

    @Test
    void pullRequestReopenedWithBotReviewer_isPrReopenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", prPayload("reopened", List.of(ownerMap("ai_bot"))));

        assertEquals(WebhookEventKind.PR_REOPENED, event.kind());
        assertTrue(event.botIsReviewer());
    }

    @Test
    void pullRequestReopenedWithoutBotReviewer_isPrReopenedWithoutTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", prPayload("reopened", List.of(ownerMap("human"))));

        assertEquals(WebhookEventKind.PR_REOPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @Test
    void pullRequestClosed_isPrClosed() {
        assertEquals(WebhookEventKind.PR_CLOSED,
                translate("pull_request", prPayload("closed", List.of())).kind());
    }

    @Test
    void pullRequestReviewRequestedForBot_isPrReviewerAdded() {
        Map<String, Object> payload = prPayload("review_requested", List.of(ownerMap("ai_bot")));
        payload.put("requested_reviewer", ownerMap("ai_bot"));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, translate("pull_request", payload).kind());
    }

    @Test
    void pullRequestReviewRequestedFromSomeoneElse_isIgnored() {
        Map<String, Object> payload = prPayload("review_requested", List.of(ownerMap("human")));
        payload.put("requested_reviewer", ownerMap("human"));

        assertEquals(WebhookEventKind.IGNORED, translate("pull_request", payload).kind());
    }

    @Test
    void pullRequestAssignedToBot_isPrAssigned() {
        Map<String, Object> payload = prPayload("assigned", List.of());
        payload.put("assignee", ownerMap("ai_bot"));

        assertEquals(WebhookEventKind.PR_ASSIGNED, translate("pull_request", payload).kind());
    }

    @Test
    void pullRequestAssignedToSomeoneElse_whileTheBotIsAssigned_isIgnored() {
        Map<String, Object> payload = prPayload("assigned", List.of());
        pullRequest(payload).put("assignees", List.of(ownerMap("ai_bot"), ownerMap("human")));
        payload.put("assignee", ownerMap("human"));

        assertEquals(WebhookEventKind.IGNORED, translate("pull_request", payload).kind());
    }

    // ---- Reviews ----

    @Test
    void submittedReview_isPrReviewSubmitted() {
        Map<String, Object> payload = prPayload("submitted", List.of());
        payload.put("review", new HashMap<>(Map.of("id", 9, "state", "approved", "body", "LGTM")));

        WebhookEvent event = translate("pull_request_review", payload);

        assertEquals(WebhookEventKind.PR_REVIEW_SUBMITTED, event.kind());
        assertEquals("approved", event.payload().getReview().getType());
    }

    @Test
    void editedReview_isIgnored() {
        Map<String, Object> payload = prPayload("edited", List.of());
        payload.put("review", new HashMap<>(Map.of("id", 9, "state", "approved", "body", "LGTM")));

        assertEquals(WebhookEventKind.IGNORED, translate("pull_request_review", payload).kind());
    }

    // ---- Comments ----

    @Test
    void reviewCommentWithoutPullRequest_isIgnored() {
        Map<String, Object> comment = commentMap("@ai_bot why?");
        comment.put("path", "src/Foo.java");
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("comment", comment);

        assertEquals(WebhookEventKind.IGNORED, translate("pull_request_review_comment", payload).kind());
    }

    @Test
    void reviewComment_isPrInlineCommentWithItsAnchor() {
        Map<String, Object> comment = commentMap("@ai_bot why?");
        comment.put("path", "src/Foo.java");
        comment.put("line", 12);
        Map<String, Object> payload = prPayload("created", List.of());
        payload.put("comment", comment);

        WebhookEvent event = translate("pull_request_review_comment", payload);

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals("src/Foo.java", event.payload().getComment().getPath());
        assertEquals(12, event.payload().getComment().getLine());
    }

    @Test
    void prConversationComment_isPrCommentMentioningTheBot() {
        Map<String, Object> issue = issueMapWithAssigneeAndRef(140L, null, null);
        issue.put("pull_request", new HashMap<>(Map.of("url", "https://api.github.com/repos/Test/my-repo/pulls/140")));
        Map<String, Object> payload = issuePayload("created", issue);
        payload.put("comment", commentMap("@ai_bot please fix"));

        WebhookEvent event = translate("issue_comment", payload);

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertTrue(event.botMentioned());
    }

    @Test
    void plainIssueComment_isIssueComment() {
        Map<String, Object> payload = issuePayload("created", issueMapWithAssigneeAndRef(42L, null, null));
        payload.put("comment", commentMap("@ai_bot any idea?"));

        assertEquals(WebhookEventKind.ISSUE_COMMENT, translate("issue_comment", payload).kind());
    }

    @Test
    void editedComment_isIgnored() {
        Map<String, Object> payload = issuePayload("edited", issueMapWithAssigneeAndRef(42L, null, null));
        payload.put("comment", commentMap("@ai_bot any idea?"));

        assertEquals(WebhookEventKind.IGNORED, translate("issue_comment", payload).kind());
    }

    private Map<String, Object> issuePayload(String action, Map<String, Object> issue) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issue);
        return payload;
    }

    private Map<String, Object> commentMap(String body) {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 1055);
        comment.put("body", body);
        comment.put("user", ownerMap("tom"));
        return comment;
    }

    private Map<String, Object> ownerMap(String login) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", "id-" + login);
        m.put("login", login);
        return m;
    }

    private Map<String, Object> repositoryMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 1);
        m.put("name", "my-repo");
        m.put("full_name", "Test/my-repo");
        m.put("owner", ownerMap("Test"));
        return m;
    }

    private Map<String, Object> prPayload(String action, List<Map<String, Object>> reviewers) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        Map<String, Object> pr = new HashMap<>();
        pr.put("id", 80);
        pr.put("number", 140);
        pr.put("title", "Some PR");
        pr.put("body", "");
        pr.put("state", "open");
        pr.put("merged", false);
        pr.put("user", ownerMap("tom"));
        pr.put("requested_reviewers", reviewers);
        pr.put("head", Map.of("ref", "feature", "sha", "abc123"));
        payload.put("pull_request", pr);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pullRequest(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("pull_request");
    }

    private Map<String, Object> issueMapWithAssigneeAndRef(long number, String assigneeLogin, String ref) {
        Map<String, Object> m = new HashMap<>();
        m.put("number", number);
        m.put("title", "Some issue");
        m.put("body", "");
        m.put("assignee", ownerMap(assigneeLogin));
        m.put("ref", ref);
        return m;
    }
}
