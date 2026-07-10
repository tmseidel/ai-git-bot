package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffFileFilterTest {

    private static final String LOCK = """
            diff --git a/package-lock.json b/package-lock.json
            index 111..222 100644
            --- a/package-lock.json
            +++ b/package-lock.json
            @@ -1 +1 @@
            -old
            +new
            """;

    private static final String SRC = """
            diff --git a/src/Foo.java b/src/Foo.java
            index aaa..bbb 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1 +1 @@
            -int x = 1;
            +int x = 2;
            """;

    private static final String MIN_JS = """
            diff --git a/web/app.min.js b/web/app.min.js
            index ccc..ddd 100644
            --- a/web/app.min.js
            +++ b/web/app.min.js
            @@ -1 +1 @@
            -a
            +b
            """;

    @Test
    void parsePatterns_splitsTrimsAndDropsBlanks() {
        assertEquals(List.of("*.lock", "package-lock.json", "*.min.js"),
                DiffFileFilter.parsePatterns(" *.lock , package-lock.json ,, *.min.js "));
    }

    @Test
    void parsePatterns_emptyOrNull_returnsEmptyList() {
        assertTrue(DiffFileFilter.parsePatterns(null).isEmpty());
        assertTrue(DiffFileFilter.parsePatterns("   ").isEmpty());
    }

    @Test
    void filter_emptyPatterns_returnsDiffUnchanged() {
        String diff = LOCK + SRC;
        assertEquals(diff, DiffFileFilter.filter(diff, List.of()));
    }

    @Test
    void filter_nullDiff_returnsNull() {
        assertEquals(null, DiffFileFilter.filter(null, List.of("*.lock")));
    }

    @Test
    void filter_exactFilename_removesSection() {
        String result = DiffFileFilter.filter(LOCK + SRC, List.of("package-lock.json"));
        assertFalse(result.contains("package-lock.json"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_extensionGlob_removesMatchingSection() {
        String result = DiffFileFilter.filter(SRC + MIN_JS, List.of("*.min.js"));
        assertFalse(result.contains("app.min.js"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_nestedGlob_matchesByFullPath() {
        String result = DiffFileFilter.filter(SRC + MIN_JS, List.of("**/*.min.js"));
        assertFalse(result.contains("app.min.js"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_multiplePatterns_removesAllMatches() {
        String result = DiffFileFilter.filter(LOCK + SRC + MIN_JS,
                List.of("*.lock", "package-lock.json", "*.min.js"));
        assertFalse(result.contains("package-lock.json"));
        assertFalse(result.contains("app.min.js"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_noMatch_returnsAllSections() {
        String result = DiffFileFilter.filter(SRC, List.of("*.lock"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_deletedFile_matchedByOldPath() {
        String deleted = """
                diff --git a/yarn.lock b/yarn.lock
                deleted file mode 100644
                index eee..000
                --- a/yarn.lock
                +++ /dev/null
                @@ -1 +0,0 @@
                -gone
                """;
        String result = DiffFileFilter.filter(deleted + SRC, List.of("yarn.lock"));
        assertFalse(result.contains("yarn.lock"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void filter_notADiff_returnsInputUnchanged() {
        String notDiff = "just some text\nwith no diff header";
        assertEquals(notDiff, DiffFileFilter.filter(notDiff, List.of("*.lock")));
    }
}
