package org.remus.giteabot.agent.issueimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;

/**
 * Posts progress, thinking, and tool-result comments on issues
 * to keep users informed about the agent's activity.
 */
@Slf4j
public class IssueNotificationService {

    /** Maximum number of characters shown per tool argument in comments. */
    private static final int MAX_ARG_DISPLAY_CHARS = 80;

    private final RepositoryApiClient repositoryClient;
    private final AiResponseParser responseParser;
    private final ToolExecutionService toolExecutionService;

    public IssueNotificationService(RepositoryApiClient repositoryClient,
                                     AiResponseParser responseParser,
                                     ToolExecutionService toolExecutionService) {
        this.repositoryClient = repositoryClient;
        this.responseParser = responseParser;
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * Posts the AI's thinking/reasoning as a comment on the issue, excluding JSON content.
     * This provides transparency about what the AI is doing.
     */
    public void postAiThinkingComment(String owner, String repo, Long issueNumber, String aiResponse) {
        String thinking = responseParser.extractNonJsonResponse(aiResponse);

        // Extract summary from the response if available
        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);
        String summary = (plan != null && plan.getSummary() != null) ? plan.getSummary() : null;

        // If no thinking text and no plan data, nothing to post
        if ((thinking == null || thinking.isBlank()) && plan == null) {
            return;
        }

        StringBuilder comment = new StringBuilder();
        comment.append("🤖 **AI Agent Response**:\n\n");

        // Add thinking text if present
        if (thinking != null && !thinking.isBlank()) {
            comment.append(thinking);
            comment.append("\n\n");
        }

        // Add summary if present
        if (summary != null && !summary.isBlank()) {
            comment.append("📝 **Summary**: ").append(summary).append("\n\n");
        }

        // Add file request info if present
        if (plan != null && plan.hasFileRequests()) {
            comment.append("📁 **Requesting files**: ");
            comment.append(String.join(", ", plan.getRequestFiles().stream()
                    .map(f -> "`" + f + "`")
                    .toList()));
            comment.append("\n\n");
        }

        if (plan != null && plan.hasContextToolRequests()) {
            comment.append("🔎 **Requesting repository tools**:\n");
            for (ImplementationPlan.ToolRequest toolReq : plan.getRequestTools()) {
                appendToolRequestLine(comment, toolReq);
            }
            comment.append("\n");
        }


        // Only show non-silent (validation) tools in the "Will run" section.
        // File tools (write-file, patch-file, mkdir, delete-file) and context tools
        // are silent and must NOT appear here – their args may contain large file content.
        if (plan != null && plan.hasToolRequest()) {
            List<ImplementationPlan.ToolRequest> validationTools = plan.getEffectiveToolRequests()
                    .stream()
                    .filter(r -> !toolExecutionService.isSilentTool(r.getTool()))
                    .toList();
            if (!validationTools.isEmpty()) {
                comment.append("🔧 **Will run** (").append(validationTools.size()).append("):\n");
                for (ImplementationPlan.ToolRequest toolReq : validationTools) {
                    appendToolRequestLine(comment, toolReq);
                }
                comment.append("\n");
            }
        }

        // Only post if we have content
        String commentText = comment.toString().strip();
        if (commentText.equals("🤖 **AI Agent Response**:")) {
            return; // Nothing meaningful to post
        }

        try {
            repositoryClient.postIssueComment(owner, repo, issueNumber, commentText);
        } catch (Exception e) {
            log.warn("Failed to post AI thinking comment on issue #{}: {}", issueNumber, e.getMessage());
        }
    }

    /**
     * Posts the result of a single tool execution as a comment on the issue.
     * Should only be called for non-context (validation) tools.
     */
    public void postToolResultComment(String owner, String repo, Long issueNumber,
                                       ImplementationPlan.ToolRequest toolRequest,
                                       ToolResult result) {
        try {
            StringBuilder comment = new StringBuilder();
            String id = toolRequest.getId() != null && !toolRequest.getId().isBlank()
                    ? " `[" + toolRequest.getId() + "]`" : "";
            comment.append("🔧 **Tool Execution**").append(id).append(": `").append(toolRequest.getTool());
            if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
                comment.append(" ").append(String.join(" ", toolRequest.getArgs()));
            }
            comment.append("`\n\n");

            if (result.success()) {
                comment.append("✅ **Success**\n");
            } else {
                comment.append("❌ **Failed** (exit code ").append(result.exitCode()).append(")\n");
                if (!result.output().isEmpty()) {
                    String output = result.output();
                    if (output.length() > 2000) {
                        output = output.substring(0, 2000) + "\n... (truncated)";
                    }
                    comment.append("\n```\n").append(output).append("```\n");
                }
            }

            repositoryClient.postIssueComment(owner, repo, issueNumber, comment.toString());
        } catch (Exception e) {
            log.warn("Failed to post tool result comment: {}", e.getMessage());
        }
    }

    /**
     * Builds and posts a success comment after initial implementation with PR link.
     */
    public void postSuccessComment(String owner, String repo, Long issueNumber,
                                    ImplementationPlan plan, Long prNumber) {
        String prRef = repositoryClient.formatPullRequestReference(prNumber);
        String summary = (plan != null && plan.getSummary() != null) ? plan.getSummary() : "(no summary)";
        String successComment = String.format(
                """
                        🤖 **AI Agent**: Implementation complete! I've created %s with the following changes:
                        
                        **Summary**: %s
                        
                        Please review the changes carefully. If you need modifications, mention me in a comment \
                        on this issue and I'll continue working on it.""",
                prRef, summary);

        repositoryClient.postIssueComment(owner, repo, issueNumber, successComment);
    }

    /**
     * Builds and posts a follow-up success comment after additional changes.
     */
    public void postFollowUpSuccessComment(String owner, String repo, Long issueNumber,
                                            ImplementationPlan plan, Long prNumber) {
        String prRef = repositoryClient.formatPullRequestReference(prNumber);
        String summary = (plan != null && plan.getSummary() != null) ? plan.getSummary() : "(no summary)";
        String updateComment = String.format(
                """
                        🤖 **AI Agent**: I've made additional changes and pushed them to %s.
                        
                        **Summary**: %s""",
                prRef, summary);

        repositoryClient.postIssueComment(owner, repo, issueNumber, updateComment);
    }

    // ---- Helpers ----

    private void appendToolRequestLine(StringBuilder comment, ImplementationPlan.ToolRequest toolReq) {
        String id = (toolReq.getId() != null && !toolReq.getId().isBlank())
                ? "[" + toolReq.getId() + "] " : "";
        comment.append("- ").append(id).append("`").append(toolReq.getTool());
        if (toolReq.getArgs() != null && !toolReq.getArgs().isEmpty()) {
            comment.append(" ");
            // Truncate individual args to avoid dumping large content in comments
            comment.append(toolReq.getArgs().stream()
                    .map(a -> a.length() > MAX_ARG_DISPLAY_CHARS
                            ? a.substring(0, MAX_ARG_DISPLAY_CHARS) + "…" : a)
                    .reduce((a, b) -> a + " " + b)
                    .orElse(""));
        }
        comment.append("`\n");
    }
}
