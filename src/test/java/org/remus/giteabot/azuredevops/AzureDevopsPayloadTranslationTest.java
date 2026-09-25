package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Azure DevOps Service Hook → {@link WebhookPayload} translation.
 * Azure DevOps carries the event type in the body as {@code eventType}, not in a header.
 */
class AzureDevopsPayloadTranslationTest {

    private final AzureDevopsWebhookHandler handler = new AzureDevopsWebhookHandler(null, null);

    @Test
    void translatePullRequestCreated_mapsAllFields() {
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.created", pullRequestPayload());

        assertNotNull(payload);
        assertEquals("opened", payload.getAction());
        assertEquals("dev@contoso.com", payload.getSender().getLogin());
        // Every consumer reads getName() to build the repo argument passed into
        // RepositoryApiClient, so it must carry the "Project/Repository" form —
        // not the bare repository name. Setting only fullName leaves repo bare and
        // AzureDevopsAddress.parse then throws on every webhook-triggered call.
        assertEquals("MyProject/my-service", payload.getRepository().getName());
        assertEquals("MyProject/my-service", payload.getRepository().getFullName());
        assertEquals("contoso", payload.getRepository().getOwner().getLogin());
        // id and number are wired from the same source (pullRequestId) but are
        // distinct fields on the model; assert both explicitly so a swap or a
        // dropped wiring for either one is caught rather than passing silently.
        assertEquals(42L, payload.getPullRequest().getId());
        assertEquals(42L, payload.getPullRequest().getNumber());
        assertEquals("Add feature", payload.getPullRequest().getTitle());
        assertEquals("does things", payload.getPullRequest().getBody());
        assertEquals("active", payload.getPullRequest().getState());
        assertFalse(payload.getPullRequest().getMerged());
        assertEquals("dev@contoso.com", payload.getPullRequest().getUser().getLogin());
        assertEquals("feature-branch", payload.getPullRequest().getHead().getRef());
        assertEquals("head-sha", payload.getPullRequest().getHead().getSha());
        assertEquals("main", payload.getPullRequest().getBase().getRef());
        assertEquals("base-sha", payload.getPullRequest().getBase().getSha());
    }

    @Test
    void translatePullRequestUpdated_whileActive_mapsToSynchronized() {
        // pullRequestPayload() has status "active"
        assertEquals("synchronized", handler.translatePayload(
                "git.pullrequest.updated", pullRequestPayload()).getAction());
    }

    @Test
    void translatePullRequestUpdated_whenCompleted_mapsToClosed() {
        // Completion arrives as an "updated" event with status completed — this is the
        // only close signal Azure DevOps sends.
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.updated", pullRequestPayloadWithStatus("completed"));
        assertEquals("closed", payload.getAction());
        assertTrue(payload.getPullRequest().getMerged());
    }

    @Test
    void translatePullRequestUpdated_whenAbandoned_mapsToClosed() {
        assertEquals("closed", handler.translatePayload(
                "git.pullrequest.updated", pullRequestPayloadWithStatus("abandoned"))
                .getAction());
    }

    @Test
    void translatePullRequestUpdated_leavesSenderUnsetBecauseCreatedByIsTheAuthor() {
        // resource.createdBy is the pull request's author; Azure DevOps sends no actor
        // on pull request events. Carrying the author over as the sender would make
        // BotWebhookService#isBotUser drop every update on a pull request the bot itself
        // opened, so a human pushing to the bot's own PR would never get a re-review.
        // The PR author is still available on pullRequest.user, where it belongs.
        WebhookPayload updated = handler.translatePayload(
                "git.pullrequest.updated", pullRequestPayload());
        assertNull(updated.getSender());
        assertEquals("dev@contoso.com", updated.getPullRequest().getUser().getLogin());

        WebhookPayload closed = handler.translatePayload(
                "git.pullrequest.updated", pullRequestPayloadWithStatus("completed"));
        assertNull(closed.getSender());

        // On creation the author and the actor are the same user, so it is set there.
        assertEquals("dev@contoso.com", handler.translatePayload(
                "git.pullrequest.created", pullRequestPayload()).getSender().getLogin());
    }

    @Test
    void translatePullRequestMerged_isIgnored() {
        // "git.pullrequest.merged" is Azure DevOps's "merge attempted" event: it fires
        // when the preview merge commit is built, including at PR creation. Treating it
        // as a close would end the bot's session moments after every PR is opened.
        assertNull(handler.translatePayload(
                "git.pullrequest.merged", pullRequestPayload()));
    }

    @Test
    void translateReviewers_mapsUniqueNames() {
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.created", pullRequestPayload());

        assertEquals(1, payload.getPullRequest().getRequestedReviewers().size());
        assertEquals("bot@contoso.com",
                payload.getPullRequest().getRequestedReviewers().get(0).getLogin());
    }

    @Test
    void translateComment_mapsBody() {
        WebhookPayload payload = handler.translatePayload(
                "ms.vss-code.git-pullrequest-comment-event", commentPayload());

        assertNotNull(payload);
        assertEquals("created", payload.getAction());
        assertEquals(7L, payload.getComment().getId());
        assertEquals("@bot please review", payload.getComment().getBody());
        assertEquals(42L, payload.getPullRequest().getNumber());
        assertNotNull(payload.getIssue(), "comment events need a synthetic issue");
        assertEquals(42L, payload.getIssue().getNumber());
        assertNotNull(payload.getIssue().getPullRequest());
    }

    @Test
    void translateComment_leavesFileAnchorUnset() {
        // Azure DevOps hangs threadContext off the thread, not the comment, and the
        // comment event payload carries only the comment. Translation therefore cannot
        // know the file anchor; hydrateInlineThreadContext fetches it separately.
        WebhookPayload payload = handler.translatePayload(
                "ms.vss-code.git-pullrequest-comment-event", commentPayload());

        assertNotNull(payload);
        assertNull(payload.getComment().getPath());
        assertNull(payload.getComment().getLine());
    }

    @Test
    void threadIdFromComment_readsThreadsLink() {
        assertEquals(5L, handler.threadIdFromComment(commentPayload()));
    }

    @Test
    void threadIdFromComment_fallsBackToSelfLink() {
        Map<String, Object> raw = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of("comment", Map.of("id", 7,
                        "_links", Map.of("self", Map.of("href",
                                "https://dev.azure.com/contoso/_apis/git/repositories/r/"
                                        + "pullRequests/42/threads/9/comments/7")))));

        assertEquals(9L, handler.threadIdFromComment(raw));
    }

    @Test
    void threadIdFromComment_returnsNullWithoutLinks() {
        Map<String, Object> raw = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of("comment", Map.of("id", 7)));

        assertNull(handler.threadIdFromComment(raw));
    }

    @Test
    void translateComment_withoutPullRequest_returnsNull() {
        // A malformed comment payload missing resource.pullRequest must be ignored
        // rather than produce a payload with null pull request/repository/number/
        // issue fields — see AzureDevopsWebhookHandler#translateCommentEvent.
        Map<String, Object> raw = Map.of(
                "eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot please review",
                                "author", Map.of("uniqueName", "dev@contoso.com"))));

        assertNull(handler.translatePayload(
                "ms.vss-code.git-pullrequest-comment-event", raw));
    }

    @Test
    void translateUnknownEventType_returnsNull() {
        assertNull(handler.translatePayload("git.push", pullRequestPayload()));
    }

    @Test
    void translatePullRequestCreated_missingResourceContainers_returnsNull() {
        // The organization is resolved from resourceContainers.collection.baseUrl, never
        // from GitIntegration.url (the instance root, which carries no organization) and
        // never from a resource url (which is project-scoped). With the envelope's
        // containers absent, translation must fail rather than guess.
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload());
        raw.remove("resourceContainers");

        assertNull(handler.translatePayload("git.pullrequest.created", raw));
    }

    @Test
    void translatePullRequestCreated_repositoryUrlAlone_isNotEnough() {
        // Guards the regression directly: resource.repository.url is project-scoped, so
        // the segment before _apis is the project id. It must not be used as a fallback —
        // addressing a non-existent collection fails as an opaque 401.
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload());
        raw.remove("resourceContainers");
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("repository", Map.of("name", "my-service",
                "project", Map.of("name", "MyProject"),
                "url", "https://tfs.example.com/tfs/DefaultCollection/"
                        + "ce0eacb6-6bf0-4a90-946c-89b6201c2457/_apis/git/repositories/my-service"));
        raw.put("resource", resource);

        assertNull(handler.translatePayload("git.pullrequest.created", raw));
    }

    @Test
    void translatePullRequestCreated_missingProjectName_returnsNull() {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload());
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("repository", Map.of("name", "my-service",
                "url", "https://dev.azure.com/contoso/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/"
                        + "_apis/git/repositories/my-service"));
        raw.put("resource", resource);

        assertNull(handler.translatePayload("git.pullrequest.created", raw));
    }

    /**
     * The Service Hook envelope's {@code resourceContainers}. The organization is resolved
     * from {@code collection.baseUrl}, which ends at the collection by definition.
     */
    private static final Map<String, Object> RESOURCE_CONTAINERS = Map.of(
            "collection", Map.of("id", "c12d0eb8-e382-443b-9f9c-c52cba5014c2",
                    "baseUrl", "https://dev.azure.com/contoso/"),
            "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "baseUrl", "https://dev.azure.com/contoso/"));

    private static Map<String, Object> pullRequestPayloadWithStatus(String status) {
        Map<String, Object> raw = new java.util.HashMap<>(pullRequestPayload());
        Map<String, Object> resource =
                new java.util.HashMap<>((Map<String, Object>) raw.get("resource"));
        resource.put("status", status);
        raw.put("resource", resource);
        return raw;
    }

    private static Map<String, Object> pullRequestPayload() {
        // Map.of() tops out at 10 key-value pairs; this resource needs 11, so
        // Map.ofEntries is used instead. Same keys/values as a Map.of(...) call.
        return Map.of("eventType", "git.pullrequest.created",
                "resource", Map.ofEntries(
                        Map.entry("pullRequestId", 42),
                        Map.entry("title", "Add feature"),
                        Map.entry("description", "does things"),
                        Map.entry("status", "active"),
                        Map.entry("sourceRefName", "refs/heads/feature-branch"),
                        Map.entry("targetRefName", "refs/heads/main"),
                        Map.entry("lastMergeSourceCommit", Map.of("commitId", "head-sha")),
                        Map.entry("lastMergeTargetCommit", Map.of("commitId", "base-sha")),
                        Map.entry("createdBy", Map.of("uniqueName", "dev@contoso.com",
                                "displayName", "Dev")),
                        Map.entry("reviewers", List.of(Map.of("uniqueName", "bot@contoso.com",
                                "displayName", "Bot"))),
                        Map.entry("repository", Map.of("name", "my-service",
                                "project", Map.of("name", "MyProject"),
                                // Project-scoped, as Azure DevOps sends it: the segment
                                // before _apis is the project id, not the collection.
                                "url", "https://dev.azure.com/contoso/"
                                        + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/git/"
                                        + "repositories/my-service"))),
                "resourceContainers", RESOURCE_CONTAINERS);
    }

    /**
     * Shaped after the published sample for {@code ms.vss-code.git-pullrequest-comment-event}
     * (Microsoft Learn, "Service hook events" → "Pull request commented on"): the comment
     * carries {@code id}, {@code author}, {@code content}, {@code commentType} and
     * {@code _links} — and no {@code threadContext}.
     */
    private static Map<String, Object> commentPayload() {
        return Map.of("eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "parentCommentId", 1,
                                "content", "@bot please review",
                                "commentType", "text",
                                "author", Map.of("uniqueName", "dev@contoso.com"),
                                "_links", Map.of(
                                        "self", Map.of("href",
                                                "https://dev.azure.com/contoso/_apis/git/repositories/"
                                                        + "my-service/pullRequests/42/threads/5/comments/7"),
                                        "threads", Map.of("href",
                                                "https://dev.azure.com/contoso/_apis/git/repositories/"
                                                        + "my-service/pullRequests/42/threads/5"))),
                        "pullRequest", Map.of(
                                "pullRequestId", 42,
                                "title", "Add feature",
                                "sourceRefName", "refs/heads/feature-branch",
                                "targetRefName", "refs/heads/main",
                                "createdBy", Map.of("uniqueName", "dev@contoso.com"),
                                "repository", Map.of("name", "my-service",
                                        "project", Map.of("name", "MyProject"),
                                        "url", "https://dev.azure.com/contoso/"
                                                + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/"
                                                + "git/repositories/my-service"))),
                "resourceContainers", RESOURCE_CONTAINERS);
    }
}
