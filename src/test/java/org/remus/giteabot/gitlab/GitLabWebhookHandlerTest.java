package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
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

    @BeforeEach
    void setUp() {
        handler = new GitLabWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setUsername("ai_bot");

        lenient().when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(false);
        lenient().when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(true);
    }

    @Test
    void mergeRequestOpenedWithBotReviewer_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Merge Request Hook",
                mergeRequestPayload("open", List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
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
    void ownerReviewAgainNote_triggersFallbackReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void nonOwnerReviewAgainNote_isIgnored() {
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "Note Hook",
                notePayload("@ai_bot please review this again"));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
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
