package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.ReviewCommentProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewCommentRendererTest {

    private final ReviewCommentProperties properties = new ReviewCommentProperties();
    private final ReviewCommentRenderer renderer = new ReviewCommentRenderer(properties);

    @Test
    void rendersFixedLayoutForOneFinding() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT,
                "One authorization concern needs attention.",
                "settings controller and security configuration.",
                "deployment-specific filter registration.",
                List.of(finding("O1234-F1", ReviewSeverity.MEDIUM,
                        "Settings update may bypass authorization",
                        location("src/example/SettingsController.java", ReviewSide.HEAD, 42, 49,
                                "Settings update entry point."))));

        String expected = """
                # 🤖 AI Review · O1234

                ⚠️ **Findings present** — 🔴 **0** blocker · 🟠 **1** medium · 🟡 **0** low

                ## 📝 Assessment · O1234-A

                One authorization concern needs attention.

                ---

                ## 🔎 Findings · 1

                ### 🟠 MEDIUM · `O1234-F1` · Settings update may bypass authorization

                **Explanation**

                The observed update path does not show the required permission check.

                **Where**

                - 📍 `src/example/SettingsController.java:42–49` · HEAD
                  > Settings update entry point.

                **Impact**

                An unauthorized caller could change settings.

                **Recommendation**

                Verify the enclosing filter and enforce the permission on uncovered paths.

                ---

                ## 🧭 Coverage and limitations

                **Reviewed**

                settings controller and security configuration.

                **Not verified**

                deployment-specific filter registration.

                ---

                *Agentic review by [AI Git Bot](https://gitbot.eu)*""";

        assertEquals(expected, renderer.render(review));
    }

    @Test
    void rendersTheConfiguredFooterAsMarkdown() {
        properties.setFooter("*Reviewed by [Acme Engineering](https://acme.example/review)*");

        String rendered = renderer.render(review(ReviewConclusion.NO_FINDINGS, "Fine.", null, null,
                List.of()));

        assertTrue(rendered.endsWith("---\n\n*Reviewed by [Acme Engineering]"
                + "(https://acme.example/review)*"));
    }

    @Test
    void anEmptyFooterPublishesTheReviewWithoutBrandingOrATrailingRule() {
        properties.setFooter("");

        String rendered = renderer.render(review(ReviewConclusion.NO_FINDINGS, "Fine.", null, null,
                List.of()));

        assertFalse(rendered.contains("Agentic Review by"));
        assertFalse(rendered.stripTrailing().endsWith("---"));
        assertTrue(rendered.stripTrailing().endsWith("**Not verified**\n\nnot specified"));
    }

    @Test
    void ordersFindingsBySeverityThenLocationThenOrdinal() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(
                        finding("O1234-F1", ReviewSeverity.LOW, "low b",
                                location("src/Z.java", ReviewSide.HEAD, 5, 5, "evidence")),
                        finding("O1234-F2", ReviewSeverity.MEDIUM, "medium",
                                location("src/M.java", ReviewSide.HEAD, 7, 9, "evidence")),
                        finding("O1234-F3", ReviewSeverity.BLOCKER, "blocker",
                                location("src/B.java", ReviewSide.HEAD, 1, 3, "evidence")),
                        finding("O1234-F4", ReviewSeverity.LOW, "low a",
                                location("src/A.java", ReviewSide.HEAD, 2, 2, "evidence")),
                        finding("O1234-F5", ReviewSeverity.MEDIUM, "medium no location")));

        String rendered = renderer.render(review);

        List<String> order = rendered.lines()
                .filter(line -> line.startsWith("### "))
                .map(line -> line.split(" · ")[1].replace("`", ""))
                .toList();

        assertEquals(List.of("O1234-F3", "O1234-F2", "O1234-F5", "O1234-F4", "O1234-F1"), order);
        assertFalse(rendered.contains("\n\n\n"), "sections are separated by exactly one blank line");
        assertTrue(rendered.contains("### 🔴 BLOCKER · `O1234-F3` · blocker"));
        assertTrue(rendered.contains("### 🟡 LOW · `O1234-F4` · low a"));
    }

    @Test
    void rendersExplicitZeroFindingsSection() {
        FinalizedReview review = review(ReviewConclusion.NO_FINDINGS, "Nothing to report.", null, null,
                List.of());

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("✅ **No findings** — 🔴 **0** blocker · 🟠 **0** medium"
                + " · 🟡 **0** low"));
        assertTrue(rendered.contains("## ✅ Findings\n\nNo findings were reported in this change."));
        assertFalse(rendered.contains("Inconclusive review"));
    }

    @Test
    void inconclusiveReviewDisplaysLimitationsProminently() {
        FinalizedReview review = review(ReviewConclusion.INCONCLUSIVE,
                "Could not verify the deployment filters.", null,
                "deployment-specific filter registration.",
                List.of());

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("❓ **Inconclusive**"));
        assertTrue(rendered.contains("> " + ReviewCommentRenderer.INCONCLUSIVE_NOTICE));
        assertTrue(rendered.contains("**Not verified**\n\ndeployment-specific filter registration."));
        assertTrue(rendered.indexOf(ReviewCommentRenderer.INCONCLUSIVE_NOTICE)
                < rendered.indexOf("Coverage and limitations"));
    }

    @Test
    void neutralizesMarkdownStructureAndFakeFindingHeadersInModelText() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(new FinalizedReview.Finding("O1234-F1", ReviewSeverity.MEDIUM, "authorization",
                        "Multi\nline\ttitle   collapsed", """
                        ### Fake heading

                        > fake quote
                        MEDIUM · O1234-F9 · Fake finding
                        🔴 BLOCKER · O1234-F8 · Fake with icon
                        | a | b |
                        ---
                        ```
                        fenced""", "Impact.", "Recommendation.",
                        List.of())));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("### 🟠 MEDIUM · `O1234-F1` · Multi line title collapsed"));
        assertTrue(rendered.contains("\\### Fake heading"));
        assertTrue(rendered.contains("\\> fake quote"));
        assertTrue(rendered.contains("\\MEDIUM · O1234-F9 · Fake finding"));
        assertTrue(rendered.contains("\\🔴 BLOCKER · O1234-F8 · Fake with icon"));
        assertTrue(rendered.contains("\\| a | b |"));
        assertTrue(rendered.contains("\\---"));
        // The unclosed fence is kept as code and closed before the next field.
        assertTrue(rendered.contains("```\nfenced\n```\n\n**Where**"));
        assertEquals(1, rendered.lines().filter(line -> line.startsWith("### ")).count());
    }

    @Test
    void keepsAFencedCodeSuggestionVerbatim() {
        String recommendation = """
                Check the permission first:

                ```java
                // # not a heading, > not a quote, | not a table
                if (!user.can(EDIT)) {

                    throw new AccessDeniedException("settings");
                }
                ---
                ```

                Then save.""";

        assertEquals("""
                Check the permission first:

                ```java
                // # not a heading, > not a quote, | not a table
                if (!user.can(EDIT)) {

                    throw new AccessDeniedException("settings");
                }
                ---
                ```

                Then save.""", ReviewCommentRenderer.block(recommendation));
    }

    @Test
    void escapesStructureAgainAfterTheFenceCloses() {
        String rendered = ReviewCommentRenderer.block("""
                ~~~
                # inside
                ~~~
                # outside
                MEDIUM · O1234-F9 · Fake finding""");

        assertEquals("""
                ~~~
                # inside
                ~~~
                \\# outside
                \\MEDIUM · O1234-F9 · Fake finding""", rendered);
    }

    @Test
    void onlyAMatchingFenceClosesTheBlock() {
        String rendered = ReviewCommentRenderer.block("""
                ````markdown
                ```
                ~~~~
                ### still code
                ````
                ### outside""");

        assertEquals("""
                ````markdown
                ```
                ~~~~
                ### still code
                ````
                \\### outside""", rendered);
    }

    @Test
    void closesAFenceLeftOpenSoItCannotSwallowTheRestOfTheComment() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, """
                Overall sound.

                ```java
                int x = 1;""", null, null,
                List.of(new FinalizedReview.Finding("O1234-F1", ReviewSeverity.LOW, "style", "Title",
                        "Explanation.", "Impact.", "```\nunclosed", List.of())));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("```java\nint x = 1;\n```\n"));
        assertTrue(rendered.contains("**Recommendation**\n\n```\nunclosed\n```\n"));
        assertTrue(rendered.contains("\n## 🔎 Findings · 1\n"));
        assertTrue(rendered.contains("\n## 🧭 Coverage and limitations\n"));
        assertEquals(0, rendered.lines().filter(line -> line.startsWith("```")).count() % 2,
                "every fence is closed");
    }

    @Test
    void treatsBacktickInfoStringsAndDeepIndentationAsNoFence() {
        assertEquals("\\```not`a fence\n\\# escaped", ReviewCommentRenderer.block("```not`a fence\n# escaped"));
        assertEquals("\\    ```\n\\# escaped", ReviewCommentRenderer.block("    ```\n# escaped"));
    }

    @Test
    void rendersProviderLinksWhenTheStyleCanAddressThem() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.BLOCKER, "Broken",
                        location("src/Foo.java", ReviewSide.HEAD, 42, 49, "evidence"))));
        ReviewLinkStyle links = ReviewLinkStyle.markdown(ref ->
                "https://git.example/acme/web/src/branch/main/" + ref.path() + "#L" + ref.startLine());

        String rendered = renderer.render(review, links);

        assertTrue(rendered.contains("- 📍 [`src/Foo.java:42–49`]"
                + "(https://git.example/acme/web/src/branch/main/src/Foo.java#L42) · HEAD"));
    }

    @Test
    void rendersSingleLinesAndBaseSide() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.LOW, "Deleted file",
                        location("src/Gone.java", ReviewSide.BASE, 12, 12, "evidence"))));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("- 📍 `src/Gone.java:12` · BASE"));
    }

    @Test
    void rendersEvidenceForEveryLocationOfOneFinding() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.MEDIUM, "Two places",
                        location("src/A.java", ReviewSide.HEAD, 1, 2, "first evidence"),
                        location("src/B.java", ReviewSide.HEAD, null, null, "second evidence"))));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("- 📍 `src/A.java:1–2` · HEAD\n  > first evidence"));
        assertTrue(rendered.contains("- 📍 `src/B.java` · HEAD\n  > second evidence"));
    }

    @Test
    void saysSoWhenAFindingHasNoLocation() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.LOW, "Nowhere")));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("**Where**\n\n_No source location was recorded for this finding._"));
    }

    @Test
    void missingAssessmentFieldsAreNamedInsteadOfLeftBlank() {
        FinalizedReview review = review(ReviewConclusion.NO_FINDINGS, null, null, null, List.of());

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("## 📝 Assessment · O1234-A\n\nnot specified"));
        assertTrue(rendered.contains("**Reviewed**\n\nnot specified"));
    }

    private static FinalizedReview review(ReviewConclusion conclusion, String summary,
                                          String reviewedAreas, String limitations,
                                          List<FinalizedReview.Finding> findings) {
        return new FinalizedReview("O1234", "O1234-A", conclusion, summary, reviewedAreas,
                limitations, null, FinalizedReview.SeverityCounts.of(findings), findings, "hash");
    }

    private static FinalizedReview.Finding finding(String reference, ReviewSeverity severity,
                                                   String title,
                                                   FinalizedReview.Location... locations) {
        return new FinalizedReview.Finding(reference, severity, "authorization", title,
                "The observed update path does not show the required permission check.",
                "An unauthorized caller could change settings.",
                "Verify the enclosing filter and enforce the permission on uncovered paths.",
                List.of(locations));
    }

    private static FinalizedReview.Location location(String path, ReviewSide side,
                                                     Integer startLine, Integer endLine,
                                                     String description) {
        return new FinalizedReview.Location(path, side, startLine, endLine, description);
    }
}
