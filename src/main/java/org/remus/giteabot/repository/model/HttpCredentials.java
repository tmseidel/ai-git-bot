package org.remus.giteabot.repository.model;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.GitTransport;

/**
 * Credentials for Git over HTTP.
 *
 * @param username the HTTP Git username (e.g. Bitbucket's fixed API-token Git user), optional otherwise
 * @param password the API token, used as the HTTP Git password
 */
public record HttpCredentials(@Nullable String username, @Nullable String password)
        implements RepositoryCredentials {

    @Override
    public GitTransport transport() {
        return GitTransport.HTTP;
    }

    /** Returns true if a username is configured. */
    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    @Override
    public String toString() {
        return "HttpCredentials[username=" + username + ", password=<redacted>]";
    }
}
