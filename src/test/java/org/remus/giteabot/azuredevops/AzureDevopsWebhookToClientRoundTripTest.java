package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Walks the production path end to end: a realistic Azure DevOps Service Hook payload
 * goes through {@link AzureDevopsWebhookHandler#translatePayload}, then {@code owner} and
 * {@code repo} are pulled out the way every consumer does it —
 * {@code payload.getRepository().getOwner().getLogin()} and
 * {@code payload.getRepository().getName()} — and fed into
 * {@link AzureDevopsAddress#parse(String, String)}, the same call
 * {@code RepositoryApiClient} implementations make on every request.
 * <p>
 * This is the one seam where the two halves of the addressing convention have to agree:
 * {@code repo} must arrive as {@code "Project/Repository"} through {@code getName()}, and
 * the organization must come from the payload rather than {@code GitIntegration.url}. The
 * translation and address-parsing layers are otherwise tested in isolation, where a
 * mismatch between them stays invisible — here it surfaces as an
 * {@link IllegalArgumentException} from {@code parse}.
 */
class AzureDevopsWebhookToClientRoundTripTest {

    private final AzureDevopsWebhookHandler handler = new AzureDevopsWebhookHandler(null, null);

    /**
     * The Service Hook envelope's {@code resourceContainers}, which is where the
     * organization is resolved from: {@code collection.baseUrl} ends at the collection
     * whatever the deployment, while every {@code url} inside {@code resource} is
     * project-scoped and so names the project id instead.
     */
    private static final Map<String, Object> RESOURCE_CONTAINERS = Map.of(
            "collection", Map.of("id", "c12d0eb8-e382-443b-9f9c-c52cba5014c2",
                    "baseUrl", "https://dev.azure.com/fabrikam/"),
            "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "baseUrl", "https://dev.azure.com/fabrikam/"));

    @Test
    void pullRequestCreated_ownerAndRepoRoundTripToAValidAddress() {
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.created", pullRequestCreatedPayload());
        assertNotNull(payload, "translation must succeed for a well-formed payload");

        // Exactly what every real consumer does — see BotWebhookService, CodeReviewService,
        // AgentReviewService, etc. — before calling into RepositoryApiClient.
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);

        assertEquals("fabrikam", addr.organization());
        assertEquals("MyProject", addr.project());
        assertEquals("my-service", addr.name());
    }

    @Test
    void pullRequestCommentEvent_ownerAndRepoRoundTripToAValidAddress() {
        WebhookPayload payload = handler.translatePayload(
                "ms.vss-code.git-pullrequest-comment-event", commentPayload());
        assertNotNull(payload, "translation must succeed for a well-formed comment payload");

        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);

        assertEquals("fabrikam", addr.organization());
        assertEquals("MyProject", addr.project());
        assertEquals("my-service", addr.name());
    }

    @Test
    void onPremPullRequestCreated_resolvesTheCollectionRatherThanTheProjectId() {
        // Regression for the Azure DevOps Server shape, where the collection is a path
        // segment and every url inside resource is project-scoped. Reading the
        // organization from resource.repository.url took the segment before _apis and so
        // yielded the *project id*, producing ".../tfs/ce0eacb6-.../Demo2/_apis/..." — a
        // collection that does not exist, which the server rejects with 401 rather than
        // 404 because it resolves the collection before it authorizes.
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.created", onPremPullRequestCreatedPayload());
        assertNotNull(payload, "translation must succeed for a well-formed on-prem payload");

        AzureDevopsAddress addr = AzureDevopsAddress.parse(
                payload.getRepository().getOwner().getLogin(),
                payload.getRepository().getName());

        assertEquals("Experimental", addr.organization());
        assertEquals("Demo2", addr.project());
        assertEquals("JiraStopWatch", addr.name());
    }

    /**
     * Trimmed from a real Service Hook body off an Azure DevOps Server instance rooted at
     * {@code https://dmo-tfs.dataphone.ch/tfs}, collection {@code Experimental}, project
     * {@code Demo2}. The collection appears only in {@code resourceContainers} and in the
     * web/remote urls — never in a {@code _apis} url, all of which are project-scoped.
     */
    private static Map<String, Object> onPremPullRequestCreatedPayload() {
        return Map.of("eventType", "git.pullrequest.created",
                "resource", Map.ofEntries(
                        Map.entry("pullRequestId", 1),
                        Map.entry("title", "Add test file for ai reviews"),
                        Map.entry("description", "Add test file for reviews"),
                        Map.entry("status", "active"),
                        Map.entry("sourceRefName", "refs/heads/feature/INF-967-git-ai-bot"),
                        Map.entry("targetRefName", "refs/heads/main"),
                        Map.entry("lastMergeSourceCommit",
                                Map.of("commitId", "76373de36e7eef50cc6efe4b1f81734c06494ecc")),
                        Map.entry("lastMergeTargetCommit",
                                Map.of("commitId", "4b7576dc7f103e41a484ebfa6fb0fe48af96b35b")),
                        Map.entry("createdBy", Map.of("uniqueName", "DPH\\mpa",
                                "displayName", "Martin Panzer")),
                        Map.entry("reviewers", List.of()),
                        Map.entry("repository", Map.of(
                                "id", "0efbf1a3-81c6-4f84-8586-363ba113b887",
                                "name", "JiraStopWatch",
                                "url", "https://dmo-tfs.dataphone.ch/tfs/Experimental/"
                                        + "ce0eacb6-6bf0-4a90-946c-89b6201c2457/_apis/git/"
                                        + "repositories/0efbf1a3-81c6-4f84-8586-363ba113b887",
                                "remoteUrl", "https://dmo-tfs.dataphone.ch/tfs/Experimental/"
                                        + "Demo2/_git/JiraStopWatch",
                                "project", Map.of("id", "ce0eacb6-6bf0-4a90-946c-89b6201c2457",
                                        "name", "Demo2")))),
                "resourceContainers", Map.of(
                        "collection", Map.of("id", "c1d373ed-385a-4447-be66-3ef95bb92c08",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/Experimental/"),
                        "server", Map.of("id", "a3448944-3192-47c6-99e4-13c689ae00d4",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/"),
                        "project", Map.of("id", "ce0eacb6-6bf0-4a90-946c-89b6201c2457",
                                "baseUrl", "https://dmo-tfs.dataphone.ch/tfs/Experimental/")));
    }

    /**
     * Realistic (trimmed) shape of a {@code git.pullrequest.created} Service Hook body,
     * with "Resource details to send = All" as the setup guide requires.
     */
    private static Map<String, Object> pullRequestCreatedPayload() {
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
                        Map.entry("createdBy", Map.of("uniqueName", "dev@fabrikam.com",
                                "displayName", "Dev")),
                        Map.entry("reviewers", List.of(Map.of("uniqueName", "bot@fabrikam.com",
                                "displayName", "Bot"))),
                        Map.entry("repository", Map.of(
                                "id", "11111111-2222-3333-4444-555555555555",
                                "name", "my-service",
                                // Project-scoped, the way Azure DevOps really sends it: the
                                // segment before _apis is the project id, not the collection.
                                "url", "https://dev.azure.com/fabrikam/"
                                        + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/git/"
                                        + "repositories/11111111-2222-3333-4444-555555555555",
                                "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                        "name", "MyProject")))),
                "resourceContainers", RESOURCE_CONTAINERS);
    }

    /**
     * Realistic shape of a {@code ms.vss-code.git-pullrequest-comment-event} body — the
     * repository lives under {@code resource.pullRequest.repository}, not
     * {@code resource.repository} — while the organization comes from the envelope's
     * {@code resourceContainers} either way, which is what makes the two event families
     * agree on it.
     */
    private static Map<String, Object> commentPayload() {
        return Map.of("eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot please review",
                                "commentType", "text",
                                "author", Map.of("uniqueName", "dev@fabrikam.com"),
                                "_links", Map.of("threads", Map.of("href",
                                        "https://dev.azure.com/fabrikam/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/git/"
                                                + "repositories/11111111-2222-3333-4444-555555555555/"
                                                + "pullRequests/42/threads/5"))),
                        "pullRequest", Map.of(
                                "pullRequestId", 42,
                                "title", "Add feature",
                                "sourceRefName", "refs/heads/feature-branch",
                                "targetRefName", "refs/heads/main",
                                "createdBy", Map.of("uniqueName", "dev@fabrikam.com"),
                                "repository", Map.of(
                                        "id", "11111111-2222-3333-4444-555555555555",
                                        "name", "my-service",
                                        "url", "https://dev.azure.com/fabrikam/"
                                                + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/_apis/git/"
                                                + "repositories/11111111-2222-3333-4444-555555555555",
                                        "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                                "name", "MyProject")))),
                "resourceContainers", RESOURCE_CONTAINERS);
    }
}
