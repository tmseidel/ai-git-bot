package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic interface for a pull request review.
 * Implementations exist for Gitea ({@link org.remus.giteabot.gitea.model.GiteaReview}),
 * GitHub ({@link org.remus.giteabot.github.model.GitHubReview}), and Bitbucket Cloud
 * ({@link org.remus.giteabot.bitbucket.model.BitbucketReview}),
 * with future support for GitLab, etc.
 */
public interface Review {

    Long getId();

    String getBody();

    String getState();

    String getSubmittedAt();

    Integer getCommentsCount();
}
