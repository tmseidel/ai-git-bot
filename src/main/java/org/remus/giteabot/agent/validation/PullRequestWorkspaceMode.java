package org.remus.giteabot.agent.validation;

/**
 * What a prepared pull-request workspace must be good for.
 *
 * <p>The constant decides how far workspace preparation may fall back when the
 * head branch of the pull request cannot be cloned from the repository that owns
 * it. Providers without authoritative head resolution ignore it.</p>
 */
public enum PullRequestWorkspaceMode {

    /**
     * Exploration only. A checkout of the target repository's pull-request head
     * ref ({@link org.remus.giteabot.repository.RepositoryApiClient#getPullRequestHeadRef})
     * is acceptable: it carries the head commit for as long as the pull request
     * is open. Used by the read-only PR workflows.
     */
    READ_ONLY,

    /**
     * The caller commits and pushes to the pull-request head branch, so only a
     * checkout of that branch from its source repository is acceptable — the
     * target-repository and PR-ref fallbacks fail closed instead of handing back a
     * workspace whose push would go to the wrong repository.
     */
    PUSH_BACK
}
