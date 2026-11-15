package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.webhook.TranslatingWebhookHandler;
import org.remus.giteabot.webhook.WebhookEventDispatcher;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests how GitLab webhooks are classified by {@link GitLabWebhookTranslator} and, through
 * the real {@link WebhookEventDispatcher}, which {@link BotWebhookService} handler they reach.
 */
@ExtendWith(MockitoExtension.class)
class GitLabWebhookTranslatorTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GitLabWebhookTranslator translator;
    private TranslatingWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new GitLabWebhookTranslator();
        handler = new TranslatingWebhookHandler(translator, GitLabWebhookTranslator.EVENT_HEADER, botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setUsername("ai_bot");
        bot.setGitIntegration(gitIntegration);

    }

    @Test
    void mergeRequestOpenedWithBotReviewer_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void mergeRequestOpenedWithoutBotReviewer_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestOpenedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("human")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void mergeRequestUpdateWithRunOnPrCreation_isStillIgnored() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestUpdateWithoutReviewerChange_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestUpdateWhenBotReviewerAdded_triggersReview() {
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "previous", List.of(user("human")),
                "current", List.of(user("human"), user("ai_bot"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human"), user("ai_bot")), changes));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void mergeRequestUpdateWhenOtherReviewerAddedButBotAlreadyAssigned_doesNotRetriggerReview() {
        // Bot was already a reviewer; a different reviewer is now added. The bot should NOT re-review.
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "previous", List.of(user("ai_bot"), user("human1")),
                "current", List.of(user("ai_bot"), user("human1"), user("human2"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("ai_bot"), user("human1"), user("human2")), changes));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestApprovedFromBotReviewer_triggersReviewRequest() {
        Map<String, Object> payload = mergeRequestPayload("approved", List.of(user("ai_bot")), null);
        payload.put("user", user("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void mergeRequestApprovedFromBotReviewerAfterConfiguredApprove_isIgnored() {
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setUsername("ai_bot");
        gitIntegration.setPostReviewAction(PostReviewAction.APPROVE);
        bot.setGitIntegration(gitIntegration);
        Map<String, Object> payload = mergeRequestPayload("approved", List.of(user("ai_bot")), null);
        payload.put("user", user("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void reviewAgainNote_triggersReview() {

        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
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

    @Test
    void reviewAgainNote_onAMergeRequestWithAnAgentSession_continuesTheSession() {
        lenient().when(botWebhookService.hasAgentSession(any(), any(WebhookPayload.class))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void issueOpenedWithRunOnIssueCreation_triggersAgent() {
        bot.setRunOnIssueCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Issue Hook",
                issuePayload("open"));

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueCreated(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
    }

    @Test
    void issueReopenedWithRunOnIssueCreation_triggersAgent() {
        bot.setRunOnIssueCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Issue Hook",
                issuePayload("reopen"));

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueCreated(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void issueOpenedWithoutRunOnIssueCreation_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Issue Hook",
                issuePayload("open"));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueCreated(any(), any());
    }

    @Test
    void issueUpdateWithAssigneeChange_stillRoutesToHandleIssueAssigned() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(),
                "current", List.of(user("ai_bot"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Issue Hook",
                issuePayload("update", changes));

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueAssigned(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleIssueCreated(any(), any());
    }

    @Test
    void issueOpenIsClassifiedAsIssueOpened() {
        WebhookEvent event = translator.translate(bot,
                Map.of(GitLabWebhookTranslator.EVENT_HEADER, "Issue Hook"), issuePayload("open"));

        assertEquals(WebhookEventKind.ISSUE_OPENED, event.kind());
    }

    @Test
    void issueReopenIsClassifiedAsIssueReopened() {
        WebhookEvent event = translator.translate(bot,
                Map.of(GitLabWebhookTranslator.EVENT_HEADER, "Issue Hook"), issuePayload("reopen"));

        assertEquals(WebhookEventKind.ISSUE_REOPENED, event.kind());
    }

    @Test
    void mergeRequestOpenedWithBotAssignee_doesNotTriggerAReview() {
        // Only assigning the bot to an existing merge request counts.
        Map<String, Object> payload = mergeRequestPayload("open", List.of(user("human")), null);
        payload.put("assignees", List.of(user("ai_bot")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestUpdateWhenBotAssigned_triggersReview() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(user("human")),
                "current", List.of(user("human"), user("ai_bot"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), changes));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class),
                eq(WebhookEventKind.PR_ASSIGNED));
    }

    @Test
    void mergeRequestUpdateWhenOtherAssigneeAddedButBotAlreadyAssigned_isIgnored() {
        Map<String, Object> changes = Map.of("assignees", Map.of(
                "previous", List.of(user("ai_bot")),
                "current", List.of(user("ai_bot"), user("human"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), changes));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestPush_withRunOnPrUpdate_triggersReview() {
        bot.setRunOnPrUpdate(true);
        Map<String, Object> payload = mergeRequestPayload("update", List.of(user("human")), null);
        attrs(payload).put("oldrev", "0123abcd");

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class),
                eq(WebhookEventKind.PR_SYNCHRONIZED));
    }

    @Test
    void mergeRequestTitleEdit_withRunOnPrUpdate_isIgnored() {
        // An update without oldrev is an edit (title, labels, ...), not a push.
        bot.setRunOnPrUpdate(true);
        Map<String, Object> payload = mergeRequestPayload("update", List.of(user("human")),
                Map.of("title", Map.of("previous", "Old", "current", "New")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void mergeRequestReopenedWithBotReviewer_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("reopen", List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void mergeRequestNoteWithMention_routesToHandlePrComment() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook", notePayload("@ai_bot please explain"));

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
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
