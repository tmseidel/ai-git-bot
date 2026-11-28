package org.remus.giteabot.learning.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningCommandParserTest {

    private static LearningCommand parse(String body) {
        return LearningCommandParser.parse(body, "@ai_bot").orElseThrow();
    }

    @Test
    void aCommentWithoutALearningCommandIsNotParsed() {
        assertThat(LearningCommandParser.parse("@ai_bot please review again", "@ai_bot")).isEmpty();
        assertThat(LearningCommandParser.parse("@other_bot learning list", "@ai_bot")).isEmpty();
        assertThat(LearningCommandParser.parse(null, "@ai_bot")).isEmpty();
    }

    @Test
    void proposeTakesTheFeedbackAndTheMultilineLessonText() {
        LearningCommand command = parse("""
                @ai_bot learning propose fb31 Before reporting a missing permission check,
                inspect the enclosing security filter.""");

        assertThat(command).isEqualTo(new LearningCommand.Propose("FB31", null,
                "Before reporting a missing permission check,\n"
                        + "inspect the enclosing security filter."));
    }

    @Test
    void aPathsLineBeforeTheBodyRestrictsTheScope() {
        LearningCommand command = parse("""
                @ai_bot learning propose FB31
                paths: src/main/java/example/settings/**, ./docs/*.md
                Before reporting a missing permission check, inspect the filter.""");

        assertThat(command).isEqualTo(new LearningCommand.Propose("FB31",
                List.of("src/main/java/example/settings/**", "docs/*.md"),
                "Before reporting a missing permission check, inspect the filter."));
    }

    @Test
    void anUnsafePathPatternIsExplainedInsteadOfStored() {
        assertThat(parse("""
                @ai_bot learning propose FB31
                paths: ../secrets/**
                Text.""")).isInstanceOfSatisfying(LearningCommand.Help.class,
                help -> assertThat(help.message()).contains("../secrets/**"));
        assertThat(parse("""
                @ai_bot learning propose FB31
                paths: /etc/**
                Text.""")).isInstanceOf(LearningCommand.Help.class);
        assertThat(parse("""
                @ai_bot learning propose FB31
                paths: src/{a,b
                Text.""")).isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void proposeNeedsAFeedbackReferenceAndText() {
        assertThat(parse("@ai_bot learning propose O1234-F2 text"))
                .isInstanceOf(LearningCommand.Help.class);
        assertThat(parse("@ai_bot learning propose FB31"))
                .isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void reviseAcceptsNewWordingANewScopeOrBoth() {
        assertThat(parse("@ai_bot learning revise L17 Sharper wording."))
                .isEqualTo(new LearningCommand.Revise("L17", null, "Sharper wording."));
        assertThat(parse("@ai_bot learning revise l17\npaths: **"))
                .isEqualTo(new LearningCommand.Revise("L17", List.of(), null));
        assertThat(parse("@ai_bot learning revise L17\npaths: src/**\nBoth."))
                .isEqualTo(new LearningCommand.Revise("L17", List.of("src/**"), "Both."));
        assertThat(parse("@ai_bot learning revise L17@2 text"))
                .isInstanceOf(LearningCommand.Help.class);
        assertThat(parse("@ai_bot learning revise L17"))
                .isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void activationAndRejectionNameAnExactRevision() {
        assertThat(parse("@ai_bot learning activate L17@2"))
                .isEqualTo(new LearningCommand.Activate("L17", 2));
        assertThat(parse("@ai_bot learning reject l17@1"))
                .isEqualTo(new LearningCommand.Reject("L17", 1));
        assertThat(parse("@ai_bot learning activate L17")).isInstanceOfSatisfying(
                LearningCommand.Help.class, help -> assertThat(help.message()).contains("L17@2"));
    }

    @Test
    void disableTakesTheLessonAndAnOptionalReason() {
        assertThat(parse("@ai_bot learning disable L17"))
                .isEqualTo(new LearningCommand.Disable("L17", null));
        assertThat(parse("@ai_bot learning disable L17 Too broad,\nit hides real defects."))
                .isEqualTo(new LearningCommand.Disable("L17", "Too broad,\nit hides real defects."));
        assertThat(parse("@ai_bot learning disable L17@1"))
                .isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void listAcceptsAStateFilterAndACursor() {
        assertThat(parse("@ai_bot learning list")).isEqualTo(new LearningCommand.ListLessons(null, null));
        assertThat(parse("@ai_bot learning list active"))
                .isEqualTo(new LearningCommand.ListLessons(LessonState.ACTIVE, null));
        assertThat(parse("@ai_bot learning list candidates page: abc"))
                .isEqualTo(new LearningCommand.ListLessons(LessonState.CANDIDATE, "abc"));
        assertThat(parse("@ai_bot learning list page: abc"))
                .isEqualTo(new LearningCommand.ListLessons(null, "abc"));
        assertThat(parse("@ai_bot learning list everything"))
                .isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void showTakesOneLesson() {
        assertThat(parse("@ai_bot learning show L17")).isEqualTo(new LearningCommand.Show("L17"));
        assertThat(parse("@ai_bot learning show L17@2")).isEqualTo(new LearningCommand.Show("L17"));
        assertThat(parse("@ai_bot learning show")).isInstanceOf(LearningCommand.Help.class);
    }

    @Test
    void quotedAndFencedExamplesAreNotCommands() {
        assertThat(LearningCommandParser.parse("""
                > @ai_bot learning activate L17@1
                ```
                @ai_bot learning disable L17
                ```
                Thanks!""", "@ai_bot")).isEmpty();
    }

    @Test
    void onlyAKnownSubcommandIsUnmistakablyAddressed() {
        assertThat(LearningCommandParser.addressesLearning("@ai_bot learning list", "@ai_bot")).isTrue();
        assertThat(LearningCommandParser.addressesLearning(
                "@ai_bot learning from this, could you explain the change?", "@ai_bot")).isFalse();
        assertThat(LearningCommandParser.parse(
                "@ai_bot learning from this, could you explain the change?", "@ai_bot"))
                .get().isInstanceOf(LearningCommand.Help.class);
    }
}
