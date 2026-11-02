package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceReviewRevisionTest {
    @Test
    void pinsOnlyTheCapturedCommitAndVerifiesTheActualHead() {
        var service = spy(new WorkspaceService(null, 60));
        Path path = Path.of("workspace");
        String sha = "a".repeat(40);
        doReturn(new CommandResult(true, "detached"))
                .when(service).runCommand(path.toFile(), new String[]{"git", "checkout", "--detach", sha}, 60);
        doReturn(new CommandResult(true, sha + "\n"),
                new CommandResult(true, "b".repeat(40)))
                .when(service).runCommand(path.toFile(), new String[]{"git", "rev-parse", "--verify", "HEAD"}, 15);
        assertThat(service.pinReviewRevision(path, sha)).isTrue();
        assertThat(service.isReviewRevision(path, sha)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> service.pinReviewRevision(path, "--malicious"));
    }
}
