package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceReviewRevisionTest {
    @Test
    void pinsOnlyTheCapturedCommitAndVerifiesTheActualHead() {
        var service = spy(new WorkspaceService(null, 60));
        Path path = Path.of("workspace");
        String sha = "a".repeat(40);
        var client = mock(RepositoryApiClient.class);
        doReturn(WorkspaceResult.success(path)).when(service).preparePullRequestWorkspace(
                client, "o", "r", "main", 1L, PullRequestWorkspaceMode.READ_ONLY);
        doReturn(new CommandResult(true, ""))
                .when(service).runCommand(path.toFile(), new String[]{"git", "cat-file", "-e", sha + "^{commit}"}, 15);
        doReturn(new CommandResult(true, "detached"))
                .when(service).runCommand(path.toFile(), new String[]{"git", "checkout", "--detach", sha}, 60);
        doReturn(new CommandResult(true, sha + "\n"),
                new CommandResult(true, "b".repeat(40)))
                .when(service).runCommand(path.toFile(), new String[]{"git", "rev-parse", "--verify", "HEAD"}, 15);
        assertThat(service.preparePinnedPullRequestWorkspace(client, "o", "r", "main", 1L, sha).success()).isTrue();
        assertThat(service.isRevision(path, sha)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.preparePinnedPullRequestWorkspace(client, "o", "r", "main", 1L, "--malicious"));
        verify(service, never()).runRemoteCommand(any(), any(), anyInt(), any(String[].class));
    }

    @Test
    void checkoutExceptionCannotLeakThePreparedWorkspace() {
        var service = spy(new WorkspaceService(null, 60));
        var client = mock(RepositoryApiClient.class);
        Path path = Path.of("workspace");
        String sha = "a".repeat(40);
        doReturn(WorkspaceResult.success(path)).when(service).preparePullRequestWorkspace(
                client, "o", "r", "main", 1L, PullRequestWorkspaceMode.READ_ONLY);
        doThrow(new IllegalStateException("command failed")).when(service).runCommand(
                path.toFile(), new String[]{"git", "cat-file", "-e", sha + "^{commit}"}, 15);
        doNothing().when(service).cleanupWorkspace(path);
        assertThatIllegalStateException().isThrownBy(() ->
                service.preparePinnedPullRequestWorkspace(client, "o", "r", "main", 1L, sha));
        verify(service).cleanupWorkspace(path);
    }
}
