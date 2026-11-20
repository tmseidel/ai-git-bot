package org.remus.giteabot.agent.writerimpl;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.writerimpl.WriterPromptBuilder.DiscussionComment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriterPromptBuilderTest {

    private final WriterPromptBuilder builder = new WriterPromptBuilder();

    @Test
    void discussion_isEmptyWithoutComments() {
        assertThat(builder.buildDiscussionContext(List.of(), 1_000, 1_000)).isEmpty();
    }

    @Test
    void discussion_dropsTheOldestCommentsFirstAndSaysSo() {
        List<DiscussionComment> comments = List.of(
                new DiscussionComment("alice", "first " + "x".repeat(100), "2026-11-01T10:00:00Z"),
                new DiscussionComment("bob", "middle " + "y".repeat(100), "2026-11-01T11:00:00Z"),
                new DiscussionComment("carol", "newest " + "z".repeat(100), "2026-11-01T12:00:00Z"));

        String discussion = builder.buildDiscussionContext(comments, 300, 1_000);

        assertThat(discussion).contains("newest").contains("middle").doesNotContain("first x")
                .contains("(Truncated: the 1 oldest comment was omitted to fit the size limit.)");
        assertThat(discussion.indexOf("middle")).isLessThan(discussion.indexOf("newest"));
    }

    @Test
    void discussion_cutsASingleOversizedComment() {
        String discussion = builder.buildDiscussionContext(
                List.of(new DiscussionComment("alice", "a".repeat(50), null)), 1_000, 10);

        assertThat(discussion).contains("### @alice\n" + "a".repeat(10) + "\n...(comment truncated)")
                .doesNotContain("a".repeat(11));
    }

    @Test
    void initialPrompt_placesTheDiscussionBeforeTheRepositoryFiles() {
        String prompt = builder.buildInitialPrompt(12L, "Title", "Body", "- pom.xml\n",
                "## Discussion\n\n### @alice\nanswer");

        assertThat(prompt.indexOf("Body")).isLessThan(prompt.indexOf("## Discussion"));
        assertThat(prompt.indexOf("## Discussion")).isLessThan(prompt.indexOf("## Repository files"));
    }

    @Test
    void initialPrompt_withoutDiscussionKeepsTheHistoricLayout() {
        String prompt = builder.buildInitialPrompt(12L, "Title", "Body", "- pom.xml\n", null);

        assertThat(prompt).doesNotContain("## Discussion").contains("Body:\nBody\n\n## Repository files");
    }

    @Test
    void continuation_namesTheCommenterInsteadOfTheIssueAuthor() {
        assertThat(builder.buildContinuationPrompt("alice", "We use Maven."))
                .isEqualTo("@alice answered:\n\nWe use Maven.");
    }

    @Test
    void continuation_fallsBackWhenTheCommenterIsUnknown() {
        assertThat(builder.buildContinuationPrompt(null, "Yes")).isEqualTo("A user answered:\n\nYes");
    }
}
