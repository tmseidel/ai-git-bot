package org.remus.giteabot.repository.model;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.GitTransport;

/**
 * Credentials for Git over SSH.
 *
 * @param privateKey the decrypted private key
 * @param knownHosts the verified {@code known_hosts} entries
 */
public record SshCredentials(@Nullable String privateKey, @Nullable String knownHosts)
        implements RepositoryCredentials {

    @Override
    public GitTransport transport() {
        return GitTransport.SSH;
    }

    @Override
    public String toString() {
        return "SshCredentials[privateKey=<redacted>, knownHosts=<redacted>]";
    }
}
