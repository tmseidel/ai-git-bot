package org.remus.giteabot.github;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.function.Supplier;

/** The platform of a GitHub integration. */
public class GitHubPlatform extends RepositoryPlatform {

    public GitHubPlatform(String baseUrl, String cloneUrl,
                          HttpCredentials httpCredentials,
                          @Nullable Supplier<SshCredentials> sshCredentials,
                          GitHubApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /** A pull request is an issue on GitHub and takes its number. */
    @Override
    public boolean sharesIssueAndPullRequestNumbers() {
        return true;
    }
}
