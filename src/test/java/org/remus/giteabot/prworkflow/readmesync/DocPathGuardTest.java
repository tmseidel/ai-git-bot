package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocPathGuardTest {

    private static final List<String> PATTERNS =
            List.of("README.md", "README.*.md", "doc/**/*.md");

    @Test
    void allowsExactReadme() {
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "README.md"));
    }

    @Test
    void allowsTranslatedReadmeVariant() {
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "README.de.md"));
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "README.zh-CN.md"));
    }

    @Test
    void allowsNestedDocUnderDoubleStar() {
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "doc/setup/install.md"));
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "doc/a/b/c/deep.md"));
    }

    @Test
    void rejectsNonMarkdownEvenWhenPathMatchesShape() {
        assertFalse(DocPathGuard.isAllowedDocPath(List.of("doc/**/*"), "doc/setup/install.txt"));
        assertFalse(DocPathGuard.isAllowedDocPath(PATTERNS, "README.md.bak"));
    }

    @Test
    void rejectsProductionCode() {
        assertFalse(DocPathGuard.isAllowedDocPath(PATTERNS, "src/main/java/App.java"));
        assertFalse(DocPathGuard.isAllowedDocPath(PATTERNS, "pom.xml"));
    }

    @Test
    void rejectsMarkdownOutsideConfiguredScope() {
        // *.md that is not README.md and not under doc/
        assertFalse(DocPathGuard.isAllowedDocPath(PATTERNS, "CONTRIBUTING.md"));
        assertFalse(DocPathGuard.isAllowedDocPath(PATTERNS, "src/notes.md"));
    }

    @Test
    void singleStarDoesNotCrossSlash() {
        List<String> patterns = List.of("doc/*.md");
        assertTrue(DocPathGuard.isAllowedDocPath(patterns, "doc/intro.md"));
        assertFalse(DocPathGuard.isAllowedDocPath(patterns, "doc/sub/intro.md"));
    }

    @Test
    void emptyPatternsRejectEverything() {
        assertFalse(DocPathGuard.isAllowedDocPath(List.of(), "README.md"));
        assertFalse(DocPathGuard.isAllowedDocPath(null, "README.md"));
    }

    @Test
    void normalizesLeadingDotSlashAndBackslashes() {
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "./README.md"));
        assertTrue(DocPathGuard.isAllowedDocPath(PATTERNS, "doc\\setup\\install.md"));
    }

    @Test
    void isMarkdownDetectsBothExtensions() {
        assertTrue(DocPathGuard.isMarkdown("x.md"));
        assertTrue(DocPathGuard.isMarkdown("X.MARKDOWN"));
        assertFalse(DocPathGuard.isMarkdown("x.rst"));
        assertFalse(DocPathGuard.isMarkdown(null));
    }

    @Test
    void parsePatternsSplitsOnCommasAndNewlines() {
        List<String> parsed = DocPathGuard.parsePatterns("README.md, README.*.md\n doc/**/*.md \n");
        assertEquals(List.of("README.md", "README.*.md", "doc/**/*.md"), parsed);
    }

    @Test
    void parsePatternsBlankGivesEmpty() {
        assertTrue(DocPathGuard.parsePatterns("").isEmpty());
        assertTrue(DocPathGuard.parsePatterns(null).isEmpty());
    }
}
