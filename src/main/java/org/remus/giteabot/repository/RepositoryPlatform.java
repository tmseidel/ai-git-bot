package org.remus.giteabot.repository;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.remus.giteabot.repository.model.SshCredentials;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything needed to work with one Git integration besides its REST API: its URLs, how Git
 * commands reach and authenticate against its repositories, the account its token authenticates
 * as, and how the provider names things in comments. The API itself is the
 * {@linkplain #getClient() client}'s job. Each provider has
 * its own subclass, built by {@link RepositoryProviderMetadata#createPlatform}.
 * <p>
 * The HTTP credentials hold the decrypted API token, which the API client's {@code RestClient}
 * keeps in memory anyway. The SSH credentials are a supplier, so the private key is only
 * decrypted when a Git command needs it rather than being held for as long as the platform is.
 */
public abstract class RepositoryPlatform {

    /** The API base URL (e.g. {@code https://api.github.com}). */
    @Getter
    protected final String baseUrl;

    /** The credential-free HTTP clone base URL (e.g. {@code https://github.com}). */
    @Getter
    protected final String cloneUrl;

    protected final HttpCredentials httpCredentials;
    protected final @Nullable Supplier<SshCredentials> sshCredentials;

    /** The API client of this integration. */
    @Getter
    protected final RepositoryApiClient client;

    /** The account the token authenticates as, the same wherever it is asked. */
    private final CachedRepositoryUser user;

    /**
     * @param baseUrl         the API base URL (e.g. {@code https://api.github.com})
     * @param cloneUrl        the credential-free HTTP clone base URL (e.g. {@code https://github.com})
     * @param httpCredentials the credentials for Git over HTTP
     * @param sshCredentials  the credentials for Git over SSH, or {@code null} when SSH is not selected
     * @param client          the API client of this integration
     * @param userTtl         how long the account the token authenticates as is cached
     */
    protected RepositoryPlatform(String baseUrl, String cloneUrl,
                                 HttpCredentials httpCredentials,
                                 @Nullable Supplier<SshCredentials> sshCredentials,
                                 RepositoryApiClient client, Duration userTtl) {
        this.baseUrl = baseUrl;
        this.cloneUrl = cloneUrl;
        this.httpCredentials = httpCredentials;
        this.sshCredentials = sshCredentials;
        this.client = client;
        this.user = new CachedRepositoryUser(client::getAuthenticatedUser, userTtl, Clock.systemUTC());
    }

    /**
     * Returns the account the token authenticates as, i.e. the bot's identity on the provider.
     * {@code owner} only says where to ask when nothing valid is cached, see
     * {@link RepositoryApiClient#getAuthenticatedUser(String)}; the account is the same for every owner.
     * This distinction is only relevant for Azure DevOps.
     *
     * @throws IllegalStateException when the provider cannot be asked and nothing is cached
     */
    public RepositoryUser getUser(String owner) {
        return user.get(owner);
    }

    /** Returns the transport Git commands use. */
    public GitTransport getTransport() {
        return sshCredentials != null ? GitTransport.SSH : GitTransport.HTTP;
    }

    /** Returns the credentials of the selected {@linkplain #getTransport() transport}. */
    public RepositoryCredentials getCredentials() {
        return switch (getTransport()) {
            case HTTP -> httpCredentials;
            case SSH -> sshCredentials.get();
        };
    }

    /**
     * Resolves the complete, credential-free Git remote for one repository. By default the
     * {@code {cloneUrl}/{owner}/{repo}.git} HTTP remote, see {@link #httpRepositoryRemote}, and
     * no SSH remote at all: a provider supporting SSH overrides this to resolve one. An
     * override building an HTTP remote must run its base URL through
     * {@link #validatedCloneBaseUrl}.
     *
     * @throws IllegalStateException when the remote cannot be resolved, or the
     *                               {@linkplain #getTransport() transport} is not supported
     */
    public String getRepositoryRemote(String owner, String repo) {
        return switch (getTransport()) {
            case HTTP -> httpRepositoryRemote(owner, repo);
            case SSH -> throw unsupportedTransport();
        };
    }

    /**
     * Returns whether HTTP Git operations must authenticate with a pre-emptive
     * {@code Authorization: Basic} header instead of a credential helper.
     * Needed for providers (e.g. Azure DevOps) whose remotes reject the
     * credential-store challenge/response flow.
     */
    public boolean usesGitAuthorizationHeader() {
        return false;
    }

    /**
     * Returns the name users type after {@code @} to address the bot, for providers that
     * derive the bot's identity from the integration credentials rather than from the
     * bot's configured username (e.g. Bitbucket). Empty means "use the bot username".
     */
    public Optional<String> getBotMentionName() {
        return Optional.empty();
    }

    /**
     * Formats a pull/merge request reference for use in comments.
     * GitLab uses {@code !N} for merge requests, while Gitea, GitHub, Bitbucket and
     * Azure DevOps use {@code #N}.
     */
    public String formatPullRequestReference(Long prNumber) {
        return "#" + prNumber;
    }

    /**
     * Returns the exception {@link #getRepositoryRemote} throws for a transport this provider
     * resolves no remote for. Without it, Git would be handed SSH credentials together with an
     * HTTP remote and fail with an unrelated authentication error.
     */
    protected IllegalStateException unsupportedTransport() {
        return new IllegalStateException(getTransport() + " transport is not supported for "
                + getClass().getSimpleName());
    }

    /** Builds the {@code {cloneUrl}/{owner}/{repo}.git} HTTP remote most providers use. */
    protected String httpRepositoryRemote(String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            throw new IllegalStateException("Repository remote cannot be resolved");
        }
        return validatedCloneBaseUrl(cloneUrl) + "/" + owner + "/" + repo + ".git";
    }

    /**
     * Validates an HTTP clone base URL and returns it normalized: lower-case scheme, no
     * trailing slash.
     * <p>
     * The remote this feeds is handed to {@code git} as a command-line argument and is
     * echoed back in git's own error output, so it must never carry a secret. A base URL
     * with userinfo would both leak the credential there and corrupt the credential-store
     * host {@code WorkspaceService} derives from the remote. Query and fragment are
     * rejected for the same reason: neither is meaningful in a Git remote, and both would
     * survive into the concatenated URL.
     * <p>
     * A separate helper rather than being inlined into {@link #httpRepositoryRemote} so that
     * subclasses building a provider-specific path — Azure DevOps addresses repositories as
     * {@code /{org}/{project}/_git/{name}} — keep the same guarantee instead of
     * reimplementing (and quietly dropping) it.
     *
     * @throws IllegalStateException when the URL is missing, unparseable, not HTTP(S), or
     *                               carries userinfo, a query or a fragment
     */
    protected static String validatedCloneBaseUrl(String cloneBaseUrl) {
        if (cloneBaseUrl == null || cloneBaseUrl.isBlank()) {
            throw new IllegalStateException("Repository remote cannot be resolved");
        }
        String normalized;
        try {
            URI base = URI.create(cloneBaseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalStateException("HTTP clone base URL must be credential-free");
            }
            normalized = base.getScheme().toLowerCase(Locale.ROOT)
                    + cloneBaseUrl.substring(base.getScheme().length());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("HTTP clone base URL is invalid", e);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
