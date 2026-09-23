package org.remus.giteabot.repository.model;

/**
 * Authoritative source coordinates for a pull request head.
 *
 * @param owner      source repository owner
 * @param repository source repository name
 * @param branch     source branch name
 * @param sha        current source commit, when provided by the repository API
 */
public record PullRequestHead(String owner, String repository, String branch, String sha) {
}
