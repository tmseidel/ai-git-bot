package org.remus.giteabot.agent.issueimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.stream.Collectors;

/**
 * Posts progress, thinking, and tool-result comments on issues
 * to keep users informed about the agent's activity.
 */
@Slf4j
public class IssueNotificationService {

    private final RepositoryApiClient repositoryClient;
    private final AiResponseParser responseParser;

    public IssueNotificationService(RepositoryApiClient repositoryClient,
                                     AiResponseParser responseParser) {
        this.repositoryClient = repositoryClient;
        this.responseParser = responseParser;
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
                comment.append("- `").append(toolReq.getTool());
                if (toolReq.getArgs() != null && !toolReq.getArgs().isEmpty()) {
                    comment.append(" ").append(String.join(" ", toolReq.getArgs()));
                }
                comment.append("`\n");
            }
            comment.append("\n");
        }

        // Add file changes info if present
        if (plan != null && plan.hasFileChanges()) {
            comment.append("📄 **Planned file changes** (").append(plan.getFileChanges().size()).append("):\n");
            for (FileChange fc : plan.getFileChanges()) {
                comment.append("- `").append(fc.getPath()).append("` (").append(fc.getOperation()).append(")\n");
            }
            comment.append("\n");
        }

        // Add tool request info if present
        if (plan != null && plan.hasToolRequest()) {
            ImplementationPlan.ToolRequest toolReq = plan.getToolRequest();
            comment.append("🔧 **Will run**: `").append(toolReq.getTool());
            if (toolReq.getArgs() != null && !toolReq.getArgs().isEmpty()) {
                comment.append(" ").append(String.join(" ", toolReq.getArgs()));
            }
            comment.append("`\n");
        }

        // Only post if we have content
        String commentText = comment.toString().strip();
        if (commentText.equals("🤖 **AI Agent Response**:")) {
            return; // Nothing meaningful to post
        }

        try {
            repositoryClient.postComment(owner, repo, issueNumber, commentText);
        } catch (Exception e) {
            log.warn("Failed to post AI thinking comment on issue #{}: {}", issueNumber, e.getMessage());
        }
    }

    /**
     * Posts the result of a tool execution as a comment on the issue.
     */
    public void postToolResultComment(String owner, String repo, Long issueNumber,
                                       ImplementationPlan.ToolRequest toolRequest,
                                       ToolResult result) {
        try {
            StringBuilder comment = new StringBuilder();
            comment.append("🔧 **Tool Execution**: `").append(toolRequest.getTool());
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

            repositoryClient.postComment(owner, repo, issueNumber, comment.toString());
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
        String successComment = String.format(
                """
                        🤖 **AI Agent**: Implementation complete! I've created %s with the following changes:
                        
                        **Summary**: %s
                        
                        **Files changed** (%d):
                        %s
                        
                        Please review the changes carefully. If you need modifications, mention me in a comment \
                        on this issue and I'll continue working on it.""",
                prRef, plan.getSummary(), plan.getFileChanges().size(),
                plan.getFileChanges().stream()
                        .map(fc -> String.format("- `%s` (%s)", fc.getPath(), fc.getOperation()))
                        .collect(Collectors.joining("\n")));

        repositoryClient.postComment(owner, repo, issueNumber, successComment);
    }

    /**
     * Builds and posts a follow-up success comment after additional changes.
     */
    public void postFollowUpSuccessComment(String owner, String repo, Long issueNumber,
                                            ImplementationPlan plan, Long prNumber) {
        String prRef = repositoryClient.formatPullRequestReference(prNumber);
        String updateComment = String.format(
                """
                        🤖 **AI Agent**: I've made the following additional changes:
                        
                        **Summary**: %s
                        
                        **Files changed** (%d):
                        %s
                        
                        The changes have been pushed to %s.""",
                plan.getSummary(), plan.getFileChanges().size(),
                plan.getFileChanges().stream()
                        .map(fc -> String.format("- `%s` (%s)", fc.getPath(), fc.getOperation()))
                        .collect(Collectors.joining("\n")),
                prRef);

        repositoryClient.postComment(owner, repo, issueNumber, updateComment);
    }
}
