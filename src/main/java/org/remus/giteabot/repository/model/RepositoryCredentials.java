package org.remus.giteabot.repository.model;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.GitTransport;

/**
 * Authentication and transport configuration for a repository API client.
 * HTTP Git operations use the credential-free clone base URL plus username/token;
 * SSH Git operations use a repository-specific remote resolved by the client plus
 * the private key and pinned {@code known_hosts} data.
 *
 * @param baseUrl   the API base URL (e.g., "https://api.github.com")
 * @param cloneUrl  the credential-free HTTP clone base URL (e.g., "https://github.com")
 * @param username  the HTTP Git username (required for Bitbucket App Passwords, optional otherwise)
 * @param token     the API token and HTTP Git access token or app password
 * @param transport transport used by Git commands
 * @param sshPrivateKey decrypted private key when SSH is selected
 * @param sshKnownHosts verified known_hosts entries when SSH is selected
 */
public record RepositoryCredentials(
        String baseUrl,
        String cloneUrl,
        @Nullable String username,
        @Nullable String token,
        GitTransport transport,
        @Nullable String sshPrivateKey,
        @Nullable String sshKnownHosts
) {
    /** Creates HTTP credentials, preserving the pre-SSH constructor API. */
    public RepositoryCredentials(String baseUrl, String cloneUrl, @Nullable String username, @Nullable String token) {
        this(baseUrl, cloneUrl, username, token, GitTransport.HTTP, null, null);
    }

    /**
     * Creates credentials without a username (for GitHub, Gitea, etc.).
     */
    public static RepositoryCredentials of(String baseUrl, String cloneUrl, String token) {
        return new RepositoryCredentials(baseUrl, cloneUrl, null, token);
    }

    /**
     * Creates credentials with a username (for Bitbucket App Passwords).
     */
    public static RepositoryCredentials of(String baseUrl, String cloneUrl, String username, String token) {
        return new RepositoryCredentials(baseUrl, cloneUrl, username, token);
    }

    /**
     * Returns true if a username is configured.
     */
    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    /** Returns a copy configured for SSH Git transport. */
    public RepositoryCredentials withSsh(String privateKey, String knownHosts) {
        return new RepositoryCredentials(baseUrl, cloneUrl, username, token,
                GitTransport.SSH, privateKey, knownHosts);
    }

    /** Returns whether Git commands should use the configured SSH credentials. */
    public boolean usesSsh() {
        return transport == GitTransport.SSH;
    }

    @Override
    public String toString() {
        return "RepositoryCredentials[baseUrl=" + baseUrl + ", cloneUrl=" + cloneUrl
                + ", username=" + username + ", token=<redacted>, transport=" + transport
                + ", sshPrivateKey=<redacted>, sshKnownHosts=<redacted>]";
    }
}
