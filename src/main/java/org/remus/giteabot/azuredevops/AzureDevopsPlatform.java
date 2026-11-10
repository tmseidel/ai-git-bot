package org.remus.giteabot.azuredevops;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Duration;
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

    /** Azure DevOps Git remotes only accept the PAT as a pre-emptive Basic header. */
    @Override
    public boolean usesGitAuthorizationHeader() {
        return true;
    }
}
