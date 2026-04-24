package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBuilderTest {

    private AgentPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AgentPromptBuilder();
    }

    @Test
    void buildTreeContext_withFiles_formatsTree() {
        List<Map<String, Object>> tree = List.of(
                Map.of("type", "blob", "path", "src/Main.java"),
                Map.of("type", "blob", "path", "README.md"),
                Map.of("type", "tree", "path", "src")
        );

        String context = builder.buildTreeContext(tree);

        assertThat(context).contains("src/Main.java");
        assertThat(context).contains("README.md");
        // tree type entries are not listed as files
        assertThat(context).doesNotContain("  src\n");
    }

    @Test
    void buildTreeContext_emptyTree_returnsMessage() {
        String context = builder.buildTreeContext(List.of());
        assertThat(context).contains("No files found");
    }

    @Test
    void buildTreeContext_nullTree_returnsMessage() {
        String context = builder.buildTreeContext(null);
        assertThat(context).contains("No files found");
    }

    @Test
    void buildFileRequestPrompt_includesIssueAndTree() {
        String prompt = builder.buildFileRequestPrompt("Fix bug", "Description of bug", "  src/Main.java\n");

        assertThat(prompt).contains("Fix bug");
        assertThat(prompt).contains("Description of bug");
        assertThat(prompt).contains("src/Main.java");
        assertThat(prompt).contains("requestFiles");
        assertThat(prompt).contains("requestTools");
        assertThat(prompt).contains("git-blame");
    }

    @Test
    void buildFileRequestPrompt_nullBody_usesNone() {
        String prompt = builder.buildFileRequestPrompt("Fix bug", null, "  src/Main.java\n");

        assertThat(prompt).contains("(none)");
    }

    @Test
    void buildImplementationPromptWithContext_includesAllSections() {
        String prompt = builder.buildImplementationPromptWithContext(
                "Add feature", "Feature description", "tree context", "file contents");

        assertThat(prompt).contains("Add feature");
        assertThat(prompt).contains("Feature description");
        assertThat(prompt).contains("tree context");
        assertThat(prompt).contains("file contents");
        assertThat(prompt).contains("requestTools");
    }

    @Test
    void buildContinuationPrompt_returnsCommentAsIs() {
        String result = builder.buildContinuationPrompt("Please fix the typo");
        assertThat(result).isEqualTo("Please fix the typo");
    }

    @Test
    void buildDiffFailureFeedback_emptyList_returnsEmpty() {
        assertThat(builder.buildDiffFailureFeedback(null)).isEmpty();
        assertThat(builder.buildDiffFailureFeedback(List.of())).isEmpty();
    }

    @Test
    void buildDiffFailureFeedback_withPaths_includesPaths() {
        String result = builder.buildDiffFailureFeedback(List.of("src/Foo.java", "src/Bar.java"));

        assertThat(result).contains("src/Foo.java");
        assertThat(result).contains("src/Bar.java");
        assertThat(result).contains("complete file content");
    }

    @Test
    void buildMissingToolFeedback_containsInstructions() {
        String result = builder.buildMissingToolFeedback();

        assertThat(result).contains("Missing Validation Tool");
        assertThat(result).contains("runTool");
    }

    @Test
    void buildPreviousChangesInfo_nullPlan_returnsEmpty() {
        assertThat(builder.buildPreviousChangesInfo(null)).isEmpty();
    }
}
