package org.remus.giteabot.azuredevops;

import java.net.URI;
import java.util.Locale;

/**
 * Resolves the Azure DevOps three-level address (organization / project / repository)
 * from the two-part {@code (owner, repo)} pair used throughout the application.
 * <p>
 * The bot addresses Azure DevOps repositories as {@code owner = organization} and
 * {@code repo = "Project/Repository"}. Repository names may not contain {@code '/'} in
 * Azure DevOps, so splitting on the first separator is unambiguous.
 *
 * @param organization the Azure DevOps organization (the {@code owner} argument)
 * @param project      the Azure DevOps project name or id
 * @param name         the Git repository name or id
 */
public record AzureDevopsAddress(String organization, String project, String name) {

    public static AzureDevopsAddress parse(String owner, String repo) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException(
                    "Azure DevOps organization (owner) must not be blank");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException(
                    "Azure DevOps repository must be given as 'Project/Repository', was blank");
        }
        int slash = repo.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(
                    "Azure DevOps repository must be given as 'Project/Repository', was: " + repo);
        }
        String project = repo.substring(0, slash);
        String name = repo.substring(slash + 1);
        if (project.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Azure DevOps repository must be given as 'Project/Repository', was: " + repo);
        }
        return new AzureDevopsAddress(owner, project, name);
    }

    /**
     * Whether {@code url} already scopes this address's {@link #organization}, meaning it
     * must <em>not</em> be repeated as a path segment.
     * <p>
     * Azure DevOps is addressed three different ways, and only the first needs the
     * organization in the request path:
     * <pre>
     * https://dev.azure.com              + org "contoso" -&gt; /contoso/{project}/_apis/...
     * https://contoso.visualstudio.com   + org "contoso" -&gt; /{project}/_apis/...
     * https://tfs.example.com/tfs/DefaultCollection
     *                    + collection "DefaultCollection" -&gt; /{project}/_apis/...
     * </pre>
     * Repeating it in the second and third forms puts the organization where the server
     * expects a collection, which 404s every request.
     * <p>
     * The decision is derived from the configured URL rather than from a host allow-list,
     * so an Azure DevOps Server instance configured without its collection
     * ({@code https://tfs.example.com/tfs}) still gets the collection as a path segment.
     * Two shapes count as already-scoped:
     * <ul>
     *   <li>the organization as the leading label of a {@code *.visualstudio.com} host.
     *       The suffix is required: Azure DevOps Server never encodes its collection in
     *       the hostname, so without it a host like {@code tfs.example.com} serving a
     *       collection named {@code tfs} would wrongly look already-scoped.</li>
     *   <li>the organization as the URL path's last segment — an Azure DevOps Server
     *       collection, or {@code https://dev.azure.com/contoso} for a single organization.</li>
     * </ul>
     */
    public boolean isOrganizationScopedBy(String url) {
        return isOrganizationScopedBy(url, organization);
    }

    /**
     * Whether {@code url} already scopes {@code organization}, for callers holding only an
     * organization rather than a full address. See {@link #isOrganizationScopedBy(String)}.
     */
    public static boolean isOrganizationScopedBy(String url, String organization) {
        if (url == null || url.isBlank() || organization == null || organization.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        if (host != null && host.toLowerCase(Locale.ROOT).endsWith(".visualstudio.com")) {
            int firstDot = host.indexOf('.');
            if (firstDot > 0 && host.substring(0, firstDot).equalsIgnoreCase(organization)) {
                return true;
            }
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                return segments[i].equalsIgnoreCase(organization);
            }
        }
        return false;
    }
}
