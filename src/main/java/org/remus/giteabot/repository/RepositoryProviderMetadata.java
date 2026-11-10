package org.remus.giteabot.repository;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Metadata and factory interface for Git repository provider integrations.
 * Each repository provider (GitHub, Gitea, GitLab, etc.) implements this interface
 * to define its type, authentication, and client creation.
 */
public interface RepositoryProviderMetadata {

    /**
     * Returns the repository provider type this metadata handles.
     */
    RepositoryType getProviderType();

    /**
     * Creates the credentials for Git over HTTP.
     *
     * @param integration    the Git integration configuration
     * @param decryptedToken the decrypted access token
     * @return the HTTP Git username and password
     */
    HttpCredentials createHttpCredentials(GitIntegration integration, String decryptedToken);

    /**
     * Creates the platform of an integration with this provider, including its API client.
     *
     * @param integration     the Git integration configuration
     * @param decryptedToken  the decrypted access token the API client authenticates with
     * @param httpCredentials the credentials for Git over HTTP
     * @param sshCredentials  the credentials for Git over SSH, or {@code null} when SSH is not selected
     * @param userTtl         how long the account the token authenticates as is cached
     * @return the platform
     */
    RepositoryPlatform createPlatform(GitIntegration integration, String decryptedToken,
                                      HttpCredentials httpCredentials,
                                      @Nullable Supplier<SshCredentials> sshCredentials,
                                      Duration userTtl);
}

