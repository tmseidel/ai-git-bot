package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the dispatch half of {@link AzureDevopsWebhookHandler}: routing, bot-loop
 * suppression, and the various "ignored" paths. The translation half is covered by
 * {@link AzureDevopsPayloadTranslationTest}.
 */
class AzureDevopsWebhookHandlerTest {

    private BotWebhookService botWebhookService;
    private GiteaClientFactory clientFactory;
    private AzureDevopsWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        botWebhookService = mock(BotWebhookService.class);
        clientFactory = mock(GiteaClientFactory.class);
        handler = new AzureDevopsWebhookHandler(botWebhookService, clientFactory);

        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/contoso");

        bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");
        bot.setUsername("bot@contoso.com");
        bot.setRunOnPrCreation(true);
        bot.setGitIntegration(integration);

        when(botWebhookService.isBotUser(any(), any())).thenReturn(false);
        when(botWebhookService.getBotAlias(any())).thenReturn("@bot");
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
        when(botWebhookService.isBotUser(any(), any())).thenReturn(true);

        assertEquals("ignored",
                handler.handleWebhook(bot, pullRequestPayload("git.pullrequest.created"))
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
        Map<String, Object> closed = pullRequestPayloadWithHeadSha("git.pullrequest.updated", sha);
        Map<String, Object> closedResource =
                new java.util.HashMap<>((Map<String, Object>) closed.get("resource"));
        closedResource.put("status", "abandoned");
        closed.put("resource", closedResource);
        handler.handleWebhook(bot, closed);
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
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(WebhookPayload.class));
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

        Map<String, Object> abandoned = pullRequestPayloadWithReviewers(
                "git.pullrequest.updated", sha, "bot@contoso.com");
        Map<String, Object> abandonedResource =
                new java.util.HashMap<>((Map<String, Object>) abandoned.get("resource"));
        abandonedResource.put("status", "abandoned");
        abandoned.put("resource", abandonedResource);
        handler.handleWebhook(bot, abandoned);

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
        // guard, handlePullRequestComment would happily call handleBotCommand (or
        // worse, reviewPullRequest/handleInlineComment) with a payload whose
        // pull request, repository, number and issue are all null.
        Map<String, Object> payload = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot please review",
                                "author", Map.of("uniqueName", "dev@contoso.com"))));
        // Note: "pullRequest" is deliberately absent from "resource".

        var response = handler.handleWebhook(bot, payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleBotCommand(any(), any());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    // ---- (b) organizationFromResourceContainers(Map) — envelope-based resolution ----
    //
    // GitIntegration.url stays the instance root (no organization) so one integration
    // serves many organizations; the organization is resolved per-event from the Service
    // Hook envelope's resourceContainers.collection.baseUrl instead. See the design doc's
    // "D6 — Where the organization comes from".

    @Test
    void organizationFromResourceContainers_serverForm_returnsCollectionNotProjectId() {
        // Regression, from a real Azure DevOps Server envelope: the organization used to be
        // read from resource.repository.url, whose segment before _apis is the *project
        // id*, not the collection. That produced ".../tfs/ce0eacb6-.../Demo2/_apis/..." — a
        // collection that does not exist, which the server rejects with 401 rather than 404
        // because it resolves the collection before it authorizes.
        assertEquals("Experimental", handler.organizationFromResourceContainers(Map.of(
                "resourceContainers", Map.of(
                        "collection", Map.of("id", "c1d373ed-385a-4447-be66-3ef95bb92c08",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/Experimental/"),
                        "server", Map.of("id", "a3448944-3192-47c6-99e4-13c689ae00d4",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/"),
                        "project", Map.of("id", "ce0eacb6-6bf0-4a90-946c-89b6201c2457",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/Experimental/")))));
    }

    @Test
    void organizationFromResourceContainers_modernForm_returnsOrganization() {
        assertEquals("fabrikam", handler.organizationFromResourceContainers(Map.of(
                "resourceContainers", Map.of(
                        "collection", Map.of("baseUrl", "https://dev.azure.com/fabrikam/")))));
    }

    @Test
    void organizationFromResourceContainers_missingOrUnusableContainers_returnsNull() {
        // Better to ignore the event than to invent an organization that fails later.
        assertNull(handler.organizationFromResourceContainers(null));
        assertNull(handler.organizationFromResourceContainers(Map.of()));
        assertNull(handler.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of())));
        // A collection container with no baseUrl at all.
        assertNull(handler.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of("collection", Map.of("id", "c1d373ed")))));
        // A blank baseUrl.
        assertNull(handler.organizationFromResourceContainers(
                Map.of("resourceContainers", Map.of("collection", Map.of("baseUrl", "")))));
    }

    @Test
    void organizationFromResourceContainers_collectionEqualToDeploymentRoot_returnsNull() {
        // A collection base url identical to the deployment root names no collection, so
        // the last-segment rule would otherwise return the virtual directory "tfs".
        assertNull(handler.organizationFromResourceContainers(Map.of(
                "resourceContainers", Map.of(
                        "collection", Map.of("baseUrl", "https://tfs.example.com/tfs/"),
                        "server", Map.of("baseUrl", "https://tfs.example.com/tfs")))));
    }

    // ---- (b') organizationFromCollectionBaseUrl(String) — the url forms themselves ----

    @Test
    void organizationFromCollectionBaseUrl_modernForm_returnsLastPathSegment() {
        assertEquals("fabrikam", handler.organizationFromCollectionBaseUrl(
                "https://dev.azure.com/fabrikam/"));
        assertEquals("fabrikam", handler.organizationFromCollectionBaseUrl(
                "https://dev.azure.com/fabrikam"));
    }

    @Test
    void organizationFromCollectionBaseUrl_serverFormBehindVirtualDirectories_returnsCollection() {
        // Azure DevOps Server can sit behind any number of virtual-directory segments, so
        // the collection is the last segment rather than one at a fixed depth.
        assertEquals("DefaultCollection", handler.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/tfs/DefaultCollection/"));
        assertEquals("DefaultCollection", handler.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/DefaultCollection/"));
        assertEquals("ProjectCollection", handler.organizationFromCollectionBaseUrl(
                "https://tfs.example.com/a/b/c/ProjectCollection/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_legacyForm_returnsHostLabel() {
        // The legacy collection base url has an empty path.
        assertEquals("fabrikam", handler.organizationFromCollectionBaseUrl(
                "https://fabrikam.visualstudio.com/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_legacyHostWins_overItsCollectionSegment() {
        // A legacy base url may also carry a collection segment, but the organization is
        // the host label — resolving the collection instead would address the wrong one.
        assertEquals("fabrikam", handler.organizationFromCollectionBaseUrl(
                "https://fabrikam.visualstudio.com/DefaultCollection/"));
    }

    @Test
    void organizationFromCollectionBaseUrl_unusableUrl_returnsNull() {
        assertNull(handler.organizationFromCollectionBaseUrl(null));
        assertNull(handler.organizationFromCollectionBaseUrl(""));
        assertNull(handler.organizationFromCollectionBaseUrl("not a url"));
        // Nothing to read: no path segment, and no legacy host label to fall back to.
        assertNull(handler.organizationFromCollectionBaseUrl("https://tfs.example.com/"));
    }

    private static Map<String, Object> pullRequestPayloadWithHeadSha(String eventType,
                                                                     String sha) {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload(eventType));
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("lastMergeSourceCommit", Map.of("commitId", sha));
        raw.put("resource", resource);
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pullRequestPayloadWithReviewers(String eventType,
                                                                       String sha,
                                                                       String... uniqueNames) {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayloadWithHeadSha(eventType, sha));
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("reviewers", java.util.Arrays.stream(uniqueNames)
                .map(name -> Map.of("uniqueName", (Object) name))
                .toList());
        raw.put("resource", resource);
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pullRequestPayloadWithoutReviewers(String eventType,
                                                                          String sha) {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayloadWithHeadSha(eventType, sha));
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.remove("reviewers");
        raw.put("resource", resource);
        return raw;
    }

    private static Map<String, Object> pullRequestPayloadWithStatus(String eventType,
                                                                    String status) {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload(eventType));
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("status", status);
        raw.put("resource", resource);
        return raw;
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
