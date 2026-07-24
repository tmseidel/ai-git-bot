package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReviewServiceTest {

    @Test
    void parseFormalReviewResult_nullAndEmptyYieldsNone() {
        assertThat(AgentReviewService.parseFormalReviewResult(null, thresholds(0, null, null)).action()).isNull();
        assertThat(AgentReviewService.parseFormalReviewResult("", thresholds(0, null, null)).action()).isNull();
        assertThat(AgentReviewService.parseFormalReviewResult("   ", thresholds(0, null, null)).action()).isNull();
    }

    @Test
    void parseFormalReviewResult_onlyBlockerThreshold_zeroBlockersApproves() {
        String review = """
                ## Review
                Looks good.

                ```json
                {"blocker": 0, "medium": 1, "low": 2}
                ```""";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, null, null));
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).doesNotContain("blocker", "medium", "low");
    }

    @Test
    void parseFormalReviewResult_onlyBlockerThreshold_oneBlockerRequestsChanges() {
        String review = """
                ## Review
                There is a critical issue.

                ```json
                {"blocker": 1, "medium": 0, "low": 0}
                ```""";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, null, null));
        assertThat(result.action()).isEqualTo(PostReviewAction.REQUEST_CHANGES);
        assertThat(result.reviewText()).doesNotContain("blocker", "medium", "low");
    }

    @Test
    void parseFormalReviewResult_multipleThresholds_allWithinLimitsApproves() {
        String review = "Here is my review.\n\n{\"blocker\": 0, \"medium\": 1, \"low\": 2}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 2, 3));
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).isEqualTo("Here is my review.");
    }

    @Test
    void parseFormalReviewResult_multipleThresholds_anyExceededRequestsChanges() {
        String review = "Here is my review.\n\n{\"blocker\": 0, \"medium\": 3, \"low\": 2}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 2, 3));
        assertThat(result.action()).isEqualTo(PostReviewAction.REQUEST_CHANGES);
    }

    @Test
    void parseFormalReviewResult_emptyThresholds_ignoredSeveritiesDoNotBlockApproval() {
        String review = "Here is my review.\n\n{\"blocker\": 0, \"medium\": 5, \"low\": 10}";

        // Only blocker is configured; medium/low are ignored.
        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, null, null));
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
    }

    @Test
    void parseFormalReviewResult_allThresholdsEmpty_yieldsNone() {
        String review = "Here is my review.\n\n{\"blocker\": 0, \"medium\": 0, \"low\": 0}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(null, null, null));
        assertThat(result.action()).isEqualTo(PostReviewAction.NONE);
    }

    @Test
    void parseFormalReviewResult_bareJsonAtEnd() {
        String review = "Here is my review text.\n\n{\"blocker\": 0, \"medium\": 0, \"low\": 0}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).isEqualTo("Here is my review text.");
    }

    @Test
    void parseFormalReviewResult_noClassificationBlock() {
        String review = "Just a plain review with no classification.";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }

    @Test
    void parseFormalReviewResult_malformedFencedBlock_strippedWithNoAction() {
        String review = """
                ## Review
                Some text.

                ```json
                {"blocker": "not-a-number"}
                ```""";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isEqualTo(PostReviewAction.NONE);
        assertThat(result.reviewText()).doesNotContain("blocker", "not-a-number", "```");
    }

    @Test
    void parseFormalReviewResult_malformedBareBlock_strippedWithNoAction() {
        String review = "Here is my review text.\n\n{\"blocker\": \"invalid\"}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, null, null));
        assertThat(result.action()).isEqualTo(PostReviewAction.NONE);
        assertThat(result.reviewText()).isEqualTo("Here is my review text.");
    }

    @Test
    void parseFormalReviewResult_missingSeverityFields_defaultToZero() {
        String review = "Here is my review.\n\n{\"blocker\": 0}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
    }

    @Test
    void parseFormalReviewResult_midDocumentJsonIsIgnored() {
        // JSON block in the middle, not at end — should not be parsed.
        String review = """
                ```json
                {"blocker": 0, "medium": 0, "low": 0}
                ```
                But wait, there's more text after this.""";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }

    @Test
    void parseFormalReviewResult_loneTextarea_reviewTextPreserved() {
        String review = "Simple review text with no JSON blocks.";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, 0, 0));
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }

    private static AgentReviewService.SeverityThresholds thresholds(Integer blocker, Integer medium, Integer low) {
        return new AgentReviewService.SeverityThresholds(blocker, medium, low);
    }
}
