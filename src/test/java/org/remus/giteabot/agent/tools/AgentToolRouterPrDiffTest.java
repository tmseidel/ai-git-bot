package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRouterPrDiffTest {

    private static String fileDiff(String path, String line) {
        return "diff --git a/" + path + " b/" + path + "\n"
                + "index 1111111..2222222 100644\n"
                + "--- a/" + path + "\n"
                + "+++ b/" + path + "\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-old\n"
                + "+" + line + "\n";
    }

    private static final DiffSummary SUMMARY = DiffSummary.parse(
            fileDiff("src/A.java", "alpha") + fileDiff("src/B.java", "beta"));

    @Test
    void oneFileKeepsTheOutputWithoutAHeading() {
        ToolResult result = AgentToolRouter.prDiff(SUMMARY, List.of("src/A.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).doesNotContain("### ").contains("+alpha").doesNotContain("beta");
    }

    @Test
    void severalFilesComeTogetherEachUnderItsPath() {
        ToolResult result = AgentToolRouter.prDiff(SUMMARY, List.of("src/A.java", "src/B.java", "src/A.java"));

        assertThat(result.output()).startsWith("### src/A.java\n")
                .contains("+alpha", "\n\n### src/B.java\n", "+beta");
        assertThat(result.output().indexOf("### src/A.java")).isEqualTo(result.output().lastIndexOf("### src/A.java"));
    }

    @Test
    void aPathWithoutHunksIsNamedFirstAndTheOthersAreStillShown() {
        ToolResult some = AgentToolRouter.prDiff(SUMMARY, List.of("src/A.java", "src/Gone.java"));
        ToolResult none = AgentToolRouter.prDiff(SUMMARY, List.of("src/Gone.java"));

        assertThat(some.output()).startsWith("No diff hunks found for: src/Gone.java\n\n### src/A.java\n")
                .doesNotContain("Changed files:");
        assertThat(none.output()).isEqualTo("No diff hunks found for: src/Gone.java\n"
                + "Changed files: src/A.java, src/B.java");
    }

    @Test
    void morePathsThanTheCapAreNamedAsNotShown() {
        List<String> paths = new ArrayList<>(List.of("src/A.java"));
        for (int i = 0; i < AgentToolRouter.MAX_PR_DIFF_FILES; i++) {
            paths.add("src/X" + i + ".java");
        }

        ToolResult result = AgentToolRouter.prDiff(SUMMARY, paths);

        assertThat(result.output()).contains("Not shown, call pr-diff again for: src/X7.java "
                + "(at most 8 files and about 40000 characters per call)\n", "+alpha");
    }

    @Test
    void filesOverTheCharacterCapGoToAnotherCallButTheFirstIsAlwaysShown() {
        int big = AgentToolRouter.MAX_PR_DIFF_CHARS * 3 / 4;
        DiffSummary summary = DiffSummary.parse(fileDiff("src/Big.java", "x".repeat(big))
                + fileDiff("src/Huge.java", "y".repeat(AgentToolRouter.MAX_PR_DIFF_CHARS * 2))
                + fileDiff("src/Small.java", "small"));

        ToolResult result = AgentToolRouter.prDiff(summary, List.of("src/Big.java", "src/Huge.java", "src/Small.java"));
        ToolResult alone = AgentToolRouter.prDiff(summary, List.of("src/Huge.java"));

        assertThat(result.output()).startsWith("Not shown, call pr-diff again for: src/Huge.java (")
                .contains("### src/Big.java\n", "### src/Small.java\n", "+small")
                .doesNotContain("yyyy");
        assertThat(alone.output()).contains("y".repeat(AgentToolRouter.MAX_PR_DIFF_CHARS * 2));
    }

    @Test
    void trailingSpacesOfTheLastLineAreKept() {
        // the last line of the hunk ends in a Markdown hard break (two spaces)
        DiffSummary summary = DiffSummary.parse(fileDiff("doc/A.md", "line with a break  "));

        ToolResult one = AgentToolRouter.prDiff(summary, List.of("doc/A.md"));
        ToolResult two = AgentToolRouter.prDiff(summary, List.of("doc/A.md", "src/A.java"));

        assertThat(one.output()).endsWith("+line with a break  ");
        // the file is still the last block when the other path has no hunks
        assertThat(two.output()).startsWith("No diff hunks found for: src/A.java\n").endsWith("+line with a break  ");
        assertThat(AgentToolRouter.withoutTrailingNewlines("a  \n\r\n")).isEqualTo("a  ");
    }

    @Test
    void noPathIsAnError() {
        ToolResult result = AgentToolRouter.prDiff(SUMMARY, List.of(" ", ""));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("pr-diff requires a file path argument");
    }

    @Test
    void theArrayOfANativeCallBecomesThePathList() throws Exception {
        var schema = new ToolCatalog(new AgentConfigProperties()).schemaOf("pr-diff").orElseThrow();
        var call = AgentJackson.mapper().readTree("{\"paths\": [\"src/A.java\", \"src/B.java\"]}");

        assertThat(ToolArguments.toPositional("pr-diff", call, schema)).containsExactly("src/A.java", "src/B.java");
    }
}
