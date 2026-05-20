package org.remus.giteabot.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the race-safe correlation helper used by
 * {@link GitHubApiClient#dispatchWorkflow}. The same helper is also called
 * by {@code GiteaApiClient} (Gitea Actions reuses the GitHub Actions REST
 * shape), so these cases guard both providers against picking up an
 * unrelated concurrently-triggered run.
 *
 * <p>Background: GitHub's {@code POST .../dispatches} endpoint does not
 * return a run id, so the bot snapshots the existing run ids (filtered by
 * {@code event=workflow_dispatch} and optionally {@code branch}) and then
 * polls until a *new* matching id appears. Reliable correlation requires
 * the branch filter; non-branch refs (PR head refs, tags, raw SHAs) fall
 * back to "oldest new id" and log a WARN — documented behavior tested
 * below.</p>
 */
class GitHubApiClientDispatchCorrelationTest {

    @Test
    void deriveBranchFilterExtractsHeadsRef() {
        assertThat(GitHubApiClient.deriveBranchFilter("refs/heads/main")).isEqualTo("main");
        assertThat(GitHubApiClient.deriveBranchFilter("refs/heads/feature/x-y"))
                .isEqualTo("feature/x-y");
    }

    @Test
    void deriveBranchFilterAcceptsBareBranchName() {
        assertThat(GitHubApiClient.deriveBranchFilter("develop")).isEqualTo("develop");
        assertThat(GitHubApiClient.deriveBranchFilter("feature/X")).isEqualTo("feature/X");
    }

    @Test
    void deriveBranchFilterReturnsNullForPullRequestHeadRef() {
        // refs/pull/N/head cannot be reduced to a single branch on the runs
        // listing endpoint -> correlation falls back to "oldest new id" with
        // a WARN log; see GitHubApiClient.dispatchWorkflow comment block.
        assertThat(GitHubApiClient.deriveBranchFilter("refs/pull/123/head")).isNull();
        assertThat(GitHubApiClient.deriveBranchFilter("refs/pull/123/merge")).isNull();
    }

    @Test
    void deriveBranchFilterReturnsNullForTagRef() {
        assertThat(GitHubApiClient.deriveBranchFilter("refs/tags/v1.2.3")).isNull();
    }

    @Test
    void deriveBranchFilterReturnsNullForRawSha() {
        assertThat(GitHubApiClient.deriveBranchFilter("deadbeefcafe1234567890abcdef0123456789ab")).isNull();
        assertThat(GitHubApiClient.deriveBranchFilter("deadbee")).isNull(); // short SHA
    }

    @Test
    void deriveBranchFilterHandlesNullAndBlank() {
        assertThat(GitHubApiClient.deriveBranchFilter(null)).isNull();
        assertThat(GitHubApiClient.deriveBranchFilter("")).isNull();
        assertThat(GitHubApiClient.deriveBranchFilter("   ")).isNull();
    }
}

