package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link GitLabWebhookTranslator} turns GitLab webhooks into {@link WebhookEvent}s.
 * What the bot then does with each kind is covered by {@code WebhookEventDispatcherTest}.
 */
class GitLabWebhookTranslatorTest {

    private GitLabWebhookTranslator translator;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new GitLabWebhookTranslator();
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setUsername("ai_bot");
        bot.setGitIntegration(gitIntegration);
    }

    private WebhookEvent translate(String eventType, Map<String, Object> payload) {
        return translator.translate(bot, Map.of(GitLabWebhookTranslator.EVENT_HEADER, eventType), payload);
    }

    // ---- Merge request lifecycle ----

    @Test
    void mergeRequestOpenedWithBotReviewer_isPrOpenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("open", List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertTrue(event.botIsReviewer());
        assertFalse(event.fromBot());
    }

    @Test
    void mergeRequestOpenedWithoutBotReviewer_isPrOpenedWithoutTheBotAsReviewer() {
        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("open", List.of(user("human")), null));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @Test
    void mergeRequestOpenedWithBotAssignee_isNotAnAssignment() {
        // Only assigning the bot to an existing merge request counts.
        Map<String, Object> payload = mergeRequestPayload("open", List.of(user("human")), null);
        payload.put("assignees", List.of(user("ai_bot")));

        assertEquals(WebhookEventKind.PR_OPENED, translate("Merge Request Hook", payload).kind());
    }

    @Test
    void mergeRequestReopenedWithBotReviewer_isPrReopenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("reopen", List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.PR_REOPENED, event.kind());
        assertTrue(event.botIsReviewer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "merge"})
    void mergeRequestClosedOrMerged_isPrClosed(String action) {
        assertEquals(WebhookEventKind.PR_CLOSED,
                translate("Merge Request Hook", mergeRequestPayload(action, List.of(), null)).kind());
    }

    @Test
    void mergeRequestUpdateWithoutPushOrBotChange_isIgnored() {
        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("update", List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void mergeRequestTitleEdit_isIgnored() {
        // An update without oldrev is an edit (title, labels, ...), not a push.
        Map<String, Object> payload = mergeRequestPayload("update", List.of(user("human")),
                Map.of("title", Map.of("previous", "Old", "current", "New")));

        assertEquals(WebhookEventKind.IGNORED, translate("Merge Request Hook", payload).kind());
    }

    @Test
    void mergeRequestPush_isPrSynchronized() {
        Map<String, Object> payload = mergeRequestPayload("update", List.of(user("human")), null);
        attrs(payload).put("oldrev", "0123abcd");

        assertEquals(WebhookEventKind.PR_SYNCHRONIZED, translate("Merge Request Hook", payload).kind());
    }

    @Test
    void mergeRequestUpdateWhenBotReviewerAdded_isPrReviewerAdded() {
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "previous", List.of(user("human")),
                "current", List.of(user("human"), user("ai_bot"))));

        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human"), user("ai_bot")), changes));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, event.kind());
    }

    @Test
    void mergeRequestUpdateWhenOtherReviewerAddedButBotAlreadyReviewer_isIgnored() {
        // The bot was already a reviewer; adding someone else must not re-trigger it.
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "previous", List.of(user("ai_bot"), user("human1")),
                "current", List.of(user("ai_bot"), user("human1"), user("human2"))));

        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("update", List.of(user("ai_bot"), user("human1"), user("human2")), changes));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void mergeRequestUpdateWhenBotAssigned_isPrAssigned() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(user("human")),
                "current", List.of(user("human"), user("ai_bot"))));

        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), changes));

        assertEquals(WebhookEventKind.PR_ASSIGNED, event.kind());
    }

    @Test
    void mergeRequestUpdateWhenOtherAssigneeAddedButBotAlreadyAssigned_isIgnored() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(user("ai_bot")),
                "current", List.of(user("ai_bot"), user("human"))));

        WebhookEvent event = translate("Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), changes));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void mergeRequestApprovedByTheBotAsReviewer_isAReviewRequestNotFromTheBot() {
        // GitLab's re-request of a review shows up as the bot's own approval.
        Map<String, Object> payload = mergeRequestPayload("approved", List.of(user("ai_bot")), null);
        payload.put("user", user("ai_bot"));

        WebhookEvent event = translate("Merge Request Hook", payload);

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, event.kind());
        assertFalse(event.fromBot());
        assertTrue(event.botIsReviewer());
    }

    @Test
    void mergeRequestApprovedByTheBotAfterItsConfiguredApprove_isIgnored() {
        bot.getGitIntegration().setPostReviewAction(PostReviewAction.APPROVE);
        Map<String, Object> payload = mergeRequestPayload("approved", List.of(user("ai_bot")), null);
        payload.put("user", user("ai_bot"));

        assertEquals(WebhookEventKind.IGNORED, translate("Merge Request Hook", payload).kind());
    }

    @Test
    void mergeRequestApprovedByAHuman_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED, translate("Merge Request Hook",
                mergeRequestPayload("approved", List.of(user("ai_bot")), null)).kind());
    }

    // ---- Notes ----

    @Test
    void mergeRequestNoteWithMention_isPrCommentMentioningTheBot() {
        WebhookEvent event = translate("Note Hook", notePayload("@ai_bot please review this again"));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals("@ai_bot please review this again", event.payload().getComment().getBody());
    }

    @Test
    void mergeRequestNoteOnADiffLine_isPrInlineCommentWithItsAnchor() {
        Map<String, Object> payload = notePayload("@ai_bot why?");
        attrs(payload).put("position", Map.of("new_path", "src/Foo.java", "new_line", 12));

        WebhookEvent event = translate("Note Hook", payload);

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertEquals("src/Foo.java", event.payload().getComment().getPath());
        assertEquals(12, event.payload().getComment().getLine());
    }

    @Test
    void issueNote_isIssueComment() {
        Map<String, Object> payload = notePayload("@ai_bot any idea?");
        attrs(payload).put("noteable_type", "Issue");
        payload.remove("merge_request");
        payload.put("issue", Map.of("iid", 3, "title", "Bug", "description", "Broken"));

        assertEquals(WebhookEventKind.ISSUE_COMMENT, translate("Note Hook", payload).kind());
    }

    @Test
    void noteOnACommit_isIgnored() {
        Map<String, Object> payload = notePayload("@ai_bot hi");
        attrs(payload).put("noteable_type", "Commit");

        assertEquals(WebhookEventKind.IGNORED, translate("Note Hook", payload).kind());
    }

    @Test
    void translateNotePayload_usesTopLevelUser_whenGitLabOmitsNestedAuthorInfo() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", 55);
        attrs.put("note", "@ai_bot repeat the code-review");
        attrs.put("noteable_type", "MergeRequest");

        Map<String, Object> payload = basePayload(attrs);
        payload.put("user", Map.of("id", 1, "username", "root", "name", "Administrator"));
        payload.put("merge_request", Map.of(
                "id", 100,
                "iid", 1,
                "title", "Test MR",
                "description", "Some changes",
                "author_id", 1));

        WebhookPayload translated = translator.translateNotePayload(payload, attrs);

        assertEquals("root", translated.getComment().getUser().getLogin());
        assertEquals("root", translated.getPullRequest().getUser().getLogin());
    }

    // ---- Issues ----

    @Test
    void issueOpen_isIssueOpened() {
        assertEquals(WebhookEventKind.ISSUE_OPENED, translate("Issue Hook", issuePayload("open")).kind());
    }

    @Test
    void issueReopen_isIssueReopened() {
        assertEquals(WebhookEventKind.ISSUE_REOPENED, translate("Issue Hook", issuePayload("reopen")).kind());
    }

    @Test
    void issueUpdateWhenBotAssigned_isIssueAssigned() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(),
                "current", List.of(user("ai_bot"))));

        assertEquals(WebhookEventKind.ISSUE_ASSIGNED, translate("Issue Hook", issuePayload("update", changes)).kind());
    }

    @Test
    void issueUpdateWithoutTheBotBeingAssigned_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED, translate("Issue Hook", issuePayload("update")).kind());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("object_attributes");
    }

    private Map<String, Object> mergeRequestPayload(String action, List<Map<String, Object>> reviewers,
                                                    Map<String, Object> changes) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("action", action);
        attrs.put("id", 100);
        attrs.put("iid", 1);
        attrs.put("title", "Test MR");
        attrs.put("description", "Some changes");
        attrs.put("state", "opened");
        attrs.put("source_branch", "feature");
        attrs.put("target_branch", "main");

        Map<String, Object> payload = basePayload(attrs);
        payload.put("reviewers", reviewers);
        if (changes != null) {
            payload.put("changes", changes);
        }
        return payload;
    }

    private Map<String, Object> notePayload(String note) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", 55);
        attrs.put("note", note);
        attrs.put("noteable_type", "MergeRequest");
        attrs.put("author", user("developer"));

        Map<String, Object> payload = basePayload(attrs);
        payload.put("merge_request", Map.of(
                "id", 100,
                "iid", 1,
                "title", "Test MR",
                "description", "Some changes",
                "author", user("developer")));
        return payload;
    }

    private Map<String, Object> issuePayload(String action) {
        return issuePayload(action, null);
    }

    private Map<String, Object> issuePayload(String action, Map<String, Object> changes) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("action", action);
        attrs.put("iid", 1);
        attrs.put("title", "Test Issue");
        attrs.put("description", "Some issue body");

        Map<String, Object> payload = basePayload(attrs);
        if (changes != null) {
            payload.put("changes", changes);
        }
        return payload;
    }

    private Map<String, Object> basePayload(Map<String, Object> attrs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("object_attributes", attrs);
        payload.put("project", Map.of(
                "id", 1,
                "name", "repo",
                "path_with_namespace", "owner/repo",
                "namespace", Map.of("path", "owner")));
        payload.put("user", user("developer"));
        return payload;
    }

    private Map<String, Object> user(String username) {
        return Map.of("username", username, "name", username);
    }
}
