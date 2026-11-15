package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how {@link BitbucketWebhookTranslator} turns Bitbucket Cloud webhooks into
 * {@link WebhookEvent}s. What the bot then does with each kind is covered by
 * {@code WebhookEventDispatcherTest}.
 */
@ExtendWith(MockitoExtension.class)
class BitbucketWebhookTranslatorTest {

    private static final String READABLE_MENTION = "@AI_Bot";

    @Mock
    private RepositoryPlatformFactory repositoryPlatformFactory;
    @Mock
    private BitbucketApiClient bitbucketClient;
    @Mock
    private BotService botService;

    private BitbucketWebhookTranslator translator;
    private Bot bot;

    @BeforeEach
    void setUp() {
        translator = new BitbucketWebhookTranslator(repositoryPlatformFactory, botService, new PullRequestStateTracker());
        GitIntegration integration = new GitIntegration();
        integration.setUsername("bot@example.com");
        bot = new Bot();
        bot.setName("test-bot");
        bot.setGitIntegration(integration);

        lenient().when(repositoryPlatformFactory.getApiClient(integration)).thenReturn(bitbucketClient);
        lenient().when(bitbucketClient.getAuthenticatedAccount())
                .thenReturn(new BitbucketAccount("{uuid-ai_bot}", "acc-ai_bot", "ai_bot", "AI Bot"));
    }

    private WebhookEvent translate(String eventKey, Map<String, Object> raw) {
        return translator.translate(bot, headers(eventKey), raw);
    }

    // ---- Bot identity ----

    @Test
    void eventFromBotAccount_isFromTheBot() {
        Map<String, Object> raw = pullRequestPayload(List.of(user("ai_bot")), null);
        raw.put("actor", user("ai_bot"));

        assertTrue(translate("pullrequest:created", raw).fromBot());
    }

    @Test
    void reviewerMatchedByUuidWhenAccountIdMissing_isTheBotAsReviewer() {
        WebhookEvent event = translate("pullrequest:created",
                pullRequestPayload(List.of(Map.of("uuid", "{uuid-ai_bot}")), null));

        assertTrue(event.botIsReviewer());
    }

    @Test
    void reviewerWithBotUsernameButOtherAccount_isNotTheBot() {
        Map<String, Object> impostor = Map.of("nickname", "unrelated-bot-name", "account_id", "acc-other");

        WebhookEvent event = translate("pullrequest:created", pullRequestPayload(List.of(impostor), null));

        assertFalse(event.botIsReviewer());
    }

    @Test
    void unresolvableBotAccount_ignoresEventAndRecordsBotError() {
        when(bitbucketClient.getAuthenticatedAccount()).thenThrow(new IllegalStateException("401"));

        WebhookEvent event = translate("pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
        verify(botService).recordError(eq(bot), contains("401"));
    }

    @Test
    void missingAccountEmail_ignoresEventAndRecordsBotError() {
        when(repositoryPlatformFactory.getApiClient(bot.getGitIntegration()))
                .thenThrow(new IllegalStateException("no Atlassian account e-mail configured"));

        WebhookEvent event = translate("pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
        verify(botService).recordError(eq(bot), contains("no Atlassian account e-mail configured"));
    }

    @Test
    void readableMention_fallsBackToRawMentionWithoutName() {
        assertEquals("@{acc}", new BitbucketAccount("{u}", "acc", null, null).readableMention());
        assertEquals("@ai-bot", new BitbucketAccount("{u}", "acc", "ai-bot", null).readableMention());
    }

    // ---- Pull request lifecycle ----

    @Test
    void pullRequestCreatedWithBotReviewer_isPrOpenedWithTheBotAsReviewer() {
        WebhookEvent event = translate("pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertTrue(event.botIsReviewer());
        assertFalse(event.fromBot());
    }

    @Test
    void pullRequestCreatedWithoutBotReviewer_isPrOpenedWithoutTheBotAsReviewer() {
        WebhookEvent event = translate("pullrequest:created", pullRequestPayload(List.of(user("human")), null));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.botIsReviewer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pullrequest:fulfilled", "pullrequest:rejected", "pullrequest:merged", "pullrequest:declined"})
    void pullRequestClosed_isPrClosed(String eventKey) {
        assertEquals(WebhookEventKind.PR_CLOSED, translate(eventKey, pullRequestPayload(List.of(), null)).kind());
    }

    @Test
    void pullRequestUpdatedWithoutPushOrReviewerChange_isIgnored() {
        // The bot was already a reviewer, and the commit unchanged, when the pull request was
        // opened: creation records both, so the update is neither a review request nor a push.
        translate("pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));

        WebhookEvent event = translate("pullrequest:updated", pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void pullRequestUpdatedWhenBotReviewerAdded_isPrReviewerAdded() {
        translate("pullrequest:created", pullRequestPayload(List.of(user("human")), null));

        WebhookEvent event = translate("pullrequest:updated",
                pullRequestPayload(List.of(user("human"), user("ai_bot")), null));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, event.kind());
    }

    @Test
    void pushAfterCreation_isPrSynchronizedOnce() {
        translate("pullrequest:created", pullRequestPayload(List.of(user("human")), null));

        Map<String, Object> pushed = pullRequestPayload(List.of(user("human")), null);
        withHeadSha(pushed, "new-commit");
        WebhookEvent push = translate("pullrequest:updated", pushed);

        Map<String, Object> edited = pullRequestPayload(List.of(user("human")), null);
        withHeadSha(edited, "new-commit");
        WebhookEvent titleEdit = translate("pullrequest:updated", edited);

        assertEquals(WebhookEventKind.PR_SYNCHRONIZED, push.kind());
        assertEquals(WebhookEventKind.IGNORED, titleEdit.kind());
    }

    @Test
    void payloadWithoutReviewerList_isNotReadAsARemoval() {
        translate("pullrequest:created", pullRequestPayload(List.of(user("ai_bot")), null));
        Map<String, Object> withoutReviewers = pullRequestPayload(List.of(user("ai_bot")), null);
        ((Map<?, ?>) withoutReviewers.get("pullrequest")).remove("reviewers");
        translate("pullrequest:updated", withoutReviewers);

        WebhookEvent next = translate("pullrequest:updated", pullRequestPayload(List.of(user("ai_bot")), null));

        assertEquals(WebhookEventKind.IGNORED, next.kind());
    }

    // ---- Comments ----

    @Test
    void commentMentioningTheBotAccount_isPrCommentMentioningTheBot() {
        WebhookEvent event = translate("pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} - Review the Pull-Request again", null));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals(READABLE_MENTION, event.botAlias());
    }

    @Test
    void mentionOfBotUsername_isNotAMention() {
        WebhookEvent event = translate("pullrequest:comment_created",
                commentPayload("@unrelated-bot-name please explain this", null));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertFalse(event.botMentioned());
    }

    @Test
    void commentMentioningLongerBotName_isNotAMention() {
        assertFalse(translate("pullrequest:comment_created",
                commentPayload("@ai_bot-test please explain this", null)).botMentioned());
    }

    @Test
    void inlineComment_isPrInlineCommentWithItsAnchor() {
        WebhookEvent event = translate("pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} why?", Map.of("path", "src/Foo.java", "to", 3)));

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals("src/Foo.java", event.payload().getComment().getPath());
        assertEquals(3, event.payload().getComment().getLine());
    }

    @Test
    void inlineCommentMentioningLongerBotName_isNotAMention() {
        WebhookEvent event = translate("pullrequest:comment_created",
                commentPayload("@ai_bot-test please explain this", Map.of("path", "src/Foo.java", "to", 3)));

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertFalse(event.botMentioned());
    }

    @Test
    void botMention_isReplacedByReadableName() {
        WebhookEvent event = translate("pullrequest:comment_created",
                commentPayload("@{acc-ai_bot} generate-tests and ping @{acc-ai_bot} again", null));

        assertEquals("@AI_Bot generate-tests and ping @AI_Bot again", event.payload().getComment().getBody());
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
