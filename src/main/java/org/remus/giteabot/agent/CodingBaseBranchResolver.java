package org.remus.giteabot.agent;

import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the branch a coding session may clone.
 *
 * <p>Most repositories use their provider's default branch. Gitea Pages
 * repositories are different: their default branch is the published static
 * output, while the authored project is maintained on {@code main}. The
 * resolver only selects that authored branch after proving its source shape;
 * otherwise it fails closed before a workspace is cloned.</p>
 */
final class CodingBaseBranchResolver {

    static final String GITEA_PAGES_BRANCH = "gitea-pages";
    private static final String AUTHORED_BRANCH = "main";

    private static final Set<String> SOURCE_ROOTS = Set.of("src", "app", "lib", "server");
    private static final Set<String> PROJECT_MANIFESTS = Set.of(
            "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "pyproject.toml", "requirements.txt", "go.mod", "Cargo.toml",
            "Gemfile", "composer.json");

    private final RepositoryApiClient repositoryClient;

    CodingBaseBranchResolver(RepositoryApiClient repositoryClient) {
        this.repositoryClient = Objects.requireNonNull(repositoryClient, "repositoryClient");
    }

    String resolve(String owner, String repo, String explicitIssueRef) {
        if (explicitIssueRef != null && !explicitIssueRef.isBlank()) {
            validateRefResolves(owner, repo, explicitIssueRef);
            return explicitIssueRef;
        }

        String defaultBranch = readDefaultBranch(owner, repo);
        if (!GITEA_PAGES_BRANCH.equals(defaultBranch)) {
            return defaultBranch;
        }

        List<Map<String, Object>> mainTree = readTree(owner, repo, AUTHORED_BRANCH);
        if (!hasAuthoredSourceShape(mainTree)) {
            throw new SourceBranchResolutionException(
                    "The Pages default branch does not identify a qualified authored main branch");
        }
        return AUTHORED_BRANCH;
    }

    private String readDefaultBranch(String owner, String repo) {
        try {
            String defaultBranch = repositoryClient.getDefaultBranch(owner, repo);
            if (defaultBranch == null || defaultBranch.isBlank()) {
                throw new SourceBranchResolutionException("The repository default branch could not be validated");
            }
            return defaultBranch;
        } catch (SourceBranchResolutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SourceBranchResolutionException("The repository default branch could not be validated", e);
        }
    }

    private void validateRefResolves(String owner, String repo, String ref) {
        List<Map<String, Object>> tree = readTree(owner, repo, ref);
        if (tree.isEmpty()) {
            throw new SourceBranchResolutionException("The explicit issue ref could not be validated");
        }
    }

    private List<Map<String, Object>> readTree(String owner, String repo, String ref) {
        try {
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, ref);
            return tree == null ? List.of() : tree;
        } catch (RuntimeException e) {
            throw new SourceBranchResolutionException("The candidate source branch could not be validated", e);
        }
    }

    private boolean hasAuthoredSourceShape(List<Map<String, Object>> tree) {
        boolean hasSourceRoot = false;
        boolean hasManifest = false;

        for (Map<String, Object> entry : tree) {
            if (!isFileEntry(entry)) {
                continue;
            }
            Object rawPath = entry.get("path");
            if (!(rawPath instanceof String path) || path.isBlank()) {
                continue;
            }
            if (isSourcePath(path)) {
                hasSourceRoot = true;
            }
            if (isProjectManifest(path)) {
                hasManifest = true;
            }
            if (hasSourceRoot && hasManifest) {
                return true;
            }
        }
        return false;
    }

    private boolean isFileEntry(Map<String, Object> entry) {
        // Gitea, GitHub, and GitLab normalize files to "blob" in their tree APIs.
        // The Pages fallback is Gitea-specific, so an unknown entry type is not
        // treated as source proof.
        return "blob".equals(entry.get("type"));
    }

    private boolean isSourcePath(String path) {
        int separator = path.indexOf('/');
        return separator > 0 && separator < path.length() - 1
                && SOURCE_ROOTS.contains(path.substring(0, separator));
    }

    private boolean isProjectManifest(String path) {
        if (PROJECT_MANIFESTS.contains(path)) {
            return true;
        }
        return path.endsWith(".csproj") || path.endsWith(".fsproj") || path.endsWith(".sln");
    }

    static final class SourceBranchResolutionException extends RuntimeException {
        SourceBranchResolutionException(String message) {
            super(message);
        }

        SourceBranchResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
