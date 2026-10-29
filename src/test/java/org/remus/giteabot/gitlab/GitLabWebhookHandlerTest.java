package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.model.GitUser;
import org.remus.giteabot.repository.PostReviewAction;
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

@ExtendWith(MockitoExtension.class)
class GitLabWebhookHandlerTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GitLabWebhookHandler handler;
    private Bot bot;

    private static final GitUser BOT_USER = new GitUser("17", "ai_bot", null, null);

    @BeforeEach
    void setUp() {
        handler = new GitLabWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        bot.setGitIntegration(gitIntegration);

        lenient().when(botWebhookService.resolveGitUser(bot)).thenReturn(BOT_USER);
        lenient().when(botWebhookService.isBotUser(eq(BOT_USER), any(WebhookPayload.class))).thenReturn(false);
        lenient().when(botWebhookService.getBotAlias("ai_bot")).thenReturn("@ai_bot");
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(true);
    }

    @Test
    void translateNotePayload_carriesTheSenderAndAuthorIds() {
        Map<String, Object> payload = Map.of("user", Map.of("username", "tom", "id", 7));
        Map<String, Object> noteAttrs = Map.of("id", 1, "note", "hi",
                "author", Map.of("username", "tom", "id", 7));

        WebhookPayload translated = handler.translateNotePayload(payload, noteAttrs);

        assertEquals("7", translated.getSender().getId());
        assertEquals("7", translated.getComment().getUser().getId());
    }

    @Test
    void mergeRequestOpenedWithBotReviewer_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void mergeRequestOpenedWithoutBotReviewer_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void mergeRequestOpenedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("human")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void mergeRequestUpdateWithRunOnPrCreation_isStillIgnored() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void mergeRequestUpdateWithoutReviewerChange_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void mergeRequestUpdateWhenBotReviewerAdded_triggersReview() {
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "previous", List.of(user("human")),
                "current", List.of(user("human"), user("ai_bot"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("update", List.of(user("human"), user("ai_bot")), changes));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
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
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void mergeRequestApprovedFromBotReviewer_triggersReviewRequest() {
        lenient().when(botWebhookService.isBotUser(eq(BOT_USER), any(WebhookPayload.class))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("approved", List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void mergeRequestApprovedFromBotReviewerAfterConfiguredApprove_isIgnored() {
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setPostReviewAction(PostReviewAction.APPROVE);
        bot.setGitIntegration(gitIntegration);
        lenient().when(botWebhookService.isBotUser(eq(BOT_USER), any(WebhookPayload.class))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("approved", List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void ownerReviewAgainNote_triggersFallbackReview() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq("@ai_bot"))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleBotCommand(any(), any());
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

        WebhookPayload translated = handler.translateNotePayload(payload, attrs);

        assertEquals("root", translated.getComment().getUser().getLogin());
        assertEquals("root", translated.getPullRequest().getUser().getLogin());
        assertEquals("root", translated.getIssue().getUser().getLogin());
    }

    @Test
    void nonOwnerReviewAgainNote_isIgnored() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq("@ai_bot"))).thenReturn(true);
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
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
    void issueOpenedTranslatesActionToOpened() {
        bot.setRunOnIssueCreation(true);

        handler.handleWebhook(bot, "Issue Hook", issuePayload("open"));

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleIssueCreated(eq(bot), captor.capture());
        assertEquals("opened", captor.getValue().getAction());
    }

    @Test
    void issueReopenedTranslatesActionToReopened() {
        bot.setRunOnIssueCreation(true);

        handler.handleWebhook(bot, "Issue Hook", issuePayload("reopen"));

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleIssueCreated(eq(bot), captor.capture());
        assertEquals("reopened", captor.getValue().getAction());
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
