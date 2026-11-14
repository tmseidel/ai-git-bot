package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.webhook.TranslatingWebhookHandler;
import org.remus.giteabot.webhook.WebhookEventDispatcher;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers how {@link AzureDevopsWebhookTranslator} classifies events and, through the real
 * {@link WebhookEventDispatcher}, which {@link BotWebhookService} handler they reach:
 * routing, bot-loop suppression, the event-to-event state, and the various "ignored"
 * paths. The payload translation itself is covered by {@link AzureDevopsPayloadTranslationTest}.
 */
class AzureDevopsWebhookTranslatorTest {

    private BotWebhookService botWebhookService;
    private RepositoryPlatformFactory repositoryPlatformFactory;
    private AzureDevopsWebhookTranslator translator;
    private TranslatingWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        botWebhookService = mock(BotWebhookService.class);
        repositoryPlatformFactory = mock(RepositoryPlatformFactory.class);
        translator = new AzureDevopsWebhookTranslator(repositoryPlatformFactory, new PullRequestStateTracker());
        handler = new TranslatingWebhookHandler(translator, null, botWebhookService);

        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/contoso");
        integration.setUsername("bot@contoso.com");

        bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");
        bot.setRunOnPrCreation(true);
        bot.setGitIntegration(integration);

    }

    @Test
    void pullRequestCreated_triggersReview() {
        handler.handleWebhook(bot, pullRequestPayload("git.pullrequest.created"));

        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestCompleted_closesSession() {
        // Completion arrives as an "updated" event carrying status completed.
        handler.handleWebhook(bot, pullRequestPayloadWithStatus(
                "git.pullrequest.updated", "completed"));

        verify(botWebhookService).handlePrClosed(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestMergeAttempt_doesNotCloseSession() {
        // Regression guard: "git.pullrequest.merged" is Azure DevOps's "merge attempted"
        // event and fires when the preview merge commit is built — including right after
        // the PR is opened. Closing on it would kill the session under an open PR.
        var response = handler.handleWebhook(bot, pullRequestPayload("git.pullrequest.merged"));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrClosed(any(), any());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void eventFromBotItself_isIgnored() {
        // Azure DevOps reports the creator of a new pull request as its createdBy.
        assertEquals("ignored",
                handler.handleWebhook(bot, withResource(pullRequestPayload("git.pullrequest.created"),
                        "createdBy", Map.of("uniqueName", "bot@contoso.com")))
                        .getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void unknownEventType_isIgnoredWithOk() {
        var response = handler.handleWebhook(bot, pullRequestPayload("git.push"));

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("ignored", response.getBody());
        verifyNoMoreInteractions(ignoreStubs(botWebhookService));
    }

    @Test
    void missingEventType_isIgnored() {
        var response = handler.handleWebhook(bot, Map.of("resource", Map.of()));

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("ignored", response.getBody());
    }

    // ---- git.pullrequest.updated fires on far more than pushes ----

    @Test
    void pullRequestUpdated_withNewSourceCommit_triggersReview() {
        bot.setRunOnPrUpdate(true);

        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha(
                "git.pullrequest.updated", "aaaa1111bbbb2222cccc3333dddd4444eeee5555"));

        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestUpdated_withUnchangedSourceCommit_isIgnored() {
        // A reviewer voting, or the author editing the title/description, produces a
        // git.pullrequest.updated event with the source branch still at the same commit.
        // Re-reviewing the whole PR on those would burn AI tokens on every human click.
        bot.setRunOnPrUpdate(true);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));
        var second = handler.handleWebhook(bot,
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));

        assertEquals("ignored", second.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestCreated_seedsHeadShaSoTheFollowUpUpdateIsNotAPush() {
        // Azure DevOps fires git.pullrequest.updated moments after creation. Without
        // seeding on create, that event looks like the first push and doubles the review.
        bot.setRunOnPrUpdate(true);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));

        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestUpdated_withoutSourceCommit_stillTriggersReview() {
        // A subscription with a reduced "Resource details to send" omits the commit.
        // Dropping those events would be worse than an occasional redundant review.
        bot.setRunOnPrUpdate(true);

        handler.handleWebhook(bot, pullRequestPayload("git.pullrequest.updated"));
        handler.handleWebhook(bot, pullRequestPayload("git.pullrequest.updated"));

        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestReopenedAfterClose_reviewsAgainAtTheSameCommit() {
        // Closing forgets the tracked sha, so a PR that is completed and later updated
        // again at the same commit is not mistaken for a duplicate.
        bot.setRunOnPrUpdate(true);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));
        handler.handleWebhook(bot, withResource(
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha), "status", "abandoned"));
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));

        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    // ---- Reviewer added after the pull request was opened ----

    @Test
    void botAddedAsReviewerAfterOpen_triggersReview() {
        // Azure DevOps sends no review_requested event: adding the bot as a reviewer of
        // an open pull request arrives as a plain "updated" whose reviewer list now
        // carries the bot, with the source branch exactly where it was. Neither run-on
        // switch is set, which is the configuration the review-trigger notice describes.
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));
        verify(botWebhookService, never()).reviewPullRequest(any(), any());

        var response = handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"));

        assertEquals("review triggered", response.getBody());
        // Marked as a review request, so the bot acknowledges it like one elsewhere.
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot),
                argThat(payload -> "review_requested".equals(payload.getAction())));
    }

    @Test
    void botAlreadyReviewer_doesNotReviewAgainOnEveryFollowUpUpdate() {
        // Votes and title edits keep the bot in the reviewer list. Reacting to the list
        // rather than to the change to it would re-review the pull request every time.
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"));
        var second = handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"));

        assertEquals("ignored", second.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void botReviewerAtCreation_isNotReviewedTwiceByTheFollowUpUpdate() {
        // The reviewer list is seeded at creation for the same reason the head sha is:
        // Azure DevOps fires an "updated" event moments after the pull request opens.
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.created", sha, "bot@contoso.com"));
        handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"));

        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void reviewerListOmittedByAReducedSubscription_isNotReadAsAReviewerChange() {
        // A subscription configured with reduced "Resource details to send" omits
        // reviewers entirely. Treating that as a removal would make the next full
        // payload look like a fresh review request.
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

        handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.created", sha, "bot@contoso.com"));
        var response = handler.handleWebhook(bot,
                pullRequestPayloadWithoutReviewers("git.pullrequest.updated", sha));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void botsOwnVoteAddingItToTheReviewerList_doesNotTriggerASecondReview() {
        // Casting a vote adds the caller to the reviewer list. With the bot not an explicit
        // reviewer, its post-review vote arrives as an "updated" listing the bot with a
        // non-zero vote, and must not be read as a fresh review request.
        bot.setRunOnPrCreation(true);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));
        verify(botWebhookService, times(1)).reviewPullRequest(any(), any());

        var response = handler.handleWebhook(bot, withResource(
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha), "reviewers",
                List.of(Map.of("uniqueName", "bot@contoso.com", "vote", 10))));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(any(), any());
    }

    @Test
    void botAddedAsReviewerWithoutAVote_stillTriggersReview() {
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));

        var response = handler.handleWebhook(bot, withResource(
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha), "reviewers",
                List.of(Map.of("uniqueName", "bot@contoso.com", "vote", 0))));

        assertEquals("review triggered", response.getBody());
    }

    @Test
    void otherReviewerAdded_doesNotTriggerReview() {
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));

        var response = handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "someone-else@contoso.com"));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void reactivatedPullRequestIsTreatedAsReopened_notAsAPlainUpdate() {
        // Azure DevOps has no reopened event: reactivating an abandoned pull request is
        // an update whose status is active again. Every other provider reviews a
        // reopened PR when the bot is a requested reviewer, without needing
        // run-on-update, and this must match.
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.created", sha, "bot@contoso.com"));
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));

        handler.handleWebhook(bot, withResource(pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"), "status", "abandoned"));

        var response = handler.handleWebhook(bot, pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com"));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void reactivatedPullRequestWithoutBotReviewer_isNotReviewedWhenBothSwitchesAreOff() {
        bot.setRunOnPrCreation(false);
        bot.setRunOnPrUpdate(false);
        String sha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        handler.handleWebhook(bot, pullRequestPayloadWithHeadSha("git.pullrequest.created", sha));
        handler.handleWebhook(bot, pullRequestPayloadWithStatus("git.pullrequest.updated", "abandoned"));

        var response = handler.handleWebhook(bot,
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    // ---- (a) null-guard on translateCommentEvent: pr == null ----

    @Test
    void commentWithoutPullRequest_isIgnoredEvenWhenBodyMentionsBotAlias() {
        // Dangerous path: a malformed comment payload missing resource.pullRequest,
        // where the comment body still mentions the bot alias. Without the null
        // guard, the dispatcher would happily call handlePrComment (or worse,
        // reviewPullRequest/handleInlineComment) with a payload whose pull
        // request, repository, number and issue are all null.
        Map<String, Object> payload = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot@contoso.com please review",
                                "author", Map.of("uniqueName", "dev@contoso.com"))));
        // Note: "pullRequest" is deliberately absent from "resource".

        var response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    // ---- (b) organizationFromResourceContainers(Map) — envelope-based resolution ----
    //
    // GitIntegration.url stays the instance root (no organization) so one integration
    // serves many organizations; the organization is resolved per-event from the Service
    // Hook envelope's resourceContainers.collection.baseUrl instead. See the design doc's
    // "D6 — Where the organization comes from".

    // The resolving cases, modern and on-prem, are covered through translatePayload in
    // AzureDevopsPayloadTranslationTest.

    @Test
    void organizationFromResourceContainers_missingOrUnusableContainers_returnsNull() {
        // Better to ignore the event than to invent an organization that fails later.
        assertNull(translator.organizationFromResourceContainers(null));
        assertNull(translator.organizationFromResourceContainers(Map.of()));
        assertNull(translator.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of())));
        // A collection container with no baseUrl at all.
        assertNull(translator.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of("collection", Map.of("id", "c1d373ed")))));
        // A blank baseUrl.
        assertNull(translator.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of("collection", Map.of("baseUrl", "")))));
    }

    @Test
    void organizationFromResourceContainers_collectionEqualToDeploymentRoot_returnsNull() {
        // A collection base url identical to the deployment root names no collection, so
        // the last-segment rule would otherwise return the virtual directory "tfs".
        assertNull(translator.organizationFromResourceContainers(Map.of(
                "resourceContainers", Map.of(
                        "collection", Map.of("baseUrl", "https://tfs.example.com/tfs/"),
                        "server", Map.of("baseUrl", "https://tfs.example.com/tfs")))));
    }

    // ---- (b') organizationFromCollectionBaseUrl(String) — the url forms themselves ----

    @Test
    void organizationFromCollectionBaseUrl_modernForm_returnsLastPathSegment() {
        assertEquals("fabrikam", translator.organizationFromCollectionBaseUrl(
                "https://dev.azure.com/fabrikam/"));
        assertEquals("fabrikam", translator.organizationFromCollectionBaseUrl(
                "https://dev.azure.com/fabrikam"));
    }

    @Test
    void organizationFromCollectionBaseUrl_serverFormBehindVirtualDirectories_returnsCollection() {
        // Azure DevOps Server can sit behind any number of virtual-directory segments, so
        // the collection is the last segment rather than one at a fixed depth.
        assertEquals("DefaultCollection", translator.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/tfs/DefaultCollection/"));
        assertEquals("DefaultCollection", translator.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/DefaultCollection/"));
        assertEquals("ProjectCollection", translator.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/a/b/c/ProjectCollection/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_legacyForm_returnsHostLabel() {
        // The legacy collection base url has an empty path.
        assertEquals("fabrikam", translator.organizationFromCollectionBaseUrl(
                "https://fabrikam.visualstudio.com/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_legacyHostWins_overItsCollectionSegment() {
        // A legacy base url may also carry a collection segment, but the organization is
        // the host label — resolving the collection instead would address the wrong one.
        assertEquals("fabrikam", translator.organizationFromCollectionBaseUrl(
                "https://fabrikam.visualstudio.com/DefaultCollection/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_unusableUrl_returnsNull() {
        assertNull(translator.organizationFromCollectionBaseUrl(null));
        assertNull(translator.organizationFromCollectionBaseUrl(""));
        assertNull(translator.organizationFromCollectionBaseUrl("not a url"));
        // Nothing to read: no path segment, and no legacy host label to fall back to.
        assertNull(translator.organizationFromCollectionBaseUrl("https://tfs.example.com/"));
    }

    // ---- Comments ----

    @Test
    void inlineCommentMentioningBot_isHydratedAndRoutesToHandleInlineComment() {
        AzureDevopsApiClient client = mock(AzureDevopsApiClient.class);
        when(repositoryPlatformFactory.getApiClient(bot.getGitIntegration())).thenReturn(client);
        when(client.getInlineThreadContext(any(), any(), any(), anyLong()))
                .thenReturn(new AzureDevopsApiClient.InlineThreadContext("src/Foo.java", 12));

        ResponseEntity<String> response = handler.handleWebhook(bot, commentPayload("@bot@contoso.com why this?"));

        assertEquals("inline comment response triggered", response.getBody());
        var captor = org.mockito.ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleInlineComment(eq(bot), captor.capture());
        assertEquals("src/Foo.java", captor.getValue().getComment().getPath());
        assertEquals(12, captor.getValue().getComment().getLine());
    }

    private static Map<String, Object> commentPayload(String content) {
        return Map.of("eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", content,
                                "author", Map.of("uniqueName", "dev@contoso.com"),
                                "_links", Map.of("threads", Map.of("href",
                                        "https://dev.azure.com/contoso/_apis/git/repositories/"
                                                + "my-service/pullRequests/42/threads/5"))),
                        "pullRequest", ((Map<?, ?>) pullRequestPayload("git.pullrequest.created").get("resource"))),
                "resourceContainers", pullRequestPayload("git.pullrequest.created").get("resourceContainers"));
    }

    private static Map<String, Object> pullRequestPayloadWithHeadSha(String eventType, String sha) {
        return withResource(pullRequestPayload(eventType), "lastMergeSourceCommit", Map.of("commitId", sha));
    }

    private static Map<String, Object> pullRequestPayloadWithReviewers(String eventType, String sha,
                                                                       String... uniqueNames) {
        return withResource(pullRequestPayloadWithHeadSha(eventType, sha), "reviewers",
                java.util.Arrays.stream(uniqueNames).map(name -> Map.of("uniqueName", name)).toList());
    }

    private static Map<String, Object> pullRequestPayloadWithoutReviewers(String eventType, String sha) {
        return withResource(pullRequestPayloadWithHeadSha(eventType, sha), "reviewers", null);
    }

    private static Map<String, Object> pullRequestPayloadWithStatus(String eventType, String status) {
        return withResource(pullRequestPayload(eventType), "status", status);
    }

    /** Copies the payload with one {@code resource} field replaced, or removed when {@code value} is null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withResource(Map<String, Object> raw, String key, Object value) {
        Map<String, Object> copy = new java.util.HashMap<>(raw);
        Map<String, Object> resource = new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        if (value == null) {
            resource.remove(key);
        } else {
            resource.put(key, value);
        }
        copy.put("resource", resource);
        return copy;
    }

    private static Map<String, Object> pullRequestPayload(String eventType) {
        return Map.of("eventType", eventType,
                "resource", Map.of(
                        "pullRequestId", 42,
                        "title", "Add feature",
                        "status", "active",
                        "sourceRefName", "refs/heads/feature",
                        "targetRefName", "refs/heads/main",
                        "createdBy", Map.of("uniqueName", "dev@contoso.com"),
                        "reviewers", List.of(),
                        "repository", Map.of("name", "my-service",
                                "project", Map.of("name", "MyProject"),
                                // Project-scoped, as Azure DevOps sends it: the segment
                                // before _apis is the project id, not the collection.
                                "url", "https://dev.azure.com/contoso/"
                                        + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/git/"
                                        + "repositories/my-service")),
                "resourceContainers", Map.of(
                        "collection", Map.of("id", "c12d0eb8-e382-443b-9f9c-c52cba5014c2",
                                "baseUrl", "https://dev.azure.com/contoso/"),
                        "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                "baseUrl", "https://dev.azure.com/contoso/")));
    }
}
