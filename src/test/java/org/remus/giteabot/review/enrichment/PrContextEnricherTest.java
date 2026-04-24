package org.remus.giteabot.review.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrContextEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    // --- Orchestration tests ---

    @Test
    void buildEnrichedContext_combinesAllSections() {
        String diff = "diff --git a/src/Foo.java b/src/Foo.java\n+new line";

        when(repositoryClient.getRepositoryTree("owner", "repo", "feature"))
                .thenReturn(List.of(
                        Map.of("type", "blob", "path", "src/Foo.java"),
                        Map.of("type", "blob", "path", "pom.xml")
                ));
        when(repositoryClient.getFileContent("owner", "repo", "src/Foo.java", "feature"))
                .thenReturn("class Foo {}");
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("sha", "abc1234", "commit", Map.of("message", "Add Foo"))
                ));
        when(repositoryClient.getIssueDetails("owner", "repo", 5L))
                .thenReturn(Map.of("title", "Create Foo class", "body", "Need a Foo implementation"));

        PrContextEnricher enricher = new PrContextEnricher(repositoryClient, new ReviewConfigProperties());
        String result = enricher.buildEnrichedContext("owner", "repo", 1L, diff, "feature", "fixes #5");

        assertTrue(result.contains("Repository structure"));
        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("Changed files"));
        assertTrue(result.contains("class Foo"));
        assertTrue(result.contains("Commit messages"));
        assertTrue(result.contains("Add Foo"));
        assertTrue(result.contains("Referenced issues"));
        assertTrue(result.contains("Create Foo class"));
    }

    @Test
    void buildEnrichedContext_handlesAllFailuresGracefully() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenThrow(new RuntimeException("tree error"));
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenThrow(new RuntimeException("commits error"));

        PrContextEnricher enricher = new PrContextEnricher(repositoryClient, new ReviewConfigProperties());
        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        assertNotNull(result);
    }

    @Test
    void buildEnrichedContext_nullDiffAndBody() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));

        PrContextEnricher enricher = new PrContextEnricher(repositoryClient, new ReviewConfigProperties());
        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        assertTrue(result.contains("Repository structure"));
        assertTrue(result.contains("README.md"));
        assertFalse(result.contains("Changed files"));
        verify(repositoryClient, never()).getFileContent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void buildEnrichedContext_skipsEmptyEnrichers() {
        ContextEnricher emptyEnricher = context -> "";
        ContextEnricher contentEnricher = context -> "some content";

        PrContextEnricher enricher = new PrContextEnricher(List.of(emptyEnricher, contentEnricher));
        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        assertTrue(result.contains("some content"));
        // Should not have double newlines from empty enricher
        assertFalse(result.startsWith("\n"));
    }

    @Test
    void buildEnrichedContext_handlesEnricherException() {
        ContextEnricher failingEnricher = context -> { throw new RuntimeException("boom"); };
        ContextEnricher workingEnricher = context -> "works fine";

        PrContextEnricher enricher = new PrContextEnricher(List.of(failingEnricher, workingEnricher));
        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        assertTrue(result.contains("works fine"));
    }
}
