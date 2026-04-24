package org.remus.giteabot.gitea;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests the routing logic of {@link GiteaWebhookHandler}:
 * PR comments → handlePrComment, issue comments → handleIssueComment, etc.
 */
@ExtendWith(MockitoExtension.class)
class GiteaWebhookHandlerTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GiteaWebhookHandler handler;
    private Bot bot;

    private static final String BOT_USERNAME = "claude_bot";
    private static final String BOT_ALIAS = "@claude_bot";

    @BeforeEach
    void setUp() {
        handler = new GiteaWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setUsername(BOT_USERNAME);

        // Default stubs used by most tests – declared lenient because a few tests override
        // isBotUser to return true and never reach getBotAlias.
        lenient().when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(false);
        lenient().when(botWebhookService.getBotAlias(bot)).thenReturn(BOT_ALIAS);
    }

    // ---- PR comment routing ----

    @Test
    void prCommentWithBotMention_routesToHandlePrComment() {
        Map<String, Object> payload = buildPrCommentPayload(BOT_ALIAS + " please fix this");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleIssueComment(any(), any());
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void prCommentWithoutBotMention_isIgnored() {
        Map<String, Object> payload = buildPrCommentPayload("just a regular comment");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void prCommentWithBotMention_nonCreatedAction_isIgnored() {
        Map<String, Object> payload = buildPrCommentPayload(BOT_ALIAS + " do something");
        payload.put("action", "edited");  // not "created"

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    // ---- Issue comment routing ----

    @Test
    void issueCommentWithBotMention_routesToHandleIssueComment() {
        Map<String, Object> payload = buildIssueCommentPayload(BOT_ALIAS + " implement this feature");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("issue comment received", response.getBody());
        verify(botWebhookService).handleIssueComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handlePrComment(any(), any());
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void issueCommentWithoutBotMention_isIgnored() {
        Map<String, Object> payload = buildIssueCommentPayload("just a note");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueComment(any(), any());
    }

    // ---- Bot's own events are ignored ----

    @Test
    void ownBotComment_isIgnored() {
        when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(true);
        Map<String, Object> payload = buildPrCommentPayload(BOT_ALIAS + " something");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
        verify(botWebhookService, never()).handleIssueComment(any(), any());
    }

    // ---- Inline review comment routing ----

    @Test
    void inlineCommentWithBotMention_routesToHandleInlineComment() {
        Map<String, Object> payload = buildInlineCommentPayload(BOT_ALIAS + " explain this");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("inline comment response triggered", response.getBody());
        verify(botWebhookService).handleInlineComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void inlineCommentWithoutBotMention_isIgnored() {
        Map<String, Object> payload = buildInlineCommentPayload("just a note on the code");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
    }

    // ---- PR open/sync routing ----

    @Test
    void prOpenedEvent_routesToReviewPullRequest() {
        Map<String, Object> payload = buildPrEventPayload("opened");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void prSynchronizedEvent_routesToReviewPullRequest() {
        Map<String, Object> payload = buildPrEventPayload("synchronized");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void prClosedEvent_routesToHandlePrClosed() {
        Map<String, Object> payload = buildPrEventPayload("closed");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("session closed", response.getBody());
        verify(botWebhookService).handlePrClosed(eq(bot), any(WebhookPayload.class));
    }

    // ---- Issue assignment routing ----

    @Test
    void issueAssignedToBotEvent_routesToHandleIssueAssigned() {
        Map<String, Object> payload = buildIssueAssignedPayload(BOT_USERNAME);

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueAssigned(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void issueAssignedToDifferentUser_isIgnored() {
        Map<String, Object> payload = buildIssueAssignedPayload("someone_else");

        ResponseEntity<String> response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
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


