package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.gitlab.GitLabApiClient;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunStatusMappingTest {

    @Test
    void gitHubQueuedVariants() {
        assertThat(GitHubApiClient.mapGitHubStatus("queued", null)).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(GitHubApiClient.mapGitHubStatus("pending", null)).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(GitHubApiClient.mapGitHubStatus("waiting", null)).isEqualTo(WorkflowRunStatus.QUEUED);
    }

    @Test
    void gitHubInProgress() {
        assertThat(GitHubApiClient.mapGitHubStatus("in_progress", null))
                .isEqualTo(WorkflowRunStatus.IN_PROGRESS);
    }

    @Test
    void gitHubCompletedSuccessVsFailure() {
        assertThat(GitHubApiClient.mapGitHubStatus("completed", "success"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_SUCCESS);
        assertThat(GitHubApiClient.mapGitHubStatus("completed", "failure"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(GitHubApiClient.mapGitHubStatus("completed", "cancelled"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(GitHubApiClient.mapGitHubStatus("completed", null))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
    }

    @Test
    void gitHubUnknownStatusIsTreatedAsInProgress() {
        assertThat(GitHubApiClient.mapGitHubStatus("weird", null))
                .isEqualTo(WorkflowRunStatus.IN_PROGRESS);
        assertThat(GitHubApiClient.mapGitHubStatus(null, null))
                .isEqualTo(WorkflowRunStatus.NOT_FOUND);
    }

    @Test
    void gitLabStateMapping() {
        assertThat(GitLabApiClient.mapGitLabStatus("created")).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(GitLabApiClient.mapGitLabStatus("pending")).isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(GitLabApiClient.mapGitLabStatus("running")).isEqualTo(WorkflowRunStatus.IN_PROGRESS);
        assertThat(GitLabApiClient.mapGitLabStatus("success"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_SUCCESS);
        assertThat(GitLabApiClient.mapGitLabStatus("failed"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(GitLabApiClient.mapGitLabStatus("canceled"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(GitLabApiClient.mapGitLabStatus(null)).isEqualTo(WorkflowRunStatus.NOT_FOUND);
    }

    @Test
    void bitbucketStateMapping() {
        assertThat(BitbucketApiClient.mapBitbucketStatus("PENDING", null))
                .isEqualTo(WorkflowRunStatus.QUEUED);
        assertThat(BitbucketApiClient.mapBitbucketStatus("IN_PROGRESS", null))
                .isEqualTo(WorkflowRunStatus.IN_PROGRESS);
        assertThat(BitbucketApiClient.mapBitbucketStatus("COMPLETED", "SUCCESSFUL"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_SUCCESS);
        assertThat(BitbucketApiClient.mapBitbucketStatus("COMPLETED", "FAILED"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(BitbucketApiClient.mapBitbucketStatus("COMPLETED", "STOPPED"))
                .isEqualTo(WorkflowRunStatus.COMPLETED_FAILURE);
        assertThat(BitbucketApiClient.mapBitbucketStatus(null, null))
                .isEqualTo(WorkflowRunStatus.NOT_FOUND);
    }

    @Test
    void terminalFlagMatchesEnum() {
        assertThat(WorkflowRunStatus.COMPLETED_SUCCESS.isTerminal()).isTrue();
        assertThat(WorkflowRunStatus.COMPLETED_FAILURE.isTerminal()).isTrue();
        assertThat(WorkflowRunStatus.NOT_FOUND.isTerminal()).isTrue();
        assertThat(WorkflowRunStatus.QUEUED.isTerminal()).isFalse();
        assertThat(WorkflowRunStatus.IN_PROGRESS.isTerminal()).isFalse();
    }
}

