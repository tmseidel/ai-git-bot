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

    /**
     * The id of the comment's author, the same as {@link RepositoryUser#id()} for the same account
     * (Bitbucket: {@code account_id}; Azure DevOps: the identity GUID); {@code null} when unknown.
     */
    String getUserId();
}
