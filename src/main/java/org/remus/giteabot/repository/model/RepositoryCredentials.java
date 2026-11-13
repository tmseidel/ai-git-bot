package org.remus.giteabot.repository.model;

import org.remus.giteabot.repository.GitTransport;

/**
 * The secrets Git commands authenticate with, one variant per {@link GitTransport}.
 */
public sealed interface RepositoryCredentials permits HttpCredentials, SshCredentials {

    /** Returns the transport these credentials authenticate. */
    GitTransport transport();
}
