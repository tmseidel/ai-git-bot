package org.remus.giteabot.review.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryTreeEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private RepositoryTreeEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new RepositoryTreeEnricher(repositoryClient, new ReviewConfigProperties());
    }

    @Test
    void enrich_formatsTree() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        new RepositoryTreeEntry("pom.xml", RepositoryTreeEntry.Type.FILE),
                        new RepositoryTreeEntry("src/main/java/Foo.java", RepositoryTreeEntry.Type.FILE),
                        new RepositoryTreeEntry("src", RepositoryTreeEntry.Type.DIRECTORY)
                ));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, "main", null));

        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("src/main/java/Foo.java"));
        assertTrue(result.contains("Repository structure"));
        assertFalse(result.contains("  src\n"));
    }

    @Test
    void enrich_emptyTree() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of());

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, "main", null));

        assertEquals("", result);
    }

    @Test
    void enrich_handlesApiError() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenThrow(new RuntimeException("API error"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, null, "main", null));

        assertEquals("", result);
    }
}

