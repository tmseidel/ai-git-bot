package org.remus.giteabot.bitbucket;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.util.Optional;
import java.util.function.Supplier;

/** The platform of a Bitbucket Cloud integration. */
public class BitbucketPlatform extends RepositoryPlatform {

    private final BitbucketApiClient bitbucketClient;

    public BitbucketPlatform(String baseUrl, String cloneUrl,
                             HttpCredentials httpCredentials,
                             @Nullable Supplier<SshCredentials> sshCredentials,
                             BitbucketApiClient client) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client);
        this.bitbucketClient = client;
    }

    /**
     * Bitbucket mentions are picked via the {@code @} autocomplete, which searches by
     * display name, so that name (or the nickname) is what users should type.
     */
    @Override
    public Optional<String> getBotMentionName() {
        return Optional.ofNullable(bitbucketClient.getAuthenticatedAccount().name());
    }
}
