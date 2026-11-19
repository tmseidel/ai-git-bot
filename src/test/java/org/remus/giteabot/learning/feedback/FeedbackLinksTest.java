package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryType;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackLinksTest {

    @Test
    void pullRequestLinksFollowEachProvidersPath() {
        assertThat(FeedbackLinks.of(RepositoryType.GITEA, "https://git.example/", "acme", "web")
                .pullRequest(5L)).isEqualTo("https://git.example/acme/web/pulls/5");
        assertThat(FeedbackLinks.of(RepositoryType.GITHUB, "https://github.com", "acme", "web")
                .pullRequest(5L)).isEqualTo("https://github.com/acme/web/pull/5");
        assertThat(FeedbackLinks.of(RepositoryType.GITLAB, "https://gitlab.com", "group/sub", "web")
                .pullRequest(5L)).isEqualTo("https://gitlab.com/group/sub/web/-/merge_requests/5");
        assertThat(FeedbackLinks.of(RepositoryType.BITBUCKET, "https://bitbucket.org", "acme", "web")
                .pullRequest(5L)).isEqualTo("https://bitbucket.org/acme/web/pull-requests/5");
    }

    @Test
    void commentAnchorsAreOnlyBuiltWhereTheyAreKnown() {
        FeedbackLinks github = FeedbackLinks.of(RepositoryType.GITHUB, "https://github.com", "acme", "web");
        assertThat(github.comment(5L, "PR_COMMENT", "11"))
                .isEqualTo("https://github.com/acme/web/pull/5#issuecomment-11");
        assertThat(github.comment(5L, "INLINE_COMMENT", "11"))
                .isEqualTo("https://github.com/acme/web/pull/5#discussion_r11");
        assertThat(github.comment(5L, "REVIEW", "11"))
                .isEqualTo("https://github.com/acme/web/pull/5#pullrequestreview-11");

        FeedbackLinks gitea = FeedbackLinks.of(RepositoryType.GITEA, "https://git.example", "acme", "web");
        assertThat(gitea.comment(5L, "REVIEW", "11")).isNull();
        assertThat(gitea.comment(5L, "PR_COMMENT", "thread:11")).isNull();
    }

    @Test
    void azureDevOpsAndIncompleteCoordinatesHaveNoLinks() {
        assertThat(FeedbackLinks.of(RepositoryType.AZURE_DEVOPS, "https://dev.azure.com", "org/proj",
                "web").pullRequest(5L)).isNull();
        assertThat(FeedbackLinks.of(RepositoryType.GITHUB, null, "acme", "web").pullRequest(5L)).isNull();
        assertThat(FeedbackLinks.NONE.comment(5L, "PR_COMMENT", "1")).isNull();
    }
}
