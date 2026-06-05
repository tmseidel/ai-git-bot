package org.remus.giteabot.bitbucket;

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
class BitbucketWebhookHandlerTest {

    @Mock
    private BotWebhookService botWebhookService;

    private BitbucketWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new BitbucketWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setUsername("ai_bot");

        lenient().when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(false);
        lenient().when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(true);
    }

    @Test
    void pullRequestCreatedWithBotReviewer_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestCreatedWithoutBotReviewer_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestCreatedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestUpdatedWithRunOnPrCreation_isStillIgnored() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestUpdatedWithoutReviewerChange_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestUpdatedWhenBotReviewerAdded_triggersReview() {
        Map<String, Object> changes = Map.of("reviewers", Map.of(
                "old", List.of(user("human")),
                "new", List.of(user("human"), user("ai_bot"))));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("human"), user("ai_bot")), changes));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void ownerReviewAgainComment_triggersReview() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq("@ai_bot"))).thenReturn(true);
        lenient().when(botWebhookService.isReviewAgainRequestFromPullRequestAuthor(any(WebhookPayload.class), eq("@ai_bot")))
                .thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@ai_bot - Review the Pull-Request again", null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void nonOwnerReviewAgainComment_isIgnored() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq("@ai_bot"))).thenReturn(true);
        lenient().when(botWebhookService.isReviewAgainRequestFromPullRequestAuthor(any(WebhookPayload.class), eq("@ai_bot")))
                .thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@ai_bot - Review the Pull-Request again", null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void regularOwnerMention_routesToBotCommand() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq("@ai_bot"))).thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@ai_bot please explain this", null));

        assertEquals("command received", response.getBody());
        verify(botWebhookService).handleBotCommand(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    private Map<String, Object> pullRequestPayload(List<Map<String, Object>> reviewers, Map<String, Object> changes) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("pullrequest", pullRequest(reviewers));
        raw.put("actor", user("developer"));
        raw.put("repository", repository());
        if (changes != null) {
            raw.put("changes", changes);
        }
        return raw;
    }

    private Map<String, Object> commentPayload(String body, Map<String, Object> inline) {
        Map<String, Object> raw = pullRequestPayload(List.of(user("ai_bot")), null);
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 55);
        comment.put("content", Map.of("raw", body));
        comment.put("user", user("developer"));
        if (inline != null) {
            comment.put("inline", inline);
        }
        raw.put("comment", comment);
        return raw;
    }

    private Map<String, Object> pullRequest(List<Map<String, Object>> reviewers) {
        Map<String, Object> pullrequest = new HashMap<>();
        pullrequest.put("id", 42);
        pullrequest.put("title", "Add feature");
        pullrequest.put("description", "Feature description");
        pullrequest.put("state", "OPEN");
        pullrequest.put("author", user("developer"));
        pullrequest.put("reviewers", reviewers);
        pullrequest.put("source", Map.of("branch", Map.of("name", "feature"), "commit", Map.of("hash", "abc")));
        pullrequest.put("destination", Map.of("branch", Map.of("name", "main"), "commit", Map.of("hash", "def")));
        return pullrequest;
    }

    private Map<String, Object> repository() {
        return Map.of(
                "name", "myrepo",
                "full_name", "workspace/myrepo",
                "uuid", "{12345}",
                "owner", user("workspace"));
    }

    private Map<String, Object> user(String username) {
        return Map.of("nickname", username, "display_name", username);
    }
}
