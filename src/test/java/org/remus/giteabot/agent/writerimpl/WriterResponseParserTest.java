package org.remus.giteabot.agent.writerimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WriterResponseParserTest {

    private WriterResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new WriterResponseParser();
    }

    @Test
    void parse_withIntroTextBeforeRawJson_parsesStructuredPlan() {
        WriterPlan plan = parser.parse("""
                Now I have enough context. Let me inspect the exact filtering logic.
                {"qualityAssessment":"Ready to continue","requestFiles":[],"requestTools":[{"id":"d1","tool":"rg","args":["author|sender","src","-rn"]}],"clarifyingQuestions":[],"revisedIssueDraft":"","assumptions":[],"openQuestions":[],"readyToCreate":false}
                """);

        assertThat(plan.getQualityAssessment()).isEqualTo("Ready to continue");
        assertThat(plan.getRequestTools()).hasSize(1);
        assertThat(plan.getRequestTools().getFirst().getTool()).isEqualTo("rg");
        assertThat(plan.hasQuestions()).isFalse();
        assertThat(plan.hasContextRequests()).isTrue();
    }

    @Test
    void parse_withIntroTextBeforeJsonCodeFence_parsesStructuredPlan() {
        WriterPlan plan = parser.parse("""
                I found enough context.

                ```json
                {
                  "qualityAssessment": "Missing exact trigger conditions",
                  "clarifyingQuestions": ["Which webhook events should be accepted?"],
                  "readyToCreate": false
                }
                ```
                """);

        assertThat(plan.getQualityAssessment()).isEqualTo("Missing exact trigger conditions");
        assertThat(plan.getClarifyingQuestions()).containsExactly("Which webhook events should be accepted?");
        assertThat(plan.isReadyToCreate()).isFalse();
    }

    @Test
    void hasJsonPayload_distinguishesStructuredAnswersFromProse() {
        assertThat(parser.hasJsonPayload("{\"readyToCreate\":true}")).isTrue();
        assertThat(parser.hasJsonPayload("Intro text.\n```json\n{\"readyToCreate\":false}\n```")).isTrue();
        assertThat(parser.hasJsonPayload("I've reviewed the issue and it looks complete.")).isFalse();
        assertThat(parser.hasJsonPayload("")).isFalse();
        assertThat(parser.hasJsonPayload(null)).isFalse();
    }

    @Test
    void parse_plainTextWithoutJson_fallsBackToQualityAssessment() {
        WriterPlan plan = parser.parse("Please provide the expected behavior for non-authors.");

        assertThat(plan.getQualityAssessment()).isEqualTo("Please provide the expected behavior for non-authors.");
        // The clarifyingQuestions list must stay empty so the rendered comment
        // does not duplicate the prose (once as assessment, once as a question).
        assertThat(plan.getClarifyingQuestions()).isEmpty();
        assertThat(plan.isReadyToCreate()).isFalse();
        assertThat(plan.hasContextRequests()).isFalse();
    }
}

