package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrPayloadHydratorTest {

    private static final PullRequestDetails DETAILS = new PullRequestDetails(
            "Add login", "Adds the login page", PullRequestState.OPEN, "feature/login", "abc123", "main", "def456");

    private final RepositoryApiClient client = mock(RepositoryApiClient.class);

    // ---- hydrate ----

    @Test
    void hydrate_payloadWithHeadRef_doesNotRequestClient() {
        WebhookPayload payload = issueCommentPayload(42L);
        payload.setPullRequest(pullRequestWithHead("feature/existing"));
        @SuppressWarnings("unchecked")
        Supplier<RepositoryApiClient> supplier = mock(Supplier.class);

        PrPayloadHydrator.hydrate(payload, supplier);

        verify(supplier, never()).get();
        assertThat(payload.getPullRequest().getHead().getRef()).isEqualTo("feature/existing");
    }

    @Test
    void hydrate_missingRepositoryOwner_leavesPayloadUntouched() {
        WebhookPayload payload = issueCommentPayload(42L);
        payload.getRepository().setOwner(null);

        PrPayloadHydrator.hydrate(payload, () -> client);

        verify(client, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
        assertThat(payload.getPullRequest()).isNull();
    }

    @Test
    void hydrate_noPrNumber_leavesPayloadUntouched() {
        WebhookPayload payload = issueCommentPayload(null);

        PrPayloadHydrator.hydrate(payload, () -> client);

        verify(client, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
        assertThat(payload.getPullRequest()).isNull();
    }

    @Test
    void hydrate_issueCommentPayload_createsPullRequestFromDetails() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenReturn(Optional.of(DETAILS));

        PrPayloadHydrator.hydrate(payload, () -> client);

        WebhookPayload.PullRequest pr = payload.getPullRequest();
        assertThat(pr.getNumber()).isEqualTo(42L);
        assertThat(pr.getTitle()).isEqualTo("Add login");
        assertThat(pr.getBody()).isEqualTo("Adds the login page");
        assertThat(pr.getState()).isEqualTo("open");
        assertThat(pr.getHead().getRef()).isEqualTo("feature/login");
        assertThat(pr.getHead().getSha()).isEqualTo("abc123");
        assertThat(pr.getBase().getRef()).isEqualTo("main");
        assertThat(pr.getBase().getSha()).isEqualTo("def456");
    }

    @Test
    void hydrate_mergedPullRequest_mapsToClosedAndMerged() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenReturn(Optional.of(
                new PullRequestDetails("Add login", null, PullRequestState.MERGED, "feature/login", null, null, null)));

        PrPayloadHydrator.hydrate(payload, () -> client);

        assertThat(payload.getPullRequest().getState()).isEqualTo("closed");
        assertThat(payload.getPullRequest().getMerged()).isTrue();
    }

    @Test
    void hydrate_prNumberResolution_prefersPullRequestThenIssueThenTopLevel() {
        WebhookPayload fromPullRequest = issueCommentPayload(2L);
        fromPullRequest.setNumber(3L);
        WebhookPayload.PullRequest partial = new WebhookPayload.PullRequest();
        partial.setNumber(1L);
        fromPullRequest.setPullRequest(partial);
        WebhookPayload fromIssue = issueCommentPayload(2L);
        fromIssue.setNumber(3L);
        WebhookPayload fromTopLevel = issueCommentPayload(null);
        fromTopLevel.setNumber(3L);
        when(client.getPullRequestDetails(anyString(), anyString(), anyLong())).thenReturn(Optional.of(DETAILS));

        PrPayloadHydrator.hydrate(fromPullRequest, () -> client);
        PrPayloadHydrator.hydrate(fromIssue, () -> client);
        PrPayloadHydrator.hydrate(fromTopLevel, () -> client);

        verify(client).getPullRequestDetails("owner", "repo", 1L);
        verify(client).getPullRequestDetails("owner", "repo", 2L);
        verify(client).getPullRequestDetails("owner", "repo", 3L);
        assertThat(fromPullRequest.getPullRequest()).isSameAs(partial);
    }

    @Test
    void hydrate_providerWithoutDetails_leavesPayloadUntouched() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenReturn(Optional.empty());

        PrPayloadHydrator.hydrate(payload, () -> client);

        assertThat(payload.getPullRequest()).isNull();
    }

    @Test
    void hydrate_clientThrows_leavesPayloadUntouched() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenThrow(new IllegalStateException("boom"));

        PrPayloadHydrator.hydrate(payload, () -> client);

        assertThat(payload.getPullRequest()).isNull();
    }

    @Test
    void hydrate_clientSupplierThrows_leavesPayloadUntouched() {
        WebhookPayload payload = issueCommentPayload(42L);

        PrPayloadHydrator.hydrate(payload, () -> {
            throw new IllegalStateException("no integration");
        });

        assertThat(payload.getPullRequest()).isNull();
    }

    @Test
    void hydrate_detailsWithoutRefs_keepsExistingTitleAndStateAndSetsNoHeadOrBase() {
        WebhookPayload payload = issueCommentPayload(42L);
        WebhookPayload.PullRequest existing = new WebhookPayload.PullRequest();
        existing.setTitle("Webhook title");
        existing.setState("open");
        payload.setPullRequest(existing);
        when(client.getPullRequestDetails("owner", "repo", 42L))
                .thenReturn(Optional.of(new PullRequestDetails(null, "Body", null, null, null, null, null)));

        PrPayloadHydrator.hydrate(payload, () -> client);

        assertThat(existing.getTitle()).isEqualTo("Webhook title");
        assertThat(existing.getState()).isEqualTo("open");
        assertThat(existing.getBody()).isEqualTo("Body");
        assertThat(existing.getHead()).isNull();
        assertThat(existing.getBase()).isNull();
    }

    // ---- resolvePrNumber ----

    @Test
    void resolvePrNumber_noNumberAnywhere_returnsNull() {
        assertThat(PrPayloadHydrator.resolvePrNumber(issueCommentPayload(null))).isNull();
    }

    @Test
    void resolvePrNumberOrZero_noNumberAnywhere_returnsZero() {
        assertThat(PrPayloadHydrator.resolvePrNumberOrZero(issueCommentPayload(null))).isZero();
    }

    @Test
    void resolvePrNumberOrZero_issueNumber_isReturned() {
        assertThat(PrPayloadHydrator.resolvePrNumberOrZero(issueCommentPayload(42L))).isEqualTo(42L);
    }

    // ---- resolveHeadBranch ----

    @Test
    void resolveHeadBranch_payloadHeadRef_isNormalisedWithoutApiCall() {
        WebhookPayload payload = issueCommentPayload(42L);
        payload.setPullRequest(pullRequestWithHead("refs/heads/feature/login"));

        String branch = PrPayloadHydrator.resolveHeadBranch(client, payload, "owner", "repo", 42L);

        assertThat(branch).isEqualTo("feature/login");
        verify(client, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
    }

    @Test
    void resolveHeadBranch_missingHeadRef_fetchesFromProvider() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenReturn(Optional.of(DETAILS));

        assertThat(PrPayloadHydrator.resolveHeadBranch(client, payload, "owner", "repo", 42L))
                .isEqualTo("feature/login");
    }

    @Test
    void resolveHeadBranch_nonPositivePrNumber_returnsNullWithoutApiCall() {
        WebhookPayload payload = issueCommentPayload(null);

        assertThat(PrPayloadHydrator.resolveHeadBranch(client, payload, "owner", "repo", 0L)).isNull();
        verify(client, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
    }

    @Test
    void resolveHeadBranch_providerWithoutHeadRef_returnsNull() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L))
                .thenReturn(Optional.of(new PullRequestDetails("Add login", null, null, " ", null, null, null)));

        assertThat(PrPayloadHydrator.resolveHeadBranch(client, payload, "owner", "repo", 42L)).isNull();
    }

    @Test
    void resolveHeadBranch_clientThrows_returnsNull() {
        WebhookPayload payload = issueCommentPayload(42L);
        when(client.getPullRequestDetails("owner", "repo", 42L)).thenThrow(new IllegalStateException("boom"));

        assertThat(PrPayloadHydrator.resolveHeadBranch(client, payload, "owner", "repo", 42L)).isNull();
    }

    // ---- helpers ----

    /** A GitHub-style issue_comment payload: repository and issue, but no pull_request block. */
    private static WebhookPayload issueCommentPayload(Long issueNumber) {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("repo");
        repository.setOwner(owner);
        WebhookPayload payload = new WebhookPayload();
        payload.setRepository(repository);
        if (issueNumber != null) {
            WebhookPayload.Issue issue = new WebhookPayload.Issue();
            issue.setNumber(issueNumber);
            payload.setIssue(issue);
        }
        return payload;
    }

    private static WebhookPayload.PullRequest pullRequestWithHead(String ref) {
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef(ref);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setHead(head);
        return pr;
    }
}
