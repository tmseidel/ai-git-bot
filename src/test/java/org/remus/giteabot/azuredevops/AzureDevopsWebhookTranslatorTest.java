package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Covers how {@link AzureDevopsWebhookTranslator} turns Azure DevOps Service Hook events into
 * {@link WebhookEvent}s: classification, bot identity, the event-to-event state that tells a
 * push from a vote or edit, and the various "ignored" paths. The payload translation itself is
 * covered by {@link AzureDevopsPayloadTranslationTest}; what the bot does with each kind by
 * {@code WebhookEventDispatcherTest}.
 */
class AzureDevopsWebhookTranslatorTest {

    private static final String SHA = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";

    private RepositoryPlatformFactory repositoryPlatformFactory;
    private AzureDevopsWebhookTranslator translator;
    private Bot bot;

    @BeforeEach
    void setUp() {
        repositoryPlatformFactory = mock(RepositoryPlatformFactory.class);
        translator = new AzureDevopsWebhookTranslator(repositoryPlatformFactory, new PullRequestStateTracker());

        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/contoso");
        integration.setUsername("bot@contoso.com");

        bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");
        bot.setGitIntegration(integration);
    }

    /** Azure DevOps carries the event type in the body, so no header is needed. */
    private WebhookEvent translate(Map<String, Object> payload) {
        return translator.translate(bot, Map.of(), payload);
    }

    // ---- Lifecycle ----

    @Test
    void pullRequestCreated_isPrOpened() {
        WebhookEvent event = translate(pullRequestPayload("git.pullrequest.created"));

        assertEquals(WebhookEventKind.PR_OPENED, event.kind());
        assertFalse(event.fromBot());
    }

    @Test
    void pullRequestCompletedOrAbandoned_isPrClosed() {
        // Completion arrives as an "updated" event carrying status completed.
        assertEquals(WebhookEventKind.PR_CLOSED,
                translate(pullRequestPayloadWithStatus("git.pullrequest.updated", "completed")).kind());
        assertEquals(WebhookEventKind.PR_CLOSED,
                translate(pullRequestPayloadWithStatus("git.pullrequest.updated", "abandoned")).kind());
    }

    @Test
    void pullRequestMergeAttempt_isIgnored() {
        // Regression guard: "git.pullrequest.merged" is Azure DevOps's "merge attempted"
        // event and fires when the preview merge commit is built — including right after
        // the PR is opened. Closing on it would kill the session under an open PR.
        assertEquals(WebhookEventKind.IGNORED, translate(pullRequestPayload("git.pullrequest.merged")).kind());
    }

    @Test
    void pullRequestCreatedByTheBot_isFromTheBot() {
        // Azure DevOps reports the creator of a new pull request as its createdBy.
        WebhookEvent event = translate(withResource(pullRequestPayload("git.pullrequest.created"),
                "createdBy", Map.of("uniqueName", "bot@contoso.com")));

        assertTrue(event.fromBot());
    }

    @Test
    void unknownEventType_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED, translate(pullRequestPayload("git.push")).kind());
    }

    @Test
    void missingEventType_isIgnored() {
        assertEquals(WebhookEventKind.IGNORED, translate(Map.of("resource", Map.of())).kind());
    }

    // ---- git.pullrequest.updated fires on far more than pushes ----

    @Test
    void pullRequestUpdated_withNewSourceCommit_isPrSynchronized() {
        assertEquals(WebhookEventKind.PR_SYNCHRONIZED,
                translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA)).kind());
    }

    @Test
    void pullRequestUpdated_withUnchangedSourceCommit_isIgnored() {
        // A reviewer voting, or the author editing the title/description, produces a
        // git.pullrequest.updated event with the source branch still at the same commit.
        // Re-reviewing the whole PR on those would burn AI tokens on every human click.
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA));

        WebhookEvent second = translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA));

        assertEquals(WebhookEventKind.IGNORED, second.kind());
    }

    @Test
    void pullRequestCreated_seedsHeadShaSoTheFollowUpUpdateIsNotAPush() {
        // Azure DevOps fires git.pullrequest.updated moments after creation. Without
        // seeding on create, that event looks like the first push and doubles the review.
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.created", SHA));

        WebhookEvent update = translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA));

        assertEquals(WebhookEventKind.IGNORED, update.kind());
    }

    @Test
    void pullRequestUpdated_withoutSourceCommit_isAlwaysPrSynchronized() {
        // A subscription with a reduced "Resource details to send" omits the commit.
        // Dropping those events would be worse than an occasional redundant review.
        assertEquals(WebhookEventKind.PR_SYNCHRONIZED, translate(pullRequestPayload("git.pullrequest.updated")).kind());
        assertEquals(WebhookEventKind.PR_SYNCHRONIZED, translate(pullRequestPayload("git.pullrequest.updated")).kind());
    }

    // ---- Reactivation ----

    @Test
    void reactivatedPullRequest_isPrReopenedAtTheSameCommit() {
        // Azure DevOps has no reopened event: reactivating an abandoned pull request is
        // an update whose status is active again. Closing forgets the tracked sha, so the
        // reactivation is not mistaken for a duplicate of the last update either.
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA));
        translate(withResource(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA), "status", "abandoned"));

        WebhookEvent reactivated = translate(pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA));

        assertEquals(WebhookEventKind.PR_REOPENED, reactivated.kind());
        assertFalse(reactivated.botIsReviewer());
    }

    @Test
    void reactivatedPullRequestWithBotReviewer_isPrReopenedWithTheBotAsReviewer() {
        // Every other provider reviews a reopened PR when the bot is a requested reviewer,
        // without needing run-on-update; the reopen kind lets the dispatcher do the same.
        translate(pullRequestPayloadWithReviewers("git.pullrequest.created", SHA, "bot@contoso.com"));
        translate(withResource(pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", SHA, "bot@contoso.com"), "status", "abandoned"));

        WebhookEvent reactivated = translate(pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", SHA, "bot@contoso.com"));

        assertEquals(WebhookEventKind.PR_REOPENED, reactivated.kind());
        assertTrue(reactivated.botIsReviewer());
    }

    // ---- Reviewer added after the pull request was opened ----

    @Test
    void botAddedAsReviewerAfterOpen_isPrReviewerAdded() {
        // Azure DevOps sends no review_requested event: adding the bot as a reviewer of
        // an open pull request arrives as a plain "updated" whose reviewer list now
        // carries the bot, with the source branch exactly where it was.
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.created", SHA));

        WebhookEvent event = translate(pullRequestPayloadWithReviewers("git.pullrequest.updated", SHA, "bot@contoso.com"));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, event.kind());
    }

    @Test
    void botAlreadyReviewer_isNotAddedAgainOnEveryFollowUpUpdate() {
        // Votes and title edits keep the bot in the reviewer list. Reacting to the list
        // rather than to the change to it would re-review the pull request every time.
        WebhookEvent first = translate(pullRequestPayloadWithReviewers("git.pullrequest.updated", SHA, "bot@contoso.com"));
        WebhookEvent second = translate(pullRequestPayloadWithReviewers("git.pullrequest.updated", SHA, "bot@contoso.com"));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, first.kind());
        assertEquals(WebhookEventKind.IGNORED, second.kind());
    }

    @Test
    void botReviewerAtCreation_isNotAddedAgainByTheFollowUpUpdate() {
        // The reviewer list is seeded at creation for the same reason the head sha is:
        // Azure DevOps fires an "updated" event moments after the pull request opens.
        WebhookEvent created = translate(pullRequestPayloadWithReviewers("git.pullrequest.created", SHA, "bot@contoso.com"));
        WebhookEvent update = translate(pullRequestPayloadWithReviewers("git.pullrequest.updated", SHA, "bot@contoso.com"));

        assertEquals(WebhookEventKind.PR_OPENED, created.kind());
        assertTrue(created.botIsReviewer());
        assertEquals(WebhookEventKind.IGNORED, update.kind());
    }

    @Test
    void reviewerListOmittedByAReducedSubscription_isNotReadAsAReviewerChange() {
        // A subscription configured with reduced "Resource details to send" omits
        // reviewers entirely. Treating that as a removal would make the next full
        // payload look like a fresh review request.
        translate(pullRequestPayloadWithReviewers("git.pullrequest.created", SHA, "bot@contoso.com"));
        WebhookEvent withoutReviewers = translate(pullRequestPayloadWithoutReviewers("git.pullrequest.updated", SHA));
        WebhookEvent withReviewers = translate(pullRequestPayloadWithReviewers("git.pullrequest.updated", SHA, "bot@contoso.com"));

        assertEquals(WebhookEventKind.IGNORED, withoutReviewers.kind());
        assertEquals(WebhookEventKind.IGNORED, withReviewers.kind());
    }

    @Test
    void botsOwnVoteAddingItToTheReviewerList_isNotAReviewerAddition() {
        // Casting a vote adds the caller to the reviewer list. With the bot not an explicit
        // reviewer, its post-review vote arrives as an "updated" listing the bot with a
        // non-zero vote, and must not be read as a fresh review request.
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.created", SHA));

        WebhookEvent event = translate(withResource(
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA), "reviewers",
                List.of(Map.of("uniqueName", "bot@contoso.com", "vote", 10))));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    @Test
    void botAddedAsReviewerWithoutAVote_isPrReviewerAdded() {
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.created", SHA));

        WebhookEvent event = translate(withResource(
                pullRequestPayloadWithHeadSha("git.pullrequest.updated", SHA), "reviewers",
                List.of(Map.of("uniqueName", "bot@contoso.com", "vote", 0))));

        assertEquals(WebhookEventKind.PR_REVIEWER_ADDED, event.kind());
    }

    @Test
    void otherReviewerAdded_isIgnored() {
        translate(pullRequestPayloadWithHeadSha("git.pullrequest.created", SHA));

        WebhookEvent event = translate(pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", SHA, "someone-else@contoso.com"));

        assertEquals(WebhookEventKind.IGNORED, event.kind());
    }

    // ---- Comments ----

    @Test
    void commentWithoutPullRequest_isIgnoredEvenWhenBodyMentionsBotAlias() {
        // A malformed comment payload missing resource.pullRequest, where the comment body
        // still mentions the bot alias. Without the null guard, a comment handler would be
        // reached with a payload whose pull request, repository, number and issue are null.
        Map<String, Object> payload = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot@contoso.com please review",
                                "author", Map.of("uniqueName", "dev@contoso.com"))));
        // Note: "pullRequest" is deliberately absent from "resource".

        assertEquals(WebhookEventKind.IGNORED, translate(payload).kind());
    }

    @Test
    void inlineCommentMentioningBot_isHydratedIntoPrInlineComment() {
        AzureDevopsApiClient client = mock(AzureDevopsApiClient.class);
        when(repositoryPlatformFactory.getApiClient(bot.getGitIntegration())).thenReturn(client);
        when(client.getInlineThreadContext(any(), any(), any(), anyLong()))
                .thenReturn(new AzureDevopsApiClient.InlineThreadContext("src/Foo.java", 12));

        WebhookEvent event = translate(commentPayload("@bot@contoso.com why this?"));

        assertEquals(WebhookEventKind.PR_INLINE_COMMENT, event.kind());
        assertTrue(event.botMentioned());
        assertEquals("src/Foo.java", event.payload().getComment().getPath());
        assertEquals(12, event.payload().getComment().getLine());
    }

    @Test
    void conversationCommentMentioningBot_isPrComment() {
        AzureDevopsApiClient client = mock(AzureDevopsApiClient.class);
        when(repositoryPlatformFactory.getApiClient(bot.getGitIntegration())).thenReturn(client);

        WebhookEvent event = translate(commentPayload("@bot@contoso.com please explain"));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertTrue(event.botMentioned());
    }

    @Test
    void commentNotMentioningBot_isNotHydrated() {
        // Azure DevOps fires the comment event for every comment on every pull request;
        // only those addressing the bot are worth an API call.
        WebhookEvent event = translate(commentPayload("looks good"));

        assertEquals(WebhookEventKind.PR_COMMENT, event.kind());
        assertFalse(event.botMentioned());
        verifyNoInteractions(repositoryPlatformFactory);
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
