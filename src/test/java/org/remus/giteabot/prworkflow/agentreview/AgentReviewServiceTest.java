package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReviewServiceTest {

    @Test
    void parseDecision_nullAndEmptyYieldNone() {
        assertThat(AgentReviewService.parseDecision(null).action()).isNull();
        assertThat(AgentReviewService.parseDecision("").action()).isNull();
        assertThat(AgentReviewService.parseDecision("   ").action()).isNull();
    }

    @Test
    void parseDecision_approveInFencedBlock() {
        String review = """
                ## Review
                Looks good.

                ```json
                {"decision": "APPROVE"}
                ```""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).doesNotContain("decision");
    }

    @Test
    void parseDecision_requestChangesInFencedBlock() {
        String review = """
                ## Review
                There are issues.

                ```json
                {"decision": "REQUEST_CHANGES"}
                ```""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isEqualTo(PostReviewAction.REQUEST_CHANGES);
        assertThat(result.reviewText()).doesNotContain("REQUEST_CHANGES");
    }

    @Test
    void parseDecision_noneInFencedBlock() {
        String review = """
                ## Review
                Minor issues but not blocking.

                ```json
                {"decision": "NONE"}
                ```""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isEqualTo(PostReviewAction.NONE);
        assertThat(result.reviewText()).doesNotContain("NONE");
    }

    @Test
    void parseDecision_bareJsonAtEnd() {
        String review = "Here is my review text.\n\n{\"decision\":\"APPROVE\"}";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).isEqualTo("Here is my review text.");
    }

    @Test
    void parseDecision_noDecisionBlock() {
        String review = "Just a plain review with no decision.";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }

    @Test
    void parseDecision_malformedFencedBlock_fallsBackToNone() {
        String review = """
                ## Review
                Some text.

                ```json
                {"decision": "INVALID_VALUE"}
                ```""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isNull();
    }

    @Test
    void parseDecision_approveFencedWithExtraFields() {
        String review = """
                ## Review
                All good.

                ```json
                { "decision": "APPROVE", "confidence": 0.95 }
                ```""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isEqualTo(PostReviewAction.APPROVE);
        assertThat(result.reviewText()).doesNotContain("decision");
    }

    @Test
    void parseDecision_midDocumentJsonIsIgnored() {
        // JSON block in the middle, not at end — should not be parsed
        String review = """
                ```json
                {"decision": "APPROVE"}
                ```
                But wait, there's more text after this.""";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }

    @Test
    void parseDecision_loneTextarea_reviewTextPreserved() {
        String review = "Simple review text with no JSON blocks.";

        var result = AgentReviewService.parseDecision(review);
        assertThat(result.action()).isNull();
        assertThat(result.reviewText()).isEqualTo(review);
    }
}
