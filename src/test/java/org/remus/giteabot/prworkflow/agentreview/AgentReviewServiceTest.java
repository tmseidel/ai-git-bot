package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.repository.PostReviewAction;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentReviewServiceTest {

    @Test
    void completeSnapshotRendersEveryRecordedFindingFromTheSameData() {
        var findings = List.of(
                new ReviewDraft.Finding("F1", "blocker", "security", "SQL injection", "Concrete evidence", "A.java", 7),
                new ReviewDraft.Finding("F2", "low", null, "Typo", "Misspelled label", null, null));
        var draft = new ReviewDraft(1, 2, ReviewDraft.Status.COMPLETE, 3, true, "Reviewed change", List.of(), findings, null);
        assertThat(draft.render()).contains("Reviewed change", "F1 [BLOCKER] SQL injection", "Concrete evidence",
                "A.java:7", "F2 [LOW] Typo", "Misspelled label");
    }

    @Test
    void anUnsealedDraftCannotBecomeReviewMarkdown() {
        var draft = new ReviewDraft(1, 1, ReviewDraft.Status.DRAFT, 1, true, "", List.of(), List.of(), null);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(draft::render);
    }

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

    @Test
    void parseFormalReviewResult_findingsArray_extractedWithKnownKeysOnly() {
        String review = """
                ## Review
                Found issues.

                ```json
                {"blocker": 1, "medium": 0, "low": 0, "findings": [
                  {"severity": "blocker", "category": "security", "title": "SQL injection",
                   "file": "src/Foo.java", "line": 42, "cwe": "CWE-89", "owasp": "A03:2021",
                   "unknownField": "dropped"},
                  "not-an-object",
                  {"severity": "medium", "line": "not-an-int"}
                ]}
                ```""";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(0, null, null));

        assertThat(result.action()).isEqualTo(PostReviewAction.REQUEST_CHANGES);
        assertThat(result.classification()).isEqualTo(new AgentReviewService.SeverityClassification(1, 0, 0));
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings().get(0))
                .containsEntry("severity", "blocker")
                .containsEntry("category", "security")
                .containsEntry("title", "SQL injection")
                .containsEntry("file", "src/Foo.java")
                .containsEntry("line", 42)
                .containsEntry("cwe", "CWE-89")
                .containsEntry("owasp", "A03:2021")
                .doesNotContainKey("unknownField");
        assertThat(result.findings().get(1))
                .containsEntry("severity", "medium")
                .doesNotContainKey("line");
    }

    @Test
    void parseFormalReviewResult_nonArrayFindings_emptyFindings() {
        String review = "Review text.\n\n{\"blocker\": 0, \"medium\": 1, \"low\": 0, \"findings\": \"nope\"}";

        var result = AgentReviewService.parseFormalReviewResult(review, thresholds(1, 1, 1));

        assertThat(result.findings()).isEmpty();
        assertThat(result.classification()).isEqualTo(new AgentReviewService.SeverityClassification(0, 1, 0));
    }

    @Test
    void parseFormalReviewResult_noClassificationBlock_nullClassificationEmptyFindings() {
        var result = AgentReviewService.parseFormalReviewResult("Plain review.", thresholds(0, 0, 0));

        assertThat(result.classification()).isNull();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void publishFindingEvents_structuredFindings_oneEventPerFinding() {
        EventHookPublisher publisher = mock(EventHookPublisher.class);
        Bot bot = bot();
        var parsed = new AgentReviewService.ParseResult("review", PostReviewAction.APPROVE,
                new AgentReviewService.SeverityClassification(1, 1, 0),
                List.of(Map.of("severity", "blocker", "title", "A"), Map.of("severity", "medium", "title", "B")));

        AgentReviewService.publishFindingEvents(publisher, bot, parsed, "o", "r", 7L);

        verify(publisher, times(2)).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED),
                eq(bot), eq("o"), eq("r"), eq(7L), isNull(), anyMap());
        verify(publisher).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED),
                eq(bot), eq("o"), eq("r"), eq(7L), isNull(),
                argThat(data -> data.get("finding") instanceof Map<?, ?> f && "A".equals(f.get("title"))));
    }

    @Test
    void publishFindingEvents_countsOnly_singleAggregateEvent() {
        EventHookPublisher publisher = mock(EventHookPublisher.class);
        Bot bot = bot();
        var parsed = new AgentReviewService.ParseResult("review", PostReviewAction.REQUEST_CHANGES,
                new AgentReviewService.SeverityClassification(2, 1, 3), List.of());

        AgentReviewService.publishFindingEvents(publisher, bot, parsed, "o", "r", 7L);

        verify(publisher, times(1)).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED),
                eq(bot), eq("o"), eq("r"), eq(7L), isNull(),
                argThat(data -> data.get("findingCounts") instanceof Map<?, ?> c
                        && Integer.valueOf(2).equals(c.get("blocker"))
                        && Integer.valueOf(1).equals(c.get("medium"))
                        && Integer.valueOf(3).equals(c.get("low"))));
    }

    @Test
    void publishFindingEvents_malformedFindingsWithCounts_fallsBackToAggregate() {
        EventHookPublisher publisher = mock(EventHookPublisher.class);
        Bot bot = bot();
        // Model returned counts but the findings array was non-array/malformed → parser
        // yielded an empty list; the aggregate event must still carry the counts.
        var result = AgentReviewService.parseFormalReviewResult(
                "Review.\n\n{\"blocker\": 1, \"medium\": 0, \"low\": 0, \"findings\": 42}",
                thresholds(0, null, null));

        AgentReviewService.publishFindingEvents(publisher, bot, result, "o", "r", 7L);

        verify(publisher, times(1)).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED),
                eq(bot), eq("o"), eq("r"), eq(7L), isNull(),
                argThat(data -> data.containsKey("findingCounts") && !data.containsKey("finding")));
    }

    @Test
    void publishFindingEvents_zeroCountsAndNoFindings_noEvent() {
        EventHookPublisher publisher = mock(EventHookPublisher.class);
        var parsed = new AgentReviewService.ParseResult("review", PostReviewAction.APPROVE,
                new AgentReviewService.SeverityClassification(0, 0, 0), List.of());

        AgentReviewService.publishFindingEvents(publisher, bot(), parsed, "o", "r", 7L);

        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void publishFindingEvents_noClassification_noEvent() {
        EventHookPublisher publisher = mock(EventHookPublisher.class);
        var parsed = AgentReviewService.ParseResult.noDecision("review");

        AgentReviewService.publishFindingEvents(publisher, bot(), parsed, "o", "r", 7L);

        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any(), anyMap());
    }

    private static Bot bot() {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("review-bot");
        return bot;
    }

    private static AgentReviewService.SeverityThresholds thresholds(Integer blocker, Integer medium, Integer low) {
        return new AgentReviewService.SeverityThresholds(blocker, medium, low);
    }
}
