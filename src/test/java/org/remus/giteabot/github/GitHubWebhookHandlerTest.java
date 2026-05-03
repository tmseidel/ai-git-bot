package org.remus.giteabot.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookHandlerTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GitHubWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new GitHubWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setUsername("ai_bot");

        lenient().when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(false);
        lenient().when(botWebhookService.shouldTriggerCodeReview(eq(bot), any(WebhookPayload.class))).thenReturn(true);
    }

    @Test
    void pullRequestOpened_isIgnored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "opened");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMap(42L, "Regular PR"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestSynchronized_whenBotReviewer_triggersReview() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "synchronize");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMap(42L, "Regular PR"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestReviewRequested_whenSkipOrReviewerCheckFails_isIgnored() {
        when(botWebhookService.shouldTriggerCodeReview(eq(bot), any(WebhookPayload.class))).thenReturn(false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "review_requested");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("requested_reviewer", ownerMap("ai_bot"));
        payload.put("pull_request", pullRequestMap(42L, "Regular PR"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void issueAssignedPayload_withCustomIssueRef_propagatesCompatibilityRefToWebhookPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "ai_bot", "release/1.2"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("agent triggered", response.getBody());
        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleIssueAssigned(eq(bot), captor.capture());
        assertEquals("release/1.2", captor.getValue().getIssue().getRef());
    }

    @Test
    void translatePayload_issueWithoutRef_keepsRefNull() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "ai_bot", null));

        WebhookPayload translated = handler.translatePayload("issues", payload);

        assertEquals("assigned", translated.getAction());
        assertNull(translated.getIssue().getRef());
    }

    @Test
    void issueAssignedPayload_toDifferentAssignee_isIgnored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "someone_else", null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
    }

    private Map<String, Object> ownerMap(String login) {
        Map<String, Object> m = new HashMap<>();
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

    private Map<String, Object> issueMapWithAssigneeAndRef(long number, String assigneeLogin, String ref) {
        Map<String, Object> m = new HashMap<>();
        m.put("number", number);
        m.put("title", "Some issue");
        m.put("body", "");
        m.put("assignee", ownerMap(assigneeLogin));
        m.put("ref", ref);
        return m;
    }

    private Map<String, Object> pullRequestMap(long number, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 80);
        m.put("number", number);
        m.put("title", title);
        m.put("body", "");
        m.put("state", "open");
        m.put("requested_reviewers", java.util.List.of(ownerMap("ai_bot")));
        return m;
    }
}
