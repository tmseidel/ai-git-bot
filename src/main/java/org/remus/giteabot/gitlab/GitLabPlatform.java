package org.remus.giteabot.gitlab;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.function.Supplier;

/** The platform of a GitLab integration. */
public class GitLabPlatform extends RepositoryPlatform {

    public GitLabPlatform(String baseUrl, String cloneUrl,
                          HttpCredentials httpCredentials,
                          @Nullable Supplier<SshCredentials> sshCredentials,
                          GitLabApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /** GitLab references merge requests as {@code !N}. */
    @Override
    public String formatPullRequestReference(Long prNumber) {
        return "!" + prNumber;
    }
}
