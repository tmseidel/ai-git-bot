package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.config.AgentConfigProperties;

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
    void buildTreeContext_usesConfiguredMaxTreeFiles() {
        AgentConfigProperties.ContextConfig contextConfig = new AgentConfigProperties.ContextConfig();
        contextConfig.setMaxTreeFiles(1);
        AgentPromptBuilder limitedBuilder = new AgentPromptBuilder(contextConfig);

        String context = limitedBuilder.buildTreeContext(List.of(
                Map.of("type", "blob", "path", "src/Main.java"),
                Map.of("type", "blob", "path", "src/Other.java")
        ));

        assertThat(context).contains("src/Main.java");
        assertThat(context).doesNotContain("src/Other.java");
        assertThat(context).contains("truncated");
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
    void buildFileRequestPrompt_withIssueComments_includesComments() {
        String prompt = builder.buildFileRequestPrompt(
                "Fix bug", "Description of bug", "Comment by alice: please handle edge case", "  src/Main.java\n");

        assertThat(prompt).contains("## Issue Comments");
        assertThat(prompt).contains("please handle edge case");
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
    void buildImplementationPromptWithContext_withIssueComments_includesComments() {
        String prompt = builder.buildImplementationPromptWithContext(
                "Add feature", "Feature description", "Comment by bob: keep compatibility", "tree context", "file contents");

        assertThat(prompt).contains("## Issue Comments");
        assertThat(prompt).contains("keep compatibility");
    }

    @Test
    void buildContinuationPrompt_returnsCommentAsIs() {
        String result = builder.buildContinuationPrompt("Please fix the typo");
        assertThat(result).isEqualTo("Please fix the typo");
    }

    @Test
    void buildContinuationPrompt_withIssueComments_includesDiscussionContext() {
        String result = builder.buildContinuationPrompt("Please continue", "Comment by alice: original clarification");

        assertThat(result).contains("## New User Comment");
        assertThat(result).contains("Please continue");
        assertThat(result).contains("## Current Issue Comments");
        assertThat(result).contains("original clarification");
    }

    @Test
    void buildIssueCommentsContext_extractsCommonProviderFields() {
        List<Map<String, Object>> comments = List.of(
                Map.of(
                        "body", "Please preserve existing API behavior",
                        "user", Map.of("login", "alice"),
                        "created_at", "2026-05-13T10:00:00Z"),
                Map.of(
                        "content", Map.of("raw", "Bitbucket-style comment"),
                        "author", Map.of("display_name", "Bob"),
                        "created_on", "2026-05-13T11:00:00Z")
        );

        String result = builder.buildIssueCommentsContext(comments);

        assertThat(result).contains("alice");
        assertThat(result).contains("Please preserve existing API behavior");
        assertThat(result).contains("Bob");
        assertThat(result).contains("Bitbucket-style comment");
    }

    @Test
    void buildIssueCommentsContext_usesConfiguredCommentLimits() {
        AgentConfigProperties.ContextConfig contextConfig = new AgentConfigProperties.ContextConfig();
        contextConfig.setMaxIssueComments(1);
        contextConfig.setMaxSingleIssueCommentChars(12);
        AgentPromptBuilder limitedBuilder = new AgentPromptBuilder(contextConfig);

        String result = limitedBuilder.buildIssueCommentsContext(List.of(
                Map.of("body", "First comment is very long", "user", Map.of("login", "alice")),
                Map.of("body", "Second comment", "user", Map.of("login", "bob"))
        ));

        assertThat(result).contains("alice");
        assertThat(result).contains("First commen");
        assertThat(result).contains("comment truncated");
        assertThat(result).doesNotContain("Second comment");
        assertThat(result).contains("Additional issue comments omitted");
    }

    @Test
    void buildMissingToolFeedback_containsInstructions() {
        String result = builder.buildMissingToolFeedback();

        assertThat(result).contains("Missing Tool Requests");
        assertThat(result).contains("runTools");
        assertThat(result).contains("write-file");
    }

    // ---- buildMultiToolFeedback tests ----

    @Test
    void buildMultiToolFeedback_singleSuccessfulTool_containsIdAndSuccess() {
        ImplementationPlan.ToolRequest req = ImplementationPlan.ToolRequest.builder()
                .id("build").tool("mvn").args(List.of("compile", "-q")).build();
        ToolResult result = new ToolResult(true, 0, "BUILD SUCCESS", "");

        String feedback = builder.buildMultiToolFeedback(List.of(req), List.of(result));

        assertThat(feedback).contains("Result for `build`");
        assertThat(feedback).contains("mvn compile -q");
        assertThat(feedback).contains("Success");
        assertThat(feedback).doesNotContain("Fix the errors");
    }

    @Test
    void buildMultiToolFeedback_twoToolsOneFailed_indicatesFailure() {
        ImplementationPlan.ToolRequest req1 = ImplementationPlan.ToolRequest.builder()
                .id("build").tool("mvn").args(List.of("compile")).build();
        ImplementationPlan.ToolRequest req2 = ImplementationPlan.ToolRequest.builder()
                .id("test").tool("mvn").args(List.of("test")).build();
        ToolResult ok = new ToolResult(true, 0, "OK", "");
        ToolResult fail = new ToolResult(false, 1, "FAILED", "");

        String feedback = builder.buildMultiToolFeedback(List.of(req1, req2), List.of(ok, fail));

        assertThat(feedback).contains("Result for `build`");
        assertThat(feedback).contains("Result for `test`");
        assertThat(feedback).contains("Success");
        assertThat(feedback).contains("Failed");
        assertThat(feedback).contains("Fix the errors");
        assertThat(feedback).contains("runTools");
    }

    @Test
    void buildMultiToolFeedback_emptyList_returnsEmpty() {
        assertThat(builder.buildMultiToolFeedback(null, List.of())).isEmpty();
        assertThat(builder.buildMultiToolFeedback(List.of(), List.of())).isEmpty();
    }

    @Test
    void buildToolFeedback_delegatesToMultiTool() {
        ImplementationPlan.ToolRequest req = ImplementationPlan.ToolRequest.builder()
                .id("b").tool("gradle").args(List.of("build")).build();
        ToolResult result = new ToolResult(false, 1, "error output", "");

        String single = builder.buildToolFeedback(req, result);
        String multi = builder.buildMultiToolFeedback(List.of(req), List.of(result));

        assertThat(single).isEqualTo(multi);
    }
}
