package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.azuredevops.AzureDevopsApiClient;
import org.remus.giteabot.azuredevops.AzureDevopsPlatform;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.bitbucket.BitbucketPlatform;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.GiteaPlatform;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.github.GitHubPlatform;
import org.remus.giteabot.gitlab.GitLabApiClient;
import org.remus.giteabot.gitlab.GitLabPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.remus.giteabot.repository.PullRequestCommentKind.INLINE_COMMENT;
import static org.remus.giteabot.repository.PullRequestCommentKind.PR_COMMENT;
import static org.remus.giteabot.repository.PullRequestCommentKind.REVIEW;

/** Browser links to pull requests and their comments, per provider. */
class PullRequestWebUrlTest {

    private static final HttpCredentials CREDENTIALS = new HttpCredentials(null, "token");

    private static GiteaPlatform gitea(String cloneUrl) {
        return new GiteaPlatform(cloneUrl + "/api/v1", cloneUrl, CREDENTIALS, null,
                mock(GiteaApiClient.class), null, TestPlatforms.USER_TTL);
    }

    private static GitHubPlatform github(String cloneUrl) {
        return new GitHubPlatform("https://api.github.com", cloneUrl, CREDENTIALS, null,
                mock(GitHubApiClient.class), TestPlatforms.USER_TTL);
    }

    private static GitLabPlatform gitlab() {
        return new GitLabPlatform("https://gitlab.com/api/v4", "https://gitlab.com", CREDENTIALS, null,
                mock(GitLabApiClient.class), TestPlatforms.USER_TTL);
    }

    private static BitbucketPlatform bitbucket() {
        return new BitbucketPlatform("https://api.bitbucket.org/2.0", "https://bitbucket.org", CREDENTIALS,
                null, mock(BitbucketApiClient.class), TestPlatforms.USER_TTL);
    }

    private static AzureDevopsPlatform azure(String cloneUrl) {
        return new AzureDevopsPlatform("https://dev.azure.com", cloneUrl, CREDENTIALS, null,
                mock(AzureDevopsApiClient.class), TestPlatforms.USER_TTL);
    }

    @Test
    void pullRequestLinksFollowEachProvidersPath() {
        assertThat(gitea("https://git.example/").getPullRequestWebUrl("acme", "web", 5L))
                .contains("https://git.example/acme/web/pulls/5");
        assertThat(github("https://github.com").getPullRequestWebUrl("acme", "web", 5L))
                .contains("https://github.com/acme/web/pull/5");
        assertThat(gitlab().getPullRequestWebUrl("group/sub", "web", 5L))
                .contains("https://gitlab.com/group/sub/web/-/merge_requests/5");
        assertThat(bitbucket().getPullRequestWebUrl("acme", "web", 5L))
                .contains("https://bitbucket.org/acme/web/pull-requests/5");
    }

    @Test
    void issueLinksExistOnlyWhereTheIssuePageIsKnown() {
        assertThat(gitea("https://git.example/").getIssueWebUrl("acme", "web", 7L))
                .contains("https://git.example/acme/web/issues/7");
        assertThat(github("https://github.com").getIssueWebUrl("acme", "web", 7L))
                .contains("https://github.com/acme/web/issues/7");
        assertThat(gitlab().getIssueWebUrl("group/sub", "web", 7L))
                .contains("https://gitlab.com/group/sub/web/-/issues/7");
        assertThat(bitbucket().getIssueWebUrl("acme", "web", 7L)).isEmpty();
        assertThat(azure("https://dev.azure.com").getIssueWebUrl("contoso", "MyProject/my-service", 7L)).isEmpty();
        assertThat(github("https://github.com").getIssueWebUrl("acme", "web", null)).isEmpty();
    }

    @Test
    void azureDevOpsPullRequestsLiveBelowTheGitPage() {
        assertThat(azure("https://dev.azure.com").getPullRequestWebUrl("contoso", "MyProject/my-service", 5L))
                .contains("https://dev.azure.com/contoso/MyProject/_git/my-service/pullrequest/5");
        // A clone base already scoped to the organization does not repeat it.
        assertThat(azure("https://contoso.visualstudio.com")
                .getPullRequestWebUrl("contoso", "MyProject/my-service", 5L))
                .contains("https://contoso.visualstudio.com/MyProject/_git/my-service/pullrequest/5");
        assertThat(azure("https://dev.azure.com").getPullRequestWebUrl("contoso", "My Project/my-service", 5L))
                .contains("https://dev.azure.com/contoso/My%20Project/_git/my-service/pullrequest/5");
    }

    @Test
    void commentAnchorsAreOnlyBuiltWhereTheyAreKnown() {
        GitHubPlatform github = github("https://github.com");
        assertThat(github.getCommentWebUrl("acme", "web", 5L, PR_COMMENT, "11"))
                .contains("https://github.com/acme/web/pull/5#issuecomment-11");
        assertThat(github.getCommentWebUrl("acme", "web", 5L, INLINE_COMMENT, "11"))
                .contains("https://github.com/acme/web/pull/5#discussion_r11");
        assertThat(github.getCommentWebUrl("acme", "web", 5L, REVIEW, "11"))
                .contains("https://github.com/acme/web/pull/5#pullrequestreview-11");

        GiteaPlatform gitea = gitea("https://git.example");
        assertThat(gitea.getCommentWebUrl("acme", "web", 5L, INLINE_COMMENT, "11"))
                .contains("https://git.example/acme/web/pulls/5#issuecomment-11");
        assertThat(gitea.getCommentWebUrl("acme", "web", 5L, REVIEW, "11")).isEmpty();
        assertThat(gitea.getCommentWebUrl("acme", "web", 5L, PR_COMMENT, "thread:11")).isEmpty();
        assertThat(gitea.getCommentWebUrl("acme", "web", 5L, null, "11")).isEmpty();

        assertThat(gitlab().getCommentWebUrl("group", "web", 5L, PR_COMMENT, "11"))
                .contains("https://gitlab.com/group/web/-/merge_requests/5#note_11");
        assertThat(bitbucket().getCommentWebUrl("acme", "web", 5L, INLINE_COMMENT, "11"))
                .contains("https://bitbucket.org/acme/web/pull-requests/5#comment-11");
    }

    @Test
    void azureDevOpsAnchorsEveryCommentKindByItsThread() {
        AzureDevopsPlatform azure = azure("https://dev.azure.com");
        String pullRequest = "https://dev.azure.com/contoso/MyProject/_git/my-service/pullrequest/5";
        for (PullRequestCommentKind kind : PullRequestCommentKind.values()) {
            assertThat(azure.getCommentWebUrl("contoso", "MyProject/my-service", 5L, kind, "42/1"))
                    .contains(pullRequest + "#42");
        }
        assertThat(azure.getCommentWebUrl("contoso", "MyProject/my-service", 5L, PR_COMMENT, "x/1"))
                .isEmpty();
    }

    @Test
    void incompleteCoordinatesHaveNoLinks() {
        GitHubPlatform github = github("https://github.com");
        assertThat(github.getPullRequestWebUrl("acme", "web", null)).isEmpty();
        assertThat(github.getPullRequestWebUrl(null, "web", 5L)).isEmpty();
        assertThat(github.getCommentWebUrl("acme", "web", null, PR_COMMENT, "11")).isEmpty();
        assertThat(azure("https://dev.azure.com").getPullRequestWebUrl("contoso", "no-project", 5L))
                .isEmpty();
        // The default platform knows no pull request page.
        assertThat(TestPlatforms.of(mock(RepositoryApiClient.class)).getPullRequestWebUrl("acme", "web", 5L))
                .isEqualTo(Optional.empty());
    }

    @Test
    void aCredentialBearingCloneBaseYieldsNoLink() {
        assertThat(github("https://user:secret@github.com").getPullRequestWebUrl("acme", "web", 5L))
                .isEmpty();
    }
}
