package org.remus.giteabot.gitlab;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** The platform of a GitLab integration. */
public class GitLabPlatform extends RepositoryPlatform {

    public GitLabPlatform(String baseUrl, String cloneUrl,
                          HttpCredentials httpCredentials,
                          @Nullable Supplier<SshCredentials> sshCredentials,
                          GitLabApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /** GitLab references merge requests as {@code !N}. */
    @Override
    public String formatPullRequestReference(Long prNumber) {
        return "!" + prNumber;
    }

    @Override
    public Optional<String> getPullRequestWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/-/merge_requests/" + number);
    }

    @Override
    public Optional<String> getIssueWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/-/issues/" + number);
    }

    /** Conversation and inline comments are both notes; GitLab has no review bodies. */
    @Override
    protected Optional<String> commentAnchor(PullRequestCommentKind kind, String commentId) {
        if (!isNumericId(commentId)) {
            return Optional.empty();
        }
        return switch (kind) {
            case PR_COMMENT, INLINE_COMMENT -> Optional.of("#note_" + commentId);
            case REVIEW -> Optional.empty();
        };
    }
}
