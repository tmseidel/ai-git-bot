package org.remus.giteabot.github;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** The platform of a GitHub integration. */
public class GitHubPlatform extends RepositoryPlatform {

    public GitHubPlatform(String baseUrl, String cloneUrl,
                          HttpCredentials httpCredentials,
                          @Nullable Supplier<SshCredentials> sshCredentials,
                          GitHubApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /** A pull request is an issue on GitHub and takes its number. */
    @Override
    public boolean sharesIssueAndPullRequestNumbers() {
        return true;
    }

    @Override
    public Optional<String> getPullRequestWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/pull/" + number);
    }

    @Override
    protected Optional<String> commentAnchor(PullRequestCommentKind kind, String commentId) {
        if (!isNumericId(commentId)) {
            return Optional.empty();
        }
        return Optional.of(switch (kind) {
            case PR_COMMENT -> "#issuecomment-";
            case INLINE_COMMENT -> "#discussion_r";
            case REVIEW -> "#pullrequestreview-";
        } + commentId);
    }
}
