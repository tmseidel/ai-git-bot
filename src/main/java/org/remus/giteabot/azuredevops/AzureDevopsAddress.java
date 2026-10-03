package org.remus.giteabot.azuredevops;

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
}
