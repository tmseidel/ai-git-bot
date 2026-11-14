package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewCommentRendererTest {

    private final ReviewCommentRenderer renderer = new ReviewCommentRenderer();

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
                AI Review · O1234

                Assessment · O1234-A
                One authorization concern needs attention.

                Findings: 0 blocker · 1 medium · 0 low

                MEDIUM · O1234-F1 · Settings update may bypass authorization
                Explanation: The observed update path does not show the required permission check.
                Location: src/example/SettingsController.java:42–49 (HEAD)
                Evidence: Settings update entry point.
                Impact: An unauthorized caller could change settings.
                Recommendation: Verify the enclosing filter and enforce the permission on uncovered paths.

                Coverage and limitations
                Reviewed: settings controller and security configuration.
                Not verified: deployment-specific filter registration.""";

        assertEquals(expected, renderer.render(review));
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
                .filter(line -> line.matches("^(BLOCKER|MEDIUM|LOW) · O1234-F\\d+ · .*"))
                .map(line -> line.split(" · ")[1])
                .toList();

        assertEquals(List.of("O1234-F3", "O1234-F2", "O1234-F5", "O1234-F4", "O1234-F1"), order);
    }

    @Test
    void rendersExplicitZeroFindingsSection() {
        FinalizedReview review = review(ReviewConclusion.NO_FINDINGS, "Nothing to report.", null, null,
                List.of());

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("Findings: 0 blocker · 0 medium · 0 low"));
        assertTrue(rendered.contains("No findings were reported."));
        assertFalse(rendered.contains("Inconclusive review"));
    }

    @Test
    void inconclusiveReviewDisplaysLimitationsProminently() {
        FinalizedReview review = review(ReviewConclusion.INCONCLUSIVE,
                "Could not verify the deployment filters.", null,
                "deployment-specific filter registration.",
                List.of());

        String rendered = renderer.render(review);

        assertTrue(rendered.contains(ReviewCommentRenderer.INCONCLUSIVE_NOTICE));
        assertTrue(rendered.contains("Not verified: deployment-specific filter registration."));
        assertTrue(rendered.indexOf(ReviewCommentRenderer.INCONCLUSIVE_NOTICE)
                < rendered.indexOf("Coverage and limitations"));
    }

    @Test
    void neutralizesMarkdownStructureAndFakeFindingHeadersInModelText() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(new FinalizedReview.Finding("O1234-F1", ReviewSeverity.MEDIUM, "authorization",
                        "Multi\nline\ttitle   collapsed", "### Fake heading\n\n> fake quote\n"
                                + "MEDIUM · O1234-F9 · Fake finding", "Impact.", "Recommendation.",
                        List.of())));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("MEDIUM · O1234-F1 · Multi line title collapsed"));
        assertTrue(rendered.contains("\\### Fake heading"));
        assertTrue(rendered.contains("\\> fake quote"));
        assertTrue(rendered.contains("\\MEDIUM · O1234-F9 · Fake finding"));
    }

    @Test
    void rendersProviderLinksWhenTheStyleCanAddressThem() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.BLOCKER, "Broken",
                        location("src/Foo.java", ReviewSide.HEAD, 42, 49, "evidence"))));
        ReviewLinkStyle links = ReviewLinkStyle.markdown(ref ->
                "https://git.example/acme/web/src/branch/main/" + ref.path() + "#L" + ref.startLine());

        String rendered = renderer.render(review, links);

        assertTrue(rendered.contains("Location: [src/Foo.java:42–49 (HEAD)]"
                + "(https://git.example/acme/web/src/branch/main/src/Foo.java#L42)"));
    }

    @Test
    void rendersSingleLinesAndBaseSide() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.LOW, "Deleted file",
                        location("src/Gone.java", ReviewSide.BASE, 12, 12, "evidence"))));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("Location: src/Gone.java:12 (BASE)"));
    }

    @Test
    void rendersEvidenceForEveryLocationOfOneFinding() {
        FinalizedReview review = review(ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null,
                List.of(finding("O1234-F1", ReviewSeverity.MEDIUM, "Two places",
                        location("src/A.java", ReviewSide.HEAD, 1, 2, "first evidence"),
                        location("src/B.java", ReviewSide.HEAD, null, null, "second evidence"))));

        String rendered = renderer.render(review);

        assertTrue(rendered.contains("Location: src/A.java:1–2 (HEAD)\nEvidence: first evidence"));
        assertTrue(rendered.contains("Location: src/B.java (HEAD)\nEvidence: second evidence"));
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
