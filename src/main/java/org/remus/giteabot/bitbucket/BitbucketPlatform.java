package org.remus.giteabot.bitbucket;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** The platform of a Bitbucket Cloud integration. */
public class BitbucketPlatform extends RepositoryPlatform {

    public BitbucketPlatform(String baseUrl, String cloneUrl,
                             HttpCredentials httpCredentials,
                             @Nullable Supplier<SshCredentials> sshCredentials,
                             BitbucketApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /**
     * Bitbucket mentions are picked via the {@code @} autocomplete, which searches by
     * display name, so that name (or the nickname) is what users should type.
     */
    @Override
    public Optional<String> getBotMentionName() {
        return Optional.ofNullable(getUser(null).displayName());
    }

    @Override
    public Optional<String> getPullRequestWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/pull-requests/" + number);
    }

    /** Bitbucket has no review bodies. */
    @Override
    protected Optional<String> commentAnchor(PullRequestCommentKind kind, String commentId) {
        if (!isNumericId(commentId)) {
            return Optional.empty();
        }
        return switch (kind) {
            case PR_COMMENT, INLINE_COMMENT -> Optional.of("#comment-" + commentId);
            case REVIEW -> Optional.empty();
        };
    }
}
