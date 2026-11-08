package org.remus.giteabot.github;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.util.function.Supplier;

/** The platform of a GitHub integration. Uses every default of {@link RepositoryPlatform}. */
public class GitHubPlatform extends RepositoryPlatform {

    public GitHubPlatform(String baseUrl, String cloneUrl,
                          HttpCredentials httpCredentials,
                          @Nullable Supplier<SshCredentials> sshCredentials,
                          GitHubApiClient client) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client);
    }
}
