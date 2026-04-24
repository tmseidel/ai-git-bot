package org.remus.giteabot.agent.issueimpl;

import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Builds prompts, feedback messages, and other text content sent to the AI
 * or posted on issues/PRs during the agent workflow.
 */
public class AgentPromptBuilder {

    private static final int MAX_TREE_FILES_FOR_CONTEXT = 500;

    /**
     * Builds a prompt asking the AI which files it needs to see for the task.
     */
    public String buildFileRequestPrompt(String issueTitle, String issueBody, String treeContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Repository Files
                %s
                
                Which repository context do you need before coding? Output JSON:
                ```json
                {
                  "summary": "Need more context",
                  "requestFiles": ["path/file1", "path/file2"],
                  "requestTools": [{"tool": "rg", "args": ["UserService.save", "src"]}]
                }
                ```
                You may request max 20 files and up to 5 repository tools.
                Available repository tools:
                - `rg` / `ripgrep` / `grep`: search text usages (`["pattern"]` or `["pattern", "path"]`)
                - `find`: find files by glob (`["*.yml"]` or `["*.java", "src"]`)
                - `cat`: read a file with line numbers (`["path/file", "startLine", "endLine"]`)
                - `git-log`: inspect change history (`["path/file", "10"]`)
                - `git-blame`: inspect line history (`["path/file", "startLine", "endLine"]`)
                - `tree`: inspect directories (`["src", "3"]`)
                """, issueTitle, issueBody != null ? issueBody : "(none)", treeContext);
    }

    /**
     * Builds the implementation prompt with the file context provided.
     */
    public String buildImplementationPromptWithContext(String issueTitle, String issueBody,
                                                       String treeContext, String fileContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Repository
                %s
                
                ## File Contents
                %s
                
                If you still need more repository context, you may request additional `requestFiles` or `requestTools`.
                Otherwise implement the issue. Output JSON per system prompt format.
                """, issueTitle, issueBody != null ? issueBody : "(none)", treeContext, fileContext);
    }

    /**
     * Builds the continuation prompt for follow-up comments.
     */
    public String buildContinuationPrompt(String userComment) {
        return userComment;
    }

    /**
     * Builds a human-readable representation of the repository file tree.
     */
    public String buildTreeContext(List<Map<String, Object>> tree) {
        if (tree == null || tree.isEmpty()) {
            return "No files found in repository.";
        }
        StringBuilder sb = new StringBuilder("Repository file tree:\n");
        int count = 0;
        for (Map<String, Object> entry : tree) {
            if (count >= MAX_TREE_FILES_FOR_CONTEXT) {
                sb.append("... (truncated, ").append(tree.size() - count).append(" more files)\n");
                break;
            }
            String type = (String) entry.getOrDefault("type", "blob");
            String path = (String) entry.getOrDefault("path", "");
            if ("blob".equals(type)) {
                sb.append("  ").append(path).append("\n");
            }
            count++;
        }
        return sb.toString();
    }

    /**
     * Builds the feedback message sent to the AI after a tool execution.
     */
    public String buildToolFeedback(ImplementationPlan.ToolRequest toolRequest, ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Tool Execution Result\n\n");
        sb.append("**Command**: `").append(toolRequest.getTool());
        if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
            sb.append(" ").append(String.join(" ", toolRequest.getArgs()));
        }
        sb.append("`\n\n");

        if (result.success()) {
            sb.append("✅ **Success** (exit code 0)\n");
        } else {
            sb.append("❌ **Failed** (exit code ").append(result.exitCode()).append(")\n");
        }

        sb.append("\n").append(result.formatForAi());

        if (!result.success()) {
            sb.append("\nFix the errors and provide updated `fileChanges`. ");
            sb.append("Include `runTool` to validate again.");
        }

        return sb.toString();
    }

    /**
     * Builds feedback for the AI about files where diff application failed.
     * Instructs the AI to provide the complete file content instead of a diff.
     */
    public String buildDiffFailureFeedback(List<String> failedDiffPaths) {
        if (failedDiffPaths == null || failedDiffPaths.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## ⚠️ Diff Application Failed\n\n");
        sb.append("The following file(s) could not be updated because the diff did not match the current file content:\n\n");
        for (String path : failedDiffPaths) {
            sb.append("- `").append(path).append("`\n");
        }
        sb.append("\n**IMPORTANT**: For these files, do NOT use a diff. Instead, provide the **complete file content** ");
        sb.append("in the `content` field of the `fileChanges` entry (and omit the `diff` field). ");
        sb.append("This ensures the changes are applied correctly.\n");
        return sb.toString();
    }

    /**
     * Builds the feedback message when the AI provided file changes without a validation tool.
     */
    public String buildMissingToolFeedback() {
        return """
                ## Missing Validation Tool
                
                Your response included `fileChanges` but no `runTool` for validation.
                
                **Validation is mandatory.** Please provide the same file changes again, \
                but this time include a `runTool` to validate the code.
                
                Detect the build system from the file tree and request the appropriate tool:
                - Maven: `{"tool": "mvn", "args": ["compile", "-q", "-B"]}`
                - Gradle: `{"tool": "gradle", "args": ["compileJava", "-q"]}`
                - npm: `{"tool": "npm", "args": ["run", "build"]}`
                - etc.
                
                Output JSON with both `fileChanges` and `runTool`.""";
    }

    /**
     * Builds information about previously made changes that need to be preserved.
     * This is used when a tool fails to ensure the AI includes all previous changes
     * when providing fixes.
     */
    public String buildPreviousChangesInfo(ImplementationPlan lastValidPlan) {
        if (lastValidPlan == null || !lastValidPlan.hasFileChanges()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## IMPORTANT: Preserve Previous Changes\n\n");
        sb.append("Your previous response included the following file changes that need to be preserved.\n");
        sb.append("When you fix the errors, you MUST include ALL these changes in your response, ");
        sb.append("not just the fix. If you omit any of these files, those changes will be lost.\n\n");
        sb.append("**Files from your previous response** (").append(lastValidPlan.getFileChanges().size()).append("):\n");

        for (FileChange fc : lastValidPlan.getFileChanges()) {
            sb.append("- `").append(fc.getPath()).append("` (").append(fc.getOperation()).append(")\n");
        }

        sb.append("\nInclude all these files in your `fileChanges` array, updating any that need fixes.\n");

        return sb.toString();
    }

    /**
     * Builds the pull request body text.
     */
    public String buildPrBody(Long issueNumber, ImplementationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Fixes #%d%n%n", issueNumber));
        sb.append("## Summary\n\n");
        sb.append(plan.getSummary()).append("\n\n");
        sb.append("## Changes\n\n");
        for (FileChange fc : plan.getFileChanges()) {
            sb.append(String.format("- **%s**: `%s`%n", fc.getOperation(), fc.getPath()));
        }
        sb.append("\n---\n");
        sb.append("*This PR was automatically generated by the AI implementation agent. Please review carefully before merging.*\n");
        return sb.toString();
    }
}
