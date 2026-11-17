package org.remus.giteabot.azuredevops;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The platform of an Azure DevOps integration.
 * <p>
 * Azure DevOps has no endpoint returning patch text, so its {@link AzureDevopsApiClient} renders
 * pull request diffs with {@code git}, through this platform's remotes and credentials;
 * {@link AzureDevopsProviderMetadata} binds the client to the platform.
 */
public class AzureDevopsPlatform extends RepositoryPlatform {

    public AzureDevopsPlatform(String baseUrl, String cloneUrl,
                               HttpCredentials httpCredentials,
                               @Nullable Supplier<SshCredentials> sshCredentials,
                               AzureDevopsApiClient client, Duration userTtl) {
        super(baseUrl, cloneUrl, httpCredentials, sshCredentials, client, userTtl);
    }

    /**
     * Azure DevOps clone URLs are {@code {base}/{org}/{project}/_git/{name}} rather than
     * the {@code {base}/{owner}/{repo}.git} default, so the path is assembled here — including
     * the same organization-scoping rule the REST paths use, since a legacy or Azure DevOps
     * Server clone URL carries the organization in its host or collection prefix already.
     * <p>
     * The base URL still goes through {@link #validatedCloneBaseUrl}, which is what keeps a
     * credential-bearing URL out of the remote handed to {@code git}.
     */
    @Override
    public String getRepositoryRemote(String owner, String repo) {
        if (getTransport() != GitTransport.HTTP) {
            throw unsupportedTransport();
        }
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        String base = validatedCloneBaseUrl(cloneUrl);
        String organizationSegment = addr.isOrganizationScopedBy(base)
                ? "" : "/" + addr.organization();
        return base + organizationSegment + "/" + addr.project() + "/_git/" + addr.name();
    }

    /**
     * Azure DevOps addresses a file through query parameters on the repository's
     * {@code /_git/} page: {@code version=GC{sha}} pins the commit, and a line selection
     * runs from {@code line} up to column 1 of {@code lineEnd}, so the last highlighted
     * line is {@code lineEnd - 1}. Independent of the Git transport: the clone base is the
     * web host either way. An unresolvable address yields no link rather than a guessed one.
     */
    @Override
    public Optional<String> getFileWebUrl(String owner, String repo, String revision, String path,
                                          @Nullable Integer startLine, @Nullable Integer endLine) {
        if (revision == null || revision.isBlank() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        AzureDevopsAddress addr;
        String base;
        try {
            addr = AzureDevopsAddress.parse(owner, repo);
            base = validatedCloneBaseUrl(cloneUrl);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.empty();
        }
        String organizationSegment = addr.isOrganizationScopedBy(base)
                ? "" : "/" + encodeSegment(addr.organization());
        StringBuilder url = new StringBuilder(base).append(organizationSegment)
                .append('/').append(encodeSegment(addr.project()))
                .append("/_git/").append(encodeSegment(addr.name()))
                .append("?path=").append(encodeQueryPath(path.startsWith("/") ? path : "/" + path))
                .append("&version=GC").append(encodeQueryValue(revision));
        if (startLine != null) {
            int last = endLine != null && endLine > startLine ? endLine : startLine;
            url.append("&line=").append(startLine)
                    .append("&lineEnd=").append(last + 1)
                    .append("&lineStartColumn=1&lineEndColumn=1&lineStyle=plain");
        }
        return Optional.of(url.append("&_a=contents").toString());
    }

    private static String encodeSegment(String value) {
        return encodeQueryValue(value).replace("+", "%20");
    }

    private static String encodeQueryPath(String path) {
        return encodeQueryValue(path).replace("+", "%20").replace("%2F", "/");
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Azure DevOps Git remotes only accept the PAT as a pre-emptive Basic header. */
    @Override
    public boolean usesGitAuthorizationHeader() {
        return true;
    }
}
