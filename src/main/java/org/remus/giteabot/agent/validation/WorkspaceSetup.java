package org.remus.giteabot.agent.validation;

import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.nio.file.Path;

/**
 * Lifecycle holder of one workspace attempt, including credentials retained in
 * memory and authentication files materialized only during remote Git commands.
 *
 * <p>The private temporary parent and any in-flight files are cleaned together
 * via {@link WorkspaceService#cleanupWorkspace}.</p>
 */
class WorkspaceSetup {

    private final Path workspaceRoot;
    private Path credentialsFile;
    private Path sshPrivateKeyFile;
    private Path sshKnownHostsFile;
    private String repositoryRemote;
    private RepositoryCredentials repositoryCredentials;
    private boolean usesAuthorizationHeader;
    private volatile boolean closed;

    WorkspaceSetup(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /** The private temporary parent of this attempt (contains the marker file). */
    Path workspaceRoot() {
        return workspaceRoot;
    }

    /** The repository child directory that git clones into. */
    Path workspaceDir() {
        return workspaceRoot.resolve(WorkspaceService.REPOSITORY_DIRECTORY_NAME);
    }

    /** The external credential-store file of this attempt, or {@code null} if none. */
    Path credentialsFile() {
        return credentialsFile;
    }

    void setCredentialsFile(Path credentialsFile) {
        this.credentialsFile = credentialsFile;
    }

    Path sshPrivateKeyFile() {
        return sshPrivateKeyFile;
    }

    void setSshPrivateKeyFile(Path sshPrivateKeyFile) {
        this.sshPrivateKeyFile = sshPrivateKeyFile;
    }

    Path sshKnownHostsFile() {
        return sshKnownHostsFile;
    }

    void setSshKnownHostsFile(Path sshKnownHostsFile) {
        this.sshKnownHostsFile = sshKnownHostsFile;
    }

    String repositoryRemote() {
        return repositoryRemote;
    }

    RepositoryCredentials repositoryCredentials() {
        return repositoryCredentials;
    }

    /** Whether HTTP Git commands send the token as an {@code Authorization} header. */
    boolean usesAuthorizationHeader() {
        return usesAuthorizationHeader;
    }

    void setAuthentication(String repositoryRemote, RepositoryCredentials repositoryCredentials,
                           boolean usesAuthorizationHeader) {
        this.repositoryRemote = repositoryRemote;
        this.repositoryCredentials = repositoryCredentials;
        this.usesAuthorizationHeader = usesAuthorizationHeader;
    }

    boolean closed() {
        return closed;
    }

    void close() {
        closed = true;
        repositoryRemote = null;
        repositoryCredentials = null;
    }
}
