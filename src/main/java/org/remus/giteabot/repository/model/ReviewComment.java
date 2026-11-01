package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic interface for a pull request review comment.
 * Implementations exist for Gitea ({@link org.remus.giteabot.gitea.model.GiteaReviewComment}),
 * GitHub ({@link org.remus.giteabot.github.model.GitHubReviewComment}), and Bitbucket Cloud
 * ({@link org.remus.giteabot.bitbucket.model.BitbucketReviewComment}),
 * with future support for GitLab, etc.
 */
public interface ReviewComment {

    CommentId getId();

    String getBody();

    String getPath();

    String getDiffHunk();

    Integer getLine();

    String getUserLogin();

    /**
     * The author's stable account id, or {@code null} when the provider does not send one.
     * Comparable with {@link GitUser#id()}.
     */
    String getUserId();
}
