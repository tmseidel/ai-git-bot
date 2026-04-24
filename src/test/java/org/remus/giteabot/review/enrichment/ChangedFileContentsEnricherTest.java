package org.remus.giteabot.review.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangedFileContentsEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private ChangedFileContentsEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new ChangedFileContentsEnricher(repositoryClient, new ReviewConfigProperties());
    }

    // --- extractChangedFilePaths ---

    @Test
    void extractChangedFilePaths_parsesUnifiedDiff() {
        String diff = """
                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                index abc123..def456 100644
                --- a/src/main/java/Foo.java
                +++ b/src/main/java/Foo.java
                @@ -10,7 +10,7 @@
                 some context
                -old line
                +new line
                diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java
                index 111..222 100644
                """;

        List<String> paths = enricher.extractChangedFilePaths(diff);

        assertEquals(2, paths.size());
        assertTrue(paths.contains("src/main/java/Foo.java"));
        assertTrue(paths.contains("src/test/java/FooTest.java"));
    }

    @Test
    void extractChangedFilePaths_emptyDiff() {
        List<String> paths = enricher.extractChangedFilePaths("");
        assertTrue(paths.isEmpty());
    }

    @Test
    void extractChangedFilePaths_noDiffHeaders() {
        String diff = "+just some added content\n-and removed content";
        List<String> paths = enricher.extractChangedFilePaths(diff);
        assertTrue(paths.isEmpty());
    }

    @Test
    void extractChangedFilePaths_deduplicatesFiles() {
        String diff = """
                diff --git a/file.txt b/file.txt
                some changes
                diff --git a/file.txt b/file.txt
                more changes
                """;

        List<String> paths = enricher.extractChangedFilePaths(diff);
        assertEquals(1, paths.size());
        assertEquals("file.txt", paths.getFirst());
    }

    // --- enrich ---

    @Test
    void enrich_fetchesContent() {
        String diff = "diff --git a/src/Foo.java b/src/Foo.java\n+new line";
        when(repositoryClient.getFileContent("owner", "repo", "src/Foo.java", "feature"))
                .thenReturn("public class Foo {\n}");

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, diff, "feature", null));

        assertTrue(result.contains("src/Foo.java"));
        assertTrue(result.contains("public class Foo"));
        assertTrue(result.contains("Changed files"));
    }

    @Test
    void enrich_emptyDiff() {
        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, "", "feature", null));
        assertEquals("", result);
    }

    @Test
    void enrich_nullRef() {
        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, "some diff", null, null));
        assertEquals("", result);
    }

    @Test
    void enrich_handlesFileNotFound() {
        String diff = "diff --git a/deleted.java b/deleted.java\n-old content";
        when(repositoryClient.getFileContent("owner", "repo", "deleted.java", "feature"))
                .thenThrow(new RuntimeException("Not found"));

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, diff, "feature", null));

        assertTrue(result.contains("Changed files"));
    }

    @Test
    void enrich_truncatesLargeFiles() {
        String diff = "diff --git a/large.txt b/large.txt\n+content";
        String largeContent = "x".repeat(new ReviewConfigProperties().getMaxSingleFileChars() + 1000);
        when(repositoryClient.getFileContent("owner", "repo", "large.txt", "feature"))
                .thenReturn(largeContent);

        String result = enricher.enrich(new EnrichmentContext("owner", "repo", 1L, diff, "feature", null));

        assertTrue(result.contains("truncated"));
    }
}

