package org.remus.giteabot.learning.feedback;

import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryPlatform;

/**
 * Browser links from feedback overviews back to the pull request and the comment that submitted
 * the feedback, for the one repository an overview covers. The URLs themselves are the
 * {@link RepositoryPlatform}'s; this only binds them to the repository and renders a missing link
 * as {@code null}, which the overview prints as plain text.
 */
public final class FeedbackLinks {

    public static final FeedbackLinks NONE = new FeedbackLinks(null, null, null);

    private final RepositoryPlatform platform;
    private final String owner;
    private final String repo;

    private FeedbackLinks(RepositoryPlatform platform, String owner, String repo) {
        this.platform = platform;
        this.owner = owner;
        this.repo = repo;
    }

    public static FeedbackLinks of(RepositoryPlatform platform, String owner, String repo) {
        return platform == null ? NONE : new FeedbackLinks(platform, owner, repo);
    }

    /** {@code null} when no link can be built. */
    public String pullRequest(Long number) {
        return platform == null ? null
                : platform.getPullRequestWebUrl(owner, repo, number).orElse(null);
    }

    /**
     * {@code null} when the provider's anchor for this comment kind is not known.
     *
     * @param commentKind the persisted {@link PullRequestCommentKind} name
     */
    public String comment(Long prNumber, String commentKind, String commentId) {
        return platform == null ? null
                : platform.getCommentWebUrl(owner, repo, prNumber,
                        PullRequestCommentKind.fromName(commentKind), commentId).orElse(null);
    }
}
