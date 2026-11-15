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
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.webhook.TranslatingWebhookHandler;
import org.remus.giteabot.webhook.WebhookEventDispatcher;
import org.remus.giteabot.event.WebhookEventKind;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how Bitbucket Cloud webhooks are classified by {@link BitbucketWebhookTranslator} and,
 * through the real {@link WebhookEventDispatcher}, which {@link BotWebhookService} handler they reach.
 */
@ExtendWith(MockitoExtension.class)
class BitbucketWebhookTranslatorTest {

    private static final String READABLE_MENTION = "@AI_Bot";

    @Mock
    private BotWebhookService botWebhookService;
    @Mock
    private RepositoryPlatformFactory repositoryPlatformFactory;
    @Mock
    private BitbucketApiClient bitbucketClient;
    @Mock
    private BotService botService;

    private BitbucketWebhookTranslator translator;
    private TranslatingWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new BitbucketWebhookTranslator(repositoryPlatformFactory, botService, new PullRequestStateTracker());
        handler = new TranslatingWebhookHandler(translator, BitbucketWebhookTranslator.EVENT_HEADER, botWebhookService);
        GitIntegration integration = new GitIntegration();
        integration.setUsername("bot@example.com");
        bot = new Bot();
        bot.setName("test-bot");
        bot.setGitIntegration(integration);

        lenient().when(repositoryPlatformFactory.getApiClient(integration)).thenReturn(bitbucketClient);
        lenient().when(bitbucketClient.getAuthenticatedAccount())
                .thenReturn(new BitbucketAccount("{uuid-ai_bot}", "acc-ai_bot", "ai_bot", "AI Bot"));
    }

    @Test
    void eventFromBotAccount_isIgnored() {
        Map<String, Object> raw = pullRequestPayload(List.of(user("ai_bot")), null);
        raw.put("actor", user("ai_bot"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created", raw);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
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
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void unresolvableBotAccount_ignoresEventAndRecordsBotError() {
        when(bitbucketClient.getAuthenticatedAccount()).thenThrow(new IllegalStateException("401"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
        verify(botService).recordError(eq(bot), contains("401"));
    }

    @Test
    void missingAccountEmail_ignoresEventAndRecordsBotError() {
        when(repositoryPlatformFactory.getApiClient(bot.getGitIntegration()))
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
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void pullRequestCreatedWithoutBotReviewer_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void pullRequestCreatedWithRunOnPrCreation_triggersReviewWithoutBotReviewer() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:created",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void pullRequestUpdatedWithRunOnPrCreation_isStillIgnored() {
        bot.setRunOnPrCreation(true);
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("human")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void pullRequestUpdatedWithoutReviewerChange_isIgnored() {
        // The bot was already a reviewer, and the commit unchanged, when the pull request was
        // opened: creation records both, so the update is neither a review request nor a push.
        bot.setRunOnPrUpdate(true);
        handler.handleWebhook(bot, "pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));
        reset(botWebhookService);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void pullRequestUpdatedWhenBotReviewerAdded_triggersReview() {
        handler.handleWebhook(bot, "pullrequest:created", pullRequestPayload(List.of(user("human")), null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:updated",
                pullRequestPayload(List.of(user("human"), user("ai_bot")), null));

        assertEquals("review triggered", response.getBody());
        // Marked as a review request, so the bot acknowledges it like one elsewhere.
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class),
                eq(WebhookEventKind.PR_REVIEWER_ADDED));
    }

    @Test
    void reviewAgainComment_triggersReview() {

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} - Review the Pull-Request again", null));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void reviewAgainComment_onAPullRequestWithAnAgentSession_continuesTheSession() {
        when(botWebhookService.hasAgentSession(any(), any(WebhookPayload.class))).thenReturn(true);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} - Review the Pull-Request again", null));

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void regularOwnerMention_routesToHandlePrComment() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} please explain this", null));

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void commentMentioningLongerBotName_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@ai_bot-test please explain this", null));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void inlineCommentMentioningLongerBotName_isIgnored() {
        ResponseEntity<String> response = handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@ai_bot-test please explain this",
                        Map.of("path", "src/Foo.java", "to", 3)));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
    }

    @Test
    void botMention_isReplacedByReadableNameBeforeDelegating() {
        ArgumentCaptor<WebhookPayload> payload = ArgumentCaptor.forClass(WebhookPayload.class);

        handler.handleWebhook(bot, "pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} generate-tests and ping @{acc-ai_bot} again", null));

        verify(botWebhookService).handlePrComment(eq(bot), payload.capture());
        assertEquals("@AI_Bot generate-tests and ping @AI_Bot again", payload.getValue().getComment().getBody());
    }

    @Test
    void readableMention_fallsBackToRawMentionWithoutName() {
        assertEquals("@{acc}", new BitbucketAccount("{u}", "acc", null, null).readableMention());
        assertEquals("@ai-bot", new BitbucketAccount("{u}", "acc", "ai-bot", null).readableMention());
    }

    // ---- Pushes (runOnPrUpdate) ----

    @Test
    void pushAfterCreation_withRunOnPrUpdate_triggersReviewOnce() {
        bot.setRunOnPrUpdate(true);
        handler.handleWebhook(bot, "pullrequest:created", pullRequestPayload(List.of(user("human")), null));

        Map<String, Object> pushed = pullRequestPayload(List.of(user("human")), null);
        withHeadSha(pushed, "new-commit");
        ResponseEntity<String> push = handler.handleWebhook(bot, "pullrequest:updated", pushed);

        Map<String, Object> edited = pullRequestPayload(List.of(user("human")), null);
        withHeadSha(edited, "new-commit");
        ResponseEntity<String> titleEdit = handler.handleWebhook(bot, "pullrequest:updated", edited);

        assertEquals("review triggered", push.getBody());
        assertEquals("ignored", titleEdit.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class), any());
    }

    @Test
    void payloadWithoutReviewerList_isNotReadAsARemoval() {
        translator.translate(bot, headers("pullrequest:created"), pullRequestPayload(List.of(user("ai_bot")), null));
        Map<String, Object> withoutReviewers = pullRequestPayload(List.of(user("ai_bot")), null);
        ((Map<?, ?>) withoutReviewers.get("pullrequest")).remove("reviewers");
        translator.translate(bot, headers("pullrequest:updated"), withoutReviewers);

        WebhookEvent next = translator.translate(bot, headers("pullrequest:updated"),
                pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, next.kind());
    }

    private static Map<String, String> headers(String eventKey) {
        return Map.of(BitbucketWebhookTranslator.EVENT_HEADER, eventKey);
    }

    @SuppressWarnings("unchecked")
    private static void withHeadSha(Map<String, Object> raw, String sha) {
        ((Map<String, Object>) raw.get("pullrequest")).put("source",
                Map.of("branch", Map.of("name", "feature"), "commit", Map.of("hash", sha)));
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
