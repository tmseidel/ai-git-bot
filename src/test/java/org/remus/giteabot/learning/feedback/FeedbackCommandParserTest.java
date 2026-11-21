package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackCommandParserTest {

    private static final String ALIAS = "@ai_bot";

    @Test
    void parsesTheDocumentedForms() {
        FeedbackCommand command = parse("@ai_bot feedback O1234-F2 incorrect Authorization is "
                + "enforced in SettingsSecurityFilter.");
        assertThat(command).isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit -> {
            assertThat(submit.target().type()).isEqualTo(FeedbackTargetType.FINDING);
            assertThat(submit.target().reference()).isEqualTo("O1234-F2");
            assertThat(submit.verdict()).isEqualTo(FeedbackVerdict.INCORRECT);
            assertThat(submit.reason()).isEqualTo("Authorization is enforced in "
                    + "SettingsSecurityFilter.");
        });

        assertThat(parse("@ai_bot feedback O1234 useful")).isInstanceOfSatisfying(
                FeedbackCommand.Submit.class, submit -> {
                    assertThat(submit.target().type()).isEqualTo(FeedbackTargetType.OUTPUT);
                    assertThat(submit.verdict()).isEqualTo(FeedbackVerdict.USEFUL);
                    assertThat(submit.reason()).isEmpty();
                });

        assertThat(parse("@ai_bot feedback O1234-A needs-improvement Please explain"))
                .isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit -> {
                    assertThat(submit.target().type()).isEqualTo(FeedbackTargetType.ASSESSMENT);
                    assertThat(submit.verdict()).isEqualTo(FeedbackVerdict.NEEDS_IMPROVEMENT);
                });

        assertThat(parse("@ai_bot feedback retract FB31"))
                .isEqualTo(new FeedbackCommand.Retract("FB31"));
        assertThat(parse("@ai_bot feedback show FB31"))
                .isEqualTo(new FeedbackCommand.Show("FB31"));
        assertThat(parse("@ai_bot feedback list"))
                .isEqualTo(new FeedbackCommand.ListRecords(false, null));
        assertThat(parse("@ai_bot feedback list repository"))
                .isEqualTo(new FeedbackCommand.ListRecords(true, null));
        assertThat(parse("@ai_bot feedback list repository page: MTIuYWJj"))
                .isEqualTo(new FeedbackCommand.ListRecords(true, "MTIuYWJj"));
    }

    @Test
    void keepsTheMultilineExplanationSeparateFromTheCommand() {
        FeedbackCommand command = parse("""
                @ai_bot feedback O1234-F1 useful This caught a missing permission check.

                It would have been exploitable:
                - any authenticated user could read another tenant
                """);

        assertThat(command).isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit ->
                assertThat(submit.reason())
                        .startsWith("This caught a missing permission check.")
                        .contains("It would have been exploitable:")
                        .contains("- any authenticated user could read another tenant"));
    }

    @Test
    void ignoresCommandsInsideQuotesAndFences() {
        assertThat(parse("""
                @ai_bot feedback O1234 useful Looks right.

                > @ai_bot feedback O1234-F1 incorrect old quote
                """)).isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit -> {
            assertThat(submit.target().reference()).isEqualTo("O1234");
            assertThat(submit.reason()).doesNotContain("old quote");
        });

        assertThat(parse("""
                @ai_bot feedback O1234 useful Looks right.

                ```
                @ai_bot feedback O9999-F7 incorrect a documented example
                ```
                """)).isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit ->
                assertThat(submit.target().reference()).isEqualTo("O1234"));

        assertThat(parse("@ai_bot feedback O1234 useful\n```\n@ai_bot feedback retract FB1\n```"))
                .isInstanceOfSatisfying(FeedbackCommand.Submit.class,
                        submit -> assertThat(submit.reason()).doesNotContain("retract"));
    }

    @Test
    void aCommandForAnotherAliasIsNotOurs() {
        assertThat(FeedbackCommandParser.parse("@other_bot feedback O1234 useful", "ai_bot"))
                .isEmpty();
        assertThat(FeedbackCommandParser.parse("@ai_bot2 feedback O1234 useful", "ai_bot"))
                .isEmpty();
        assertThat(FeedbackCommandParser.parse("@ai_bot review this please", "ai_bot"))
                .isEmpty();
        assertThat(FeedbackCommandParser.parse("feedback O1234 useful", "ai_bot")).isEmpty();
    }

    @Test
    void malformedCommandsBecomeHelpInsteadOfFallingThrough() {
        assertThat(parse("@ai_bot feedback")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback nope")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback O1234")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback O1234 excellent")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback O9999-F2 useful"))
                .as("a well-formed reference is parsed; existence is decided by the service")
                .isInstanceOf(FeedbackCommand.Submit.class);
        assertThat(parse("@ai_bot feedback retract nope")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback list everything")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback list page:")).isInstanceOf(FeedbackCommand.Help.class);
        assertThat(parse("@ai_bot feedback list page: abc"))
                .as("cursors are opaque; the service checks them against the caller's listing")
                .isEqualTo(new FeedbackCommand.ListRecords(false, "abc"));
    }

    @Test
    void boundsTheReasonWithoutLosingTheCommand() {
        String longReason = "x".repeat(FeedbackCommandParser.MAX_REASON_CHARS + 500);

        FeedbackCommand command = parse("@ai_bot feedback O1234 useful " + longReason);

        assertThat(command).isInstanceOfSatisfying(FeedbackCommand.Submit.class, submit ->
                assertThat(submit.reason()).hasSize(FeedbackCommandParser.MAX_REASON_CHARS));
    }

    @Test
    void findsTheCommandAfterOtherText() {
        assertThat(parse("Thanks for the review.\n\n@ai_bot feedback O1234-F3 irrelevant Generated file."))
                .isInstanceOfSatisfying(FeedbackCommand.Submit.class,
                        submit -> assertThat(submit.target().reference()).isEqualTo("O1234-F3"));
    }

    @Test
    void theEffectiveAliasIsAcceptedAlongsideTheConfiguredOne() {
        // A provider that rewrites its mention (Bitbucket: @{account_id} -> @AI_Bot) leaves something
        // the configured username never matches, so both have to be accepted.
        assertThat(FeedbackCommandParser.parse("@AI_Bot feedback O1234 useful",
                "@AI_Bot", "@bitbucket-agent")).isPresent();
        assertThat(FeedbackCommandParser.parse("@AI_Bot feedback O1234 useful",
                "@bitbucket-agent")).isEmpty();
    }

    private static FeedbackCommand parse(String body) {
        Optional<FeedbackCommand> command = FeedbackCommandParser.parse(body, ALIAS);
        assertThat(command).as("command in: %s", body).isPresent();
        return command.orElseThrow();
    }
}
