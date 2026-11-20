package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.github.GitHubPlatform;
import org.remus.giteabot.repository.TestPlatforms;
import org.remus.giteabot.repository.model.HttpCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FeedbackLinksTest {

    private final FeedbackLinks github = FeedbackLinks.of(new GitHubPlatform("https://api.github.com",
            "https://github.com", new HttpCredentials(null, "token"), null,
            mock(GitHubApiClient.class), TestPlatforms.USER_TTL), "acme", "web");

    @Test
    void delegatesToThePlatformForTheBoundRepository() {
        assertThat(github.pullRequest(5L)).isEqualTo("https://github.com/acme/web/pull/5");
        // Comment kinds are read back from storage by name.
        assertThat(github.comment(5L, "inline_comment", "11"))
                .isEqualTo("https://github.com/acme/web/pull/5#discussion_r11");
    }

    @Test
    void missingLinksAreNull() {
        assertThat(github.comment(5L, "UNKNOWN", "11")).isNull();
        assertThat(FeedbackLinks.of(null, "acme", "web")).isSameAs(FeedbackLinks.NONE);
        assertThat(FeedbackLinks.NONE.pullRequest(5L)).isNull();
        assertThat(FeedbackLinks.NONE.comment(5L, "PR_COMMENT", "1")).isNull();
    }
}
