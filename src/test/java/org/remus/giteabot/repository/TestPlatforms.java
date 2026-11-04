package org.remus.giteabot.repository;

import org.remus.giteabot.repository.model.HttpCredentials;

/**
 * Test helper wrapping an API client, typically a mock, in a {@link RepositoryPlatform} with the
 * default behaviour of every method: HTTP remotes below {@link #URL}, {@code #N} references and so on.
 */
public final class TestPlatforms {

    public static final String URL = "https://git.example.com";

    private TestPlatforms() {
    }

    /** Returns a platform authenticating over HTTP with a dummy token. */
    public static RepositoryPlatform of(RepositoryApiClient client) {
        return of(client, new HttpCredentials(null, "token"));
    }

    /** Returns a platform authenticating over HTTP with the given credentials. */
    public static RepositoryPlatform of(RepositoryApiClient client, HttpCredentials credentials) {
        return new RepositoryPlatform(URL, URL, credentials, null, client) {
        };
    }
}
