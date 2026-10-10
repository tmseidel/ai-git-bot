package org.remus.giteabot.agent;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingBaseBranchResolverTest {

    private final RepositoryApiClient repositoryClient = mock(RepositoryApiClient.class);
    private final CodingBaseBranchResolver resolver = new CodingBaseBranchResolver(repositoryClient);

    @Test
    void keepsAnOrdinaryRepositoryOnItsProviderDefaultWithoutInspectingAnotherBranch() {
        when(repositoryClient.getDefaultBranch("owner", "repo")).thenReturn("release");

        assertThat(resolver.resolve("owner", "repo", null)).isEqualTo("release");

        verify(repositoryClient).getDefaultBranch("owner", "repo");
        verify(repositoryClient, never()).getRepositoryTree("owner", "repo", "main");
    }

    @Test
    void rejectsAnUnavailableDefaultBranchBeforeSelectingAWorkspace() {
        when(repositoryClient.getDefaultBranch("owner", "repo"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> resolver.resolve("owner", "repo", null))
                .isInstanceOf(CodingBaseBranchResolver.SourceBranchResolutionException.class)
                .hasMessageContaining("default branch");

        verify(repositoryClient, never()).getRepositoryTree("owner", "repo", "main");
    }

    @Test
    void honorsAnExplicitIssueRefOnlyAfterItResolves() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "feature/fix"))
                .thenReturn(List.of(Map.of("path", "README.md")));

        assertThat(resolver.resolve("owner", "repo", "feature/fix")).isEqualTo("feature/fix");

        verify(repositoryClient, never()).getDefaultBranch("owner", "repo");
        verify(repositoryClient).getRepositoryTree("owner", "repo", "feature/fix");
    }

    @Test
    void rejectsAnExplicitIssueRefThatDoesNotResolve() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "missing"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve("owner", "repo", "missing"))
                .isInstanceOf(CodingBaseBranchResolver.SourceBranchResolutionException.class)
                .hasMessageContaining("explicit issue ref");

        verify(repositoryClient, never()).getDefaultBranch("owner", "repo");
    }

    @Test
    void resolvesPagesOutputDefaultToQualifiedAuthoredMain() {
        when(repositoryClient.getDefaultBranch("owner", "repo")).thenReturn("gitea-pages");
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        Map.of("type", "blob", "path", "package.json"),
                        Map.of("type", "blob", "path", "src/game.ts"),
                        Map.of("type", "blob", "path", "vite.config.ts")));

        assertThat(resolver.resolve("owner", "repo", null)).isEqualTo("main");
    }

    @Test
    void rejectsPagesMainWhenManifestAndSourceRootAreDirectories() {
        when(repositoryClient.getDefaultBranch("owner", "repo")).thenReturn("gitea-pages");
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        Map.of("type", "tree", "path", "package.json"),
                        Map.of("type", "tree", "path", "src"),
                        Map.of("type", "blob", "path", "assets/app.js")));

        assertThatThrownBy(() -> resolver.resolve("owner", "repo", null))
                .isInstanceOf(CodingBaseBranchResolver.SourceBranchResolutionException.class);
    }

    @Test
    void rejectsPagesOutputWhenMainDoesNotProveItContainsAuthoredSource() {
        when(repositoryClient.getDefaultBranch("owner", "repo")).thenReturn("gitea-pages");
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        Map.of("type", "blob", "path", "index.html"),
                        Map.of("type", "blob", "path", "assets/app.js")));

        assertThatThrownBy(() -> resolver.resolve("owner", "repo", null))
                .isInstanceOf(CodingBaseBranchResolver.SourceBranchResolutionException.class)
                .hasMessageContaining("qualified authored main");
    }
}
