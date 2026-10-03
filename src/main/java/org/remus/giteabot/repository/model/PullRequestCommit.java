package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic commit of a pull request.
 *
 * @param sha     full commit SHA, may be {@code null}
 * @param message full commit message, may be {@code null}
 */
public record PullRequestCommit(String sha, String message) {
}
