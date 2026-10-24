package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic lifecycle state of a pull request. Each provider maps its
 * native state (e.g. GitHub {@code open}/{@code closed} plus {@code merged},
 * GitLab {@code opened}/{@code merged}) onto these values.
 */
public enum PullRequestState {
    /** The pull request is open and accepts new commits. */
    OPEN,
    /** The pull request was closed without being merged. */
    CLOSED_WITHOUT_MERGE,
    /** The pull request was merged. */
    MERGED
}
