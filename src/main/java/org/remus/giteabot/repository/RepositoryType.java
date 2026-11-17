package org.remus.giteabot.repository;

/**
 * Supported source repository provider types.
 * Each type corresponds to a specific {@link RepositoryApiClient} implementation
 * and has its own set of configurable properties.
 */
public enum RepositoryType {
    GITEA("Gitea"),
    GITHUB("GitHub"),
    GITLAB("GitLab"),
    BITBUCKET("Bitbucket"),
    AZURE_DEVOPS("Azure DevOps");

    private final String displayName;

    RepositoryType(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable provider name shown in the admin UI. */
    public String getDisplayName() {
        return displayName;
    }
}
