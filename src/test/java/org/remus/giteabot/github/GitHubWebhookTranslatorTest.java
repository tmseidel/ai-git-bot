package org.remus.giteabot.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.webhook.TranslatingWebhookHandler;
import org.remus.giteabot.webhook.WebhookEventDispatcher;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests how GitHub webhooks are classified by {@link GitHubWebhookTranslator} and, through
 * the real {@link WebhookEventDispatcher}, which {@link BotWebhookService} handler they reach.
 */
@ExtendWith(MockitoExtension.class)
class GitHubWebhookTranslatorTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GitHubWebhookTranslator translator;
    private TranslatingWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new GitHubWebhookTranslator();
        handler = new TranslatingWebhookHandler(translator, GitHubWebhookTranslator.EVENT_HEADER, botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setUsername("ai_bot");
        bot.setGitIntegration(gitIntegration);
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

        WebhookPayload translated = translator.translatePayload("issues", payload);

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

    @Test
    void issueAssignedToBotAsSecondAssignee_triggersAgent() {
        // issue.assignee stays the first assignee; the top-level assignee names the new one.
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "someone_else", null));
        payload.put("assignee", ownerMap("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueAssigned(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void issueAssignedToSomeoneElseWhileBotIsFirstAssignee_isIgnored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "ai_bot", null));
        payload.put("assignee", ownerMap("someone_else"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
    }

    @Test
    void reviewCommentWithoutPullRequest_isIgnored() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 7L);
        comment.put("body", "@ai_bot why?");
        comment.put("path", "src/Foo.java");
        comment.put("user", ownerMap("tom"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("comment", comment);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request_review_comment", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
    }

    @Test
    void issueOpenedWithRunOnIssueCreation_triggersAgent() {
        bot.setRunOnIssueCreation(true);
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "opened");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, null, null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueCreated(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
    }

    @Test
    void issueReopenedWithRunOnIssueCreation_triggersAgent() {
        bot.setRunOnIssueCreation(true);
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "reopened");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, null, null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueCreated(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void issueOpenedWithoutRunOnIssueCreation_isIgnored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "opened");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, null, null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueCreated(any(), any());
    }

    @Test
    void pullRequestOpenedWithBotReviewer_triggersReview() {
        Map<String, Object> payload = prPayload("opened", List.of(ownerMap("ai_bot")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestOpenedWithoutBotReviewer_isIgnored() {
        Map<String, Object> payload = prPayload("opened", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestOpenedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        Map<String, Object> payload = prPayload("opened", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestSynchronizedWithRunOnPrUpdate_triggersReview() {
        bot.setRunOnPrUpdate(true);
        Map<String, Object> payload = prPayload("synchronize", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestSynchronizedWithoutRunOnPrUpdate_isIgnored() {
        Map<String, Object> payload = prPayload("synchronize", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestSynchronize_isIgnoredEvenWhenBotReviewer() {
        // Reviewer membership does NOT bypass the update gate — only runOnPrUpdate controls sync.
        Map<String, Object> payload = prPayload("synchronize", List.of(ownerMap("ai_bot")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestSynchronizedWithRunOnPrUpdate_triggersReviewEvenWithoutBotReviewer() {
        bot.setRunOnPrUpdate(true);
        Map<String, Object> payload = prPayload("synchronize", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    // ---- reopened ----

    @Test
    void pullRequestReopenedWithBotReviewer_triggersReview() {
        Map<String, Object> payload = prPayload("reopened", List.of(ownerMap("ai_bot")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestReopenedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        Map<String, Object> payload = prPayload("reopened", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestReopenedWithoutBotReviewerOrRunOnPrCreation_isIgnored() {
        Map<String, Object> payload = prPayload("reopened", List.of(ownerMap("human")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestReviewRequestedForBot_triggersReview() {
        Map<String, Object> payload = prPayload("review_requested", List.of(ownerMap("ai_bot")));
        payload.put("requested_reviewer", ownerMap("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    // ---- assigned ----

    @Test
    void pullRequestAssignedToBot_triggersReview() {
        Map<String, Object> payload = prPayload("assigned", List.of());
        payload.put("assignee", ownerMap("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestAssignedToSomeoneElse_whileTheBotIsAssigned_isIgnored() {
        Map<String, Object> payload = prPayload("assigned", List.of());
        pullRequest(payload).put("assignees", List.of(ownerMap("ai_bot"), ownerMap("human")));
        payload.put("assignee", ownerMap("human"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestOpenedWithBotAssignee_doesNotTriggerAReview() {
        // Only assigning the bot to an existing pull request counts, as on develop.
        Map<String, Object> payload = prPayload("opened", List.of());
        pullRequest(payload).put("assignees", List.of(ownerMap("ai_bot")));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    // ---- Comments ----

    @Test
    void prConversationComment_routesToHandlePrComment() {
        Map<String, Object> issue = new HashMap<>();
        issue.put("number", 140);
        issue.put("title", "Some PR");
        issue.put("user", ownerMap("tom"));
        issue.put("pull_request", new HashMap<>(Map.of("url", "https://api.github.com/repos/Test/my-repo/pulls/140")));
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 1055);
        comment.put("body", "@ai_bot please fix");
        comment.put("user", ownerMap("tom"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issue);
        payload.put("comment", comment);

        ResponseEntity<String> response = handler.handleWebhook(bot, "issue_comment", payload);

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
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
