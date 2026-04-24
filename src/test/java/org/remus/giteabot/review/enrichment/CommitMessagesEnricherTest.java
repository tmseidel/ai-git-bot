package org.remus.giteabot.review.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommitMessagesEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private CommitMessagesEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new CommitMessagesEnricher(repositoryClient, new ReviewConfigProperties());
    }

    @Test
    void enrich_formatsGiteaCommits() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("sha", "abc1234567890", "commit", Map.of("message", "Initial implementation")),
                        Map.of("sha", "def5678901234", "commit", Map.of("message", "Fix bug"))
                ));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, null));

        assertTrue(result.contains("Commit messages"));
        assertTrue(result.contains("abc1234"));
        assertTrue(result.contains("Initial implementation"));
        assertTrue(result.contains("def5678"));
        assertTrue(result.contains("Fix bug"));
    }

    @Test
    void enrich_formatsGitLabCommits() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("id", "abc1234567890", "message", "Add feature\n\nDetailed description"),
                        Map.of("id", "def5678901234", "message", "Update tests")
                ));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, null));

        assertTrue(result.contains("abc1234"));
        assertTrue(result.contains("Add feature"));
        assertFalse(result.contains("Detailed description"));
    }

    @Test
    void enrich_emptyCommits() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of());

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, null));

        assertEquals("", result);
    }

    @Test
    void enrich_handlesApiError() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenThrow(new RuntimeException("API error"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, null));

        assertEquals("", result);
    }
}

