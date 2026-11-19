package org.remus.giteabot.gitea;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link GiteaWebhookTranslator} turns Gitea webhooks into {@link WebhookEvent}s.
 * What the bot then does with each kind is covered by {@code WebhookEventDispatcherTest}.
 */
class GiteaWebhookTranslatorTest {

    private GiteaWebhookTranslator translator;
    private Bot bot;

    private static final String BOT_USERNAME = "claude_bot";
    private static final String BOT_ALIAS = "@claude_bot";

    @BeforeEach
    void setUp() {
        translator = new GiteaWebhookTranslator();
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setUsername(BOT_USERNAME);
        bot.setGitIntegration(gitIntegration);
    }

    private WebhookEvent translate(String eventType, Map<String, Object> payload) {
        return translator.translate(bot, headers(eventType), payload);
    }

    // ---- Comments ----

    @Test
    void prCommentWithBotMention_isPrCommentMentioningTheBot() {
        WebhookEvent event = translate("issue_comment", buildPrCommentPayload(BOT_ALIAS + " please fix this"));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertFalse(event.fromBot());
    }

    @Test
    void prCommentWithoutBotMention_isPrCommentNotMentioningTheBot() {
        WebhookEvent event = translate("issue_comment", buildPrCommentPayload("just a regular comment"));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertFalse(event.botMentioned());
    }

    @Test
    void editedComment_isIgnored() {
        Map<String, Object> payload = buildPrCommentPayload(BOT_ALIAS + " do something");
        payload.put("action", "edited");

        assertEquals(WebhookEventKind.IGNORED, translate("issue_comment", payload).kind());
    }

    @Test
    void issueComment_isIssueComment() {
        WebhookEvent event = translate("issue_comment", buildIssueCommentPayload(BOT_ALIAS + " implement this feature"));

        assertEquals(WebhookEventKind.ISSUE_COMMENT, event.kind());
        assertTrue(event.botMentioned());
    }

    @Test
    void prConversationComment_withoutTopLevelPullRequest_isPrComment() {
        // Real Gitea delivers a PR-conversation comment as an issue_comment event with
        // issue.pull_request set but NO top-level pull_request. It is a pull request comment,
        // not an issue comment; the dispatcher adds the pull request half.
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMap(140L, true));   // issue WITH pull_request link
        // deliberately no top-level "pull_request" key
        payload.put("comment", commentMap(1055L, BOT_ALIAS + " please explain this change", null));

        WebhookEvent event = translate("issue_comment", payload);

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertEquals(140L, event.payload().getIssue().getNumber());
        assertNull(event.payload().getPullRequest());
    }

    @Test
    void ownBotComment_isFromTheBot() {
        Map<String, Object> payload = buildPrCommentPayload(BOT_ALIAS + " something");
        payload.put("sender", ownerMap(BOT_USERNAME));

        assertTrue(translate("issue_comment", payload).fromBot());
    }

    @Test
    void inlineComment_isPrInlineCommentWithItsPath() {
        WebhookEvent event = translate("issue_comment", buildInlineCommentPayload(BOT_ALIAS + " explain this"));

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals("src/main/Foo.java", event.payload().getComment().getPath());
    }

    // ---- Pull request lifecycle ----

    @Test
    void prOpened_isPrOpenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertTrue(event.botIsReviewer());
    }

    @Test
    @SuppressWarnings("unchecked")
    void prOpenedWithoutBotReviewer_isPrOpenedWithoutTheBotAsReviewer() {
        Map<String, Object> payload = buildPrEventPayload("opened");
        ((Map<String, Object>) payload.get("pull_request")).put("requested_reviewers", List.of());

        WebhookEvent event = translate("pull_request", payload);

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @Test
    void prSynchronized_isPrSynchronized() {
        assertEquals(WebhookEventKind.PR_SYNCHRONIZED,
                translate("pull_request", buildPrEventPayload("synchronized")).kind());
    }

    @Test
    void prReopened_isPrReopenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("pull_request", buildPrEventPayload("reopened"));

        assertEquals(WebhookEventKind.PR_REOPENED, event.kind());
        assertTrue(event.botIsReviewer());
    }

    @Test
    void prReviewRequestedForBot_isPrReviewerAdded() {
        Map<String, Object> payload = buildPrEventPayload("review_requested");
        payload.put("requested_reviewer", ownerMap(BOT_USERNAME));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, translate("pull_request", payload).kind());
    }

    @Test
    void prReviewRequestedFromSomeoneElse_isIgnored() {
        Map<String, Object> payload = buildPrEventPayload("review_requested");
        payload.put("requested_reviewer", ownerMap("someone_else"));

        assertEquals(WebhookEventKind.IGNORED, translate("pull_request", payload).kind());
    }

    @Test
    void prClosed_isPrClosed() {
        assertEquals(WebhookEventKind.PR_CLOSED, translate("pull_request", buildPrEventPayload("closed")).kind());
    }

    @Test
    void prAssignedToBot_isPrAssigned() {
        assertEquals(WebhookEventKind.PR_ASSIGNED,
                translate("pull_request", buildPrAssignedPayload(BOT_USERNAME)).kind());
    }

    @Test
    void prAssignedToSomeoneElse_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED,
                translate("pull_request", buildPrAssignedPayload("someone_else")).kind());
    }

    // ---- Issues ----

    @Test
    void issueAssignedToBot_isIssueAssigned() {
        assertEquals(WebhookEventKind.ISSUE_ASSIGNED,
                translate("issues", buildIssueAssignedPayload(BOT_USERNAME)).kind());
    }

    @Test
    void issueAssignedToDifferentUser_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED,
                translate("issues", buildIssueAssignedPayload("someone_else")).kind());
    }

    @Test
    @SuppressWarnings("unchecked")
    void issueAssignedToBotAsSecondAssignee_isIssueAssigned() {
        // issue.assignee is only the first of issue.assignees.
        Map<String, Object> payload = buildIssueAssignedPayload("someone_else");
        ((Map<String, Object>) payload.get("issue"))
                .put("assignees", List.of(ownerMap("someone_else"), ownerMap(BOT_USERNAME)));

        assertEquals(WebhookEventKind.ISSUE_ASSIGNED, translate("issues", payload).kind());
    }

    @Test
    void issueOpened_isIssueOpened() {
        assertEquals(WebhookEventKind.ISSUE_OPENED, translate("issues", buildIssueCreatedPayload("opened")).kind());
    }

    @Test
    void issueReopened_isIssueReopened() {
        assertEquals(WebhookEventKind.ISSUE_REOPENED, translate("issues", buildIssueCreatedPayload("reopened")).kind());
    }

    // ---- Classification ----

    @Test
    void issueClosed_isIgnored() {
        WebhookEvent event = translator.translate(bot, headers("issues"), buildIssueCreatedPayload("closed"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void reviewedWithoutReview_isIgnored() {
        WebhookEvent event = translator.translate(bot, headers("pull_request_comment"), buildPrEventPayload("reviewed"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void reviewedWithReview_isPrReviewSubmitted() {
        Map<String, Object> payload = buildPrEventPayload("reviewed");
        payload.put("review", new HashMap<>(Map.of("id", 7, "type", "comment", "content", "looks good")));

        WebhookEvent event = translate("pull_request_comment", payload);

        assertEquals(WebhookEventKind.PR_REVIEW_SUBMITTED, event.kind());
        assertEquals("looks good", event.payload().getReview().getContent());
    }

    @Test
    void approvedReview_isPrReviewSubmitted() {
        Map<String, Object> payload = buildPrEventPayload("reviewed");
        payload.put("review", new HashMap<>(Map.of("type", "pull_request_review_approved", "content", "LGTM")));

        assertEquals(WebhookEventKind.PR_REVIEW_SUBMITTED, translate("pull_request_approved", payload).kind());
    }

    // ---- Event header ----

    @Test
    void missingEventHeader_isIgnored() {
        WebhookEvent event = translator.translate(bot, Map.of(), buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void blankEventHeader_isIgnored() {
        WebhookEvent event = translator.translate(bot, Map.of(GiteaWebhookTranslator.EVENT_HEADER, " "),
                buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void forgejoEventHeader_isUsedWhenTheGiteaHeaderIsMissing() {
        WebhookEvent event = translator.translate(bot,
                Map.of(GiteaWebhookTranslator.FORGEJO_EVENT_HEADER, "pull_request"), buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
    }

    @Test
    void giteaEventHeader_winsOverTheForgejoHeader() {
        WebhookEvent event = translator.translate(bot, Map.of(
                        GiteaWebhookTranslator.EVENT_HEADER, "pull_request",
                        GiteaWebhookTranslator.FORGEJO_EVENT_HEADER, "issues"),
                buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
    }

    @Test
    void unhandledEventType_isIgnored() {
        WebhookEvent event = translator.translate(bot, headers("push"), buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void pullRequestActionUnderTheIssuesHeader_isNotTakenForAPullRequest() {
        // The header decides, not the payload's shape: an "issues" event is never a PR event.
        WebhookEvent event = translator.translate(bot, headers("issues"), buildPrEventPayload("opened"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    private static Map<String, String> headers(String eventType) {
        return Map.of(GiteaWebhookTranslator.EVENT_HEADER, eventType);
    }

    // ---- Payload builder helpers ----

    /**
     * Builds a raw webhook payload for a comment on a PR discussion thread.
     * Both {@code issue} and top-level {@code pull_request} are present.
     */
    private Map<String, Object> buildPrCommentPayload(String commentBody) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMap(140L, true));         // issue with pull_request link
        payload.put("pull_request", pullRequestMap(140L));  // top-level pull_request present
        payload.put("comment", commentMap(1055L, commentBody, null));
        return payload;
    }

    /**
     * Builds a raw webhook payload for a comment on a regular issue (no PR).
     * Only {@code issue} is present; {@code pull_request} at the top level is absent.
     */
    private Map<String, Object> buildIssueCommentPayload(String commentBody) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMap(42L, false));         // plain issue, no pull_request link
        // no top-level pull_request key
        payload.put("comment", commentMap(999L, commentBody, null));
        return payload;
    }

    /**
     * Builds a raw webhook payload for an inline review comment (has a file path).
     */
    private Map<String, Object> buildInlineCommentPayload(String commentBody) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMap(140L));
        payload.put("comment", commentMap(1060L, commentBody, "src/main/Foo.java"));
        return payload;
    }

    /**
     * Builds a raw webhook payload for a PR lifecycle event (opened, closed, etc.).
     */
    private Map<String, Object> buildPrEventPayload(String action) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMap(140L));
        return payload;
    }

    /**
     * Builds a raw webhook payload for an issue-created event.
     */
    private Map<String, Object> buildIssueCreatedPayload(String action) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMap(42L, false));
        // no top-level pull_request → plain issue creation
        return payload;
    }

    /**
     * Builds a raw webhook payload for an issue-assigned event.
     */
    private Map<String, Object> buildIssueAssignedPayload(String assigneeLogin) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssignee(42L, assigneeLogin));
        // no top-level pull_request → plain issue assignment
        return payload;
    }

    private Map<String, Object> buildPrAssignedPayload(String assigneeLogin) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMapWithAssignee(140L, assigneeLogin));
        return payload;
    }

    // ---- Domain object helpers ----

    private Map<String, Object> ownerMap(String login) {
        Map<String, Object> m = new HashMap<>();
        m.put("login", login);
        m.put("username", login);
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

    private Map<String, Object> pullRequestMap(long number) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 80);
        m.put("number", number);
        m.put("title", "Some PR");
        m.put("body", "");
        m.put("state", "open");
        m.put("merged", false);
        m.put("diff_url", "http://localhost:3000/Test/my-repo/pulls/" + number + ".diff");
        m.put("user", ownerMap("tom"));
        m.put("requested_reviewers", java.util.List.of(ownerMap(BOT_USERNAME)));
        Map<String, Object> head = new HashMap<>();
        head.put("ref", "feature/branch");
        head.put("sha", "abc123");
        m.put("head", head);
        Map<String, Object> base = new HashMap<>();
        base.put("ref", "main");
        base.put("sha", "def456");
        m.put("base", base);
        return m;
    }

    /** Issue map where {@code isPr} controls whether a {@code pull_request} sub-object is included. */
    private Map<String, Object> issueMap(long number, boolean isPr) {
        Map<String, Object> m = new HashMap<>();
        m.put("number", number);
        m.put("title", "Some issue");
        m.put("body", "");
        m.put("user", ownerMap("tom"));
        if (isPr) {
            Map<String, Object> prLink = new HashMap<>();
            prLink.put("merged", false);
            m.put("pull_request", prLink);
        }
        return m;
    }

    private Map<String, Object> issueMapWithAssignee(long number, String assigneeLogin) {
        Map<String, Object> m = issueMap(number, false);
        m.put("assignee", ownerMap(assigneeLogin));
        return m;
    }

    private Map<String, Object> pullRequestMapWithAssignee(long number, String assigneeLogin) {
        Map<String, Object> m = pullRequestMap(number);
        m.put("assignee", ownerMap(assigneeLogin));
        m.put("assignees", java.util.List.of(ownerMap(assigneeLogin)));
        return m;
    }

    private Map<String, Object> commentMap(long id, String body, String path) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("body", body);
        m.put("user", ownerMap("tom"));
        if (path != null) {
            m.put("path", path);
        }
        return m;
    }
}
