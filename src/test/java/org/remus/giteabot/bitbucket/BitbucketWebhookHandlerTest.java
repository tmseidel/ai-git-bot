package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BitbucketWebhookHandlerTest {

    private static final String READABLE_MENTION = "@AI_Bot";

    @Mock
    private BotWebhookService botWebhookService;
    @Mock
    private GiteaClientFactory clientFactory;
    @Mock
    private BitbucketApiClient bitbucketClient;
    @Mock
    private BotService botService;

    private BitbucketWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new BitbucketWebhookHandler(botWebhookService, clientFactory, botService);
        GitIntegration integration = new GitIntegration();
        integration.setUsername("bot@example.com");
        bot = new Bot();
        bot.setName("test-bot");
        // Deliberately different from the Bitbucket account: the bot username must have no effect.
        bot.setUsername("unrelated-bot-name");
        bot.setGitIntegration(integration);

        lenient().when(clientFactory.getApiClient(integration)).thenReturn(bitbucketClient);
        lenient().when(bitbucketClient.getAuthenticatedAccount())
                .thenReturn(new BitbucketAccount("{uuid-ai_bot}", "acc-ai_bot", "ai_bot", "AI Bot"));
        lenient().when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(true);
    }

    @Test
    void eventFromBotAccount_isIgnored() {
        Map<String, Object> raw = pullRequestPayload(List.of(user("ai_bot")), null);
        raw.put("actor", user("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created", raw);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void reviewerMatchedByUuidWhenAccountIdMissing_triggersReview() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(Map.of("uuid", "{uuid-ai_bot}")), null));

        assertEquals("review triggered", response.getBody());
    }

    @Test
    void reviewerWithBotUsernameButOtherAccount_isIgnored() {
        Map<String, Object> impostor = Map.of("nickname", "unrelated-bot-name", "account_id", "acc-other");

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(impostor), null));

        assertEquals("ignored", response.getBody());
    }

    @Test
    void mentionOfBotUsername_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@unrelated-bot-name please explain this", null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void unresolvableBotAccount_ignoresEventAndRecordsBotError() {
        when(bitbucketClient.getAuthenticatedAccount()).thenThrow(new IllegalStateException("401"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
        verify(botService).recordError(eq(bot), contains("401"));
    }

    @Test
    void missingAccountEmail_ignoresEventAndRecordsBotError() {
        when(clientFactory.getApiClient(bot.getGitIntegration()))
                .thenThrow(new IllegalStateException("no Atlassian account e-mail configured"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botService).recordError(eq(bot), contains("no Atlassian account e-mail configured"));
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
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq(READABLE_MENTION))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} - Review the Pull-Request again", null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void nonOwnerReviewAgainComment_isIgnored() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq(READABLE_MENTION))).thenReturn(true);
        when(botWebhookService.isPullRequestAuthor(any(WebhookPayload.class))).thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} - Review the Pull-Request again", null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void regularOwnerMention_routesToBotCommand() {
        lenient().when(botWebhookService.isReviewAgainRequest(any(WebhookPayload.class), eq(READABLE_MENTION))).thenReturn(false);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} please explain this", null));

        assertEquals("command received", response.getBody());
        verify(botWebhookService).handleBotCommand(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void botMention_isReplacedByReadableNameBeforeDelegating() {
        ArgumentCaptor<WebhookPayload> payload = ArgumentCaptor.forClass(WebhookPayload.class);

        handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} generate-tests and ping @{acc-ai_bot} again", null));

        verify(botWebhookService).handleBotCommand(eq(bot), payload.capture());
        assertEquals("@AI_Bot generate-tests and ping @AI_Bot again", payload.getValue().getComment().getBody());
    }

    @Test
    void readableMention_fallsBackToRawMentionWithoutName() {
        assertEquals("@{acc}", new BitbucketAccount("{u}", "acc", null, null).readableMention());
        assertEquals("@ai-bot", new BitbucketAccount("{u}", "acc", "ai-bot", null).readableMention());
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
        return Map.of("nickname", username, "display_name", username,
                "account_id", "acc-" + username, "uuid", "{uuid-" + username + "}");
    }
}
