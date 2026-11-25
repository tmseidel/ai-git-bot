package org.remus.giteabot.gitea;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** The platform of a Gitea integration. */
public class GiteaPlatform extends RepositoryPlatform {

    private final GiteaApiClient giteaClient;
    private final @Nullable String sshKnownHosts;

    /**
     * @param sshKnownHosts the pinned {@code known_hosts} entries, checked against the SSH clone
     *                      URL Gitea reports when SSH is the transport; not a secret, so kept here
     *                      rather than read through the (decrypting) SSH credentials
     */
    public GiteaPlatform(String baseUrl, String cloneUrl,
                         HttpCredentials httpCredentials,
                         @Nullable Supplier<SshCredentials> sshCredentials,
                         GiteaApiClient client,
                         @Nullable String sshKnownHosts,
                         Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
        this.giteaClient = client;
        this.sshKnownHosts = sshKnownHosts;
    }

    /**
     * Over SSH, the remote is the {@code ssh_url} Gitea reports for the repository, checked
     * against the pinned host keys: its SSH host and port are only known to the server.
     */
    @Override
    public String getRepositoryRemote(String owner, String repo) {
        return switch (getTransport()) {
            case HTTP -> httpRepositoryRemote(owner, repo);
            case SSH -> giteaClient.getSshRepositoryRemote(owner, repo, sshKnownHosts);
        };
    }

    /** A pull request is an issue on Gitea and takes its number. */
    @Override
    public boolean sharesIssueAndPullRequestNumbers() {
        return true;
    }

    @Override
    public Optional<String> getPullRequestWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/pulls/" + number);
    }

    @Override
    public Optional<String> getIssueWebUrl(String owner, String repo, @Nullable Long number) {
        return number == null ? Optional.empty()
                : repositoryWebUrl(owner, repo).map(base -> base + "/issues/" + number);
    }

    /** Gitea anchors conversation and inline comments alike; reviews have no anchor. */
    @Override
    protected Optional<String> commentAnchor(PullRequestCommentKind kind, String commentId) {
        if (!isNumericId(commentId)) {
            return Optional.empty();
        }
        return switch (kind) {
            case PR_COMMENT, INLINE_COMMENT -> Optional.of("#issuecomment-" + commentId);
            case REVIEW -> Optional.empty();
        };
    }
}
