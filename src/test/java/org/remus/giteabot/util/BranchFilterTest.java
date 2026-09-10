package org.remus.giteabot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BranchFilter}: the glob allowlist that gates PR-workflow
 * triggering (Issue #374). Covers the acceptance scenarios: empty filter,
 * wildcard, exact/short match, non-match, and full-ref ({@code refs/heads/})
 * matching, plus the gobwas/glob syntax subset and multi-pattern lists.
 */
class BranchFilterTest {

    // ---- Empty / null / wildcard: no-op (back-compat) ----

    @Test
    void nullFilter_allowsAll() {
        assertTrue(BranchFilter.matches(null, "develop"));
        assertTrue(BranchFilter.matches(null, null));
    }

    @Test
    void blankFilter_allowsAll() {
        assertTrue(BranchFilter.matches("", "develop"));
        assertTrue(BranchFilter.matches("   ", "develop"));
    }

    @Test
    void singleStarFilter_allowsAll() {
        assertTrue(BranchFilter.matches("*", "develop"));
        assertTrue(BranchFilter.matches("*", "feature/x"));
        assertTrue(BranchFilter.matches("*", null));
    }

    @Test
    void doubleStarFilter_allowsAll() {
        assertTrue(BranchFilter.matches("**", "feature/x"));
    }

    // ---- Exact / short-name matching ----

    @Test
    void exactBranch_matches() {
        assertTrue(BranchFilter.matches("develop", "develop"));
    }

    @Test
    void exactBranch_noMatch() {
        assertFalse(BranchFilter.matches("develop", "main"));
    }

    @Test
    void exactBranch_doesNotMatchSuperset() {
        // 'devel' must not match 'develop' (anchored full match, not prefix)
        assertFalse(BranchFilter.matches("devel", "develop"));
    }

    // ---- Glob syntax ----

    @Test
    void starWildcard_matchesAnyRun() {
        assertTrue(BranchFilter.matches("feature/*", "feature/xyz"));
        assertTrue(BranchFilter.matches("feature/*", "feature/nested/branch"));
        assertFalse(BranchFilter.matches("feature/*", "release"));
    }

    @Test
    void questionMark_matchesSingleChar() {
        assertTrue(BranchFilter.matches("hotfix-v?", "hotfix-v1"));
        assertFalse(BranchFilter.matches("hotfix-v?", "hotfix-v10"));
    }

    @Test
    void charClass_matches() {
        assertTrue(BranchFilter.matches("release-[0-9]", "release-3"));
        assertFalse(BranchFilter.matches("release-[0-9]", "release-x"));
    }

    @Test
    void negatedCharClass_matches() {
        assertTrue(BranchFilter.matches("release-[!0-9]", "release-x"));
        assertFalse(BranchFilter.matches("release-[!0-9]", "release-3"));
    }

    @Test
    void alternation_matches() {
        assertTrue(BranchFilter.matches("{develop,main}", "main"));
        assertTrue(BranchFilter.matches("{develop,main}", "develop"));
        assertFalse(BranchFilter.matches("{develop,main}", "feature"));
    }

    @Test
    void doubleStar_matchesAcrossSlashes() {
        assertTrue(BranchFilter.matches("feature/**", "feature/a/b/c"));
    }

    // ---- Full ref name matching (refs/heads/, refs/tags/) ----

    @Test
    void fullHeadRefPattern_matchesShortBranch() {
        assertTrue(BranchFilter.matches("refs/heads/develop", "develop"));
    }

    @Test
    void fullHeadRefPattern_matchesFullRef() {
        assertTrue(BranchFilter.matches("refs/heads/develop", "refs/heads/develop"));
    }

    @Test
    void fullTagRefPattern_matchesFullRef() {
        assertTrue(BranchFilter.matches("refs/tags/v*", "refs/tags/v1.2.3"));
    }

    @Test
    void fullHeadRefPattern_noMatch() {
        assertFalse(BranchFilter.matches("refs/heads/develop", "main"));
    }

    @Test
    void shortPattern_doesNotMatchFullRefUnlessStarred() {
        // A bare short pattern is matched against the short ref, not the full ref.
        assertTrue(BranchFilter.matches("develop", "develop"));
        // A full ref value is only matched by a full-ref (or wildcard) pattern.
        assertFalse(BranchFilter.matches("develop", "refs/heads/develop"));
    }

    // ---- Multi-pattern (comma-separated) ----

    @Test
    void multiPattern_matchesAny() {
        assertTrue(BranchFilter.matches("develop,feature/*", "feature/x"));
        assertTrue(BranchFilter.matches("develop,feature/*", "develop"));
        assertFalse(BranchFilter.matches("develop,feature/*", "release/1.0"));
    }

    @Test
    void multiPattern_ignoresWhitespaceAndEmptyParts() {
        assertTrue(BranchFilter.matches(" develop , feature/* , ", "feature/x"));
        assertTrue(BranchFilter.matches(",,develop,,", "develop"));
    }

    // ---- Null / empty ref edge cases ----

    @Test
    void nonWildcardPattern_withNullRef_isBlocked() {
        // No ref information → only a wildcard/empty filter can start the workflow.
        assertFalse(BranchFilter.matches("develop", null));
        assertFalse(BranchFilter.matches("feature/*", ""));
    }
}
