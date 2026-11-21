package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackCursorTest {

    private static final FeedbackScope SCOPE = new FeedbackScope(1L, 2L, "acme", "web", 9L);

    @Test
    void aCursorRoundTripsInsideItsListing() {
        String cursor = FeedbackCursor.encode(SCOPE, false, 123L);

        assertThat(cursor).doesNotContain("123");
        assertThat(FeedbackCursor.decode(cursor, SCOPE, false)).isEqualTo(123L);
        assertThat(FeedbackCursor.decode(cursor, new FeedbackScope(1L, 2L, "ACME", "Web", 9L), false))
                .isEqualTo(123L);
    }

    @Test
    void aCursorIsBoundToBotRepositoryPullRequestAndListingKind() {
        String cursor = FeedbackCursor.encode(SCOPE, false, 123L);

        for (FeedbackScope other : new FeedbackScope[] {
                new FeedbackScope(1L, 3L, "acme", "web", 9L),
                new FeedbackScope(4L, 2L, "acme", "web", 9L),
                new FeedbackScope(1L, 2L, "acme", "api", 9L),
                new FeedbackScope(1L, 2L, "acme", "web", 10L)}) {
            assertThatThrownBy(() -> FeedbackCursor.decode(cursor, other, false))
                    .isInstanceOf(FeedbackRejectedException.class);
        }
        assertThatThrownBy(() -> FeedbackCursor.decode(cursor, SCOPE, true))
                .isInstanceOf(FeedbackRejectedException.class);
    }

    @Test
    void aRepositoryCursorIgnoresThePullRequestItWasRequestedFrom() {
        String cursor = FeedbackCursor.encode(SCOPE, true, 5L);

        assertThat(FeedbackCursor.decode(cursor, new FeedbackScope(1L, 2L, "acme", "web", 77L), true))
                .isEqualTo(5L);
    }

    @Test
    void garbageIsRefused() {
        assertThatThrownBy(() -> FeedbackCursor.decode("42", SCOPE, false))
                .isInstanceOf(FeedbackRejectedException.class);
        assertThatThrownBy(() -> FeedbackCursor.decode("%%%", SCOPE, false))
                .isInstanceOf(FeedbackRejectedException.class);
    }
}
