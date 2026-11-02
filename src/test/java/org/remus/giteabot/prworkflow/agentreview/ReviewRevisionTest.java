package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewRevisionTest {
    private final RepositoryApiClient client = mock(RepositoryApiClient.class);

    @Test
    void capturesTheCompleteDiffAndRejectsRetargetingDuringCapture() {
        var a = details("a".repeat(40), "b".repeat(40));
        var retargeted = details(a.headSha(), "c".repeat(40));
        when(client.getPullRequestDetails("o", "r", 1L)).thenReturn(Optional.of(a), Optional.of(a),
                Optional.of(a), Optional.of(retargeted));
        when(client.getPullRequestDiff("o", "r", 1L)).thenReturn("é\n");
        var captured = ReviewRevision.capture(client, "o", "r", 1L);
        assertThat(captured.diff()).isEqualTo("é\n");
        assertThat(captured.revision().diffHash()).matches("sha256:[0-9a-f]{64}");
        assertThatIllegalStateException().isThrownBy(() -> ReviewRevision.capture(client, "o", "r", 1L));
    }

    @Test
    void missingAndMalformedRevisionsAreRejected() {
        assertThatIllegalStateException().isThrownBy(() -> ReviewRevision.capture(client, "o", "r", 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewRevision("abc", "b".repeat(40), "x"));
    }

    private static PullRequestDetails details(String head, String base) {
        return new PullRequestDetails("PR", null, PullRequestState.OPEN, "feature", head, "main", base);
    }
}
