package org.remus.giteabot.repository;

/**
 * Supported source repository provider types.
 * Each type corresponds to a specific {@link RepositoryApiClient} implementation
 * and has its own set of configurable properties.
 */
public enum RepositoryType {
    GITEA,
    GITHUB,
    GITLAB,
    BITBUCKET,
    AZURE_DEVOPS
}
