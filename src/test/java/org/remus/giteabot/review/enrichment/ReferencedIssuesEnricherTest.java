package org.remus.giteabot.review.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferencedIssuesEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private ReferencedIssuesEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new ReferencedIssuesEnricher(repositoryClient);
    }

    // --- extractIssueReferences ---

    @Test
    void extractIssueReferences_findsHashReferences() {
        Set<Long> issues = enricher.extractIssueReferences("This PR fixes #42 and relates to #123");
        assertTrue(issues.contains(42L));
        assertTrue(issues.contains(123L));
    }

    @Test
    void extractIssueReferences_findsKeywordReferences() {
        Set<Long> issues = enricher.extractIssueReferences("closes #10, fixes #20, resolves #30");
        assertEquals(3, issues.size());
        assertTrue(issues.contains(10L));
        assertTrue(issues.contains(20L));
        assertTrue(issues.contains(30L));
    }

    @Test
    void extractIssueReferences_emptyBody() {
        Set<Long> issues = enricher.extractIssueReferences("");
        assertTrue(issues.isEmpty());
    }

    @Test
    void extractIssueReferences_nullBody() {
        Set<Long> issues = enricher.extractIssueReferences(null);
        assertTrue(issues.isEmpty());
    }

    @Test
    void extractIssueReferences_noReferences() {
        Set<Long> issues = enricher.extractIssueReferences("This is a simple PR with no issue references");
        assertTrue(issues.isEmpty());
    }

    // --- enrich ---

    @Test
    void enrich_fetchesIssueDetails() {
        when(repositoryClient.getIssueDetails("owner", "repo", 42L))
                .thenReturn(Map.of("title", "Add user authentication", "body", "We need OAuth2 support"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, "fixes #42"));

        assertTrue(result.contains("Referenced issues"));
        assertTrue(result.contains("#42"));
        assertTrue(result.contains("Add user authentication"));
        assertTrue(result.contains("OAuth2 support"));
    }

    @Test
    void enrich_multipleIssues() {
        when(repositoryClient.getIssueDetails("owner", "repo", 10L))
                .thenReturn(Map.of("title", "Issue 10", "body", "Body 10"));
        when(repositoryClient.getIssueDetails("owner", "repo", 20L))
                .thenReturn(Map.of("title", "Issue 20", "body", "Body 20"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, "closes #10, fixes #20"));

        assertTrue(result.contains("#10"));
        assertTrue(result.contains("Issue 10"));
        assertTrue(result.contains("#20"));
        assertTrue(result.contains("Issue 20"));
    }

    @Test
    void enrich_nullBody() {
        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, null));
        assertEquals("", result);
    }

    @Test
    void enrich_noReferences() {
        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, "Simple PR description"));
        assertEquals("", result);
    }

    @Test
    void enrich_handlesApiError() {
        when(repositoryClient.getIssueDetails("owner", "repo", 42L))
                .thenThrow(new RuntimeException("Not found"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, null, "fixes #42"));

        assertTrue(result.contains("Referenced issues"));
    }
}

