package org.remus.giteabot.repository;

import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Duration;

/**
 * Test helper wrapping an API client, typically a mock, in a {@link RepositoryPlatform} with the
 * default behaviour of every method: HTTP remotes below {@link #URL}, {@code #N} references and so on.
 */
public final class TestPlatforms {

    public static final String URL = "https://git.example.com";

    /** The time a test platform caches the account its token authenticates as. */
    public static final Duration USER_TTL = Duration.ofMinutes(10);

    /** A commit identity for workspaces that commit. */
    public static final RepositoryUser USER = new RepositoryUser("1", "bot", "Bot", "bot@example.com");

    private TestPlatforms() {
    }

    /** Returns a platform authenticating over HTTP with a dummy token. */
    public static RepositoryPlatform of(RepositoryApiClient client) {
        return of(client, new HttpCredentials(null, "token"));
    }

    /** Returns a platform authenticating over HTTP with the given credentials. */
    public static RepositoryPlatform of(RepositoryApiClient client, HttpCredentials credentials) {
        return new RepositoryPlatform(URL, URL, credentials, null, client, USER_TTL) {
        };
    }
}
