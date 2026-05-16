package org.remus.giteabot.agent.issueimpl;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.List;
import java.util.Map;

/**
 * Builds prompts, feedback messages, and other text content sent to the AI
 * or posted on issues/PRs during the agent workflow.
 */
public class AgentPromptBuilder {

    private final int maxTreeFilesForContext;
    private final int maxIssueCommentsForContext;
    private final int maxIssueCommentsChars;
    private final int maxSingleIssueCommentChars;

    public AgentPromptBuilder() {
        this(new AgentConfigProperties.ContextConfig());
    }

    public AgentPromptBuilder(AgentConfigProperties.ContextConfig contextConfig) {
        AgentConfigProperties.ContextConfig config = contextConfig != null
                ? contextConfig
                : new AgentConfigProperties.ContextConfig();
        this.maxTreeFilesForContext = positiveOrDefault(config.getMaxTreeFiles(), 500);
        this.maxIssueCommentsForContext = positiveOrDefault(config.getMaxIssueComments(), 50);
        this.maxIssueCommentsChars = positiveOrDefault(config.getMaxIssueCommentsChars(), 20_000);
        this.maxSingleIssueCommentChars = positiveOrDefault(config.getMaxSingleIssueCommentChars(), 4_000);
    }

    /**
     * Builds a prompt asking the AI which files it needs to see for the task.
     */
    public String buildFileRequestPrompt(String issueTitle, String issueBody, String treeContext) {
        return buildFileRequestPrompt(issueTitle, issueBody, "", treeContext);
    }

    /**
     * Builds a prompt asking the AI which files it needs to see for the task, including issue discussion context.
     */
    public String buildFileRequestPrompt(String issueTitle, String issueBody,
                                         String issueCommentsContext, String treeContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Issue Comments
                %s
                
                ## Repository Files
                %s
                
                Which repository context do you need before coding? Output JSON:
                ```json
                {
                  "summary": "Need more context",
                  "requestFiles": ["path/file1", "path/file2"],
                  "requestTools": [{"id": "a6f3c1d2-7e84-4b0a-9f12-e5d8c3a10001", "tool": "branch-switcher", "args": ["develop"]}]
                }
                ```
                You may request max 20 files and up to 5 repository tools.
                If you need another base branch, request `branch-switcher` first and wait for its result before
                requesting additional files/tools.
                Available repository tools:
                - `branch-switcher`: switch workspace/context branch (`["branch-name"]`)
                - `rg` / `ripgrep` / `grep`: search text usages (`["pattern"]` or `["pattern", "path"]`)
                - `find`: find files by glob (`["*.yml"]` or `["*.java", "src"]`)
                - `cat`: read a file with line numbers (`["path/file", "startLine", "endLine"]`)
                - `git-log`: inspect change history (`["path/file", "10"]`)
                - `git-blame`: inspect line history (`["path/file", "startLine", "endLine"]`)
                - `tree`: inspect directories (`["src", "3"]`)
                """, issueTitle, issueBody != null ? issueBody : "(none)",
                normalizeIssueCommentsContext(issueCommentsContext), treeContext);
    }

    /**
     * Builds the implementation prompt with the file context provided.
     */
    public String buildImplementationPromptWithContext(String issueTitle, String issueBody,
                                                       String treeContext, String fileContext) {
        return buildImplementationPromptWithContext(issueTitle, issueBody, "", treeContext, fileContext);
    }

    /**
     * Builds the implementation prompt with issue discussion and file context provided.
     */
    public String buildImplementationPromptWithContext(String issueTitle, String issueBody,
                                                       String issueCommentsContext,
                                                       String treeContext, String fileContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Issue Comments
                %s
                
                ## Repository
                %s
                
                ## File Contents
                %s
                
                If you still need more repository context, you may request additional `requestFiles` or `requestTools`.
                Otherwise implement the issue via `runTools`. Use `write-file` / `patch-file` to apply changes,
                then include a validation tool (e.g. `mvn compile`). Output JSON per system prompt format.
                """, issueTitle, issueBody != null ? issueBody : "(none)",
                normalizeIssueCommentsContext(issueCommentsContext), treeContext, fileContext);
    }

    /**
     * Builds the continuation prompt for follow-up comments.
     */
    public String buildContinuationPrompt(String userComment) {
        return userComment;
    }

    /**
     * Builds the continuation prompt for follow-up comments with the current issue discussion included.
     */
    public String buildContinuationPrompt(String userComment, String issueCommentsContext) {
        return String.format("""
                ## New User Comment
                %s
                
                ## Current Issue Comments
                %s
                """, userComment != null ? userComment : "", normalizeIssueCommentsContext(issueCommentsContext));
    }

    /**
     * Builds a provider-agnostic, truncated issue-comment context block from native API maps.
     */
    public String buildIssueCommentsContext(List<Map<String, Object>> comments) {
        if (comments == null || comments.isEmpty()) {
            return "No issue comments were found.";
        }

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        int included = 0;
        for (Map<String, Object> comment : comments) {
            if (comment == null || included >= maxIssueCommentsForContext
                    || totalChars >= maxIssueCommentsChars) {
                break;
            }

            String body = extractCommentBody(comment);
            if (body.isBlank()) {
                continue;
            }

            body = truncate(body, maxSingleIssueCommentChars);
            String header = "### Comment " + (included + 1)
                    + " by " + extractCommentAuthor(comment)
                    + extractCommentTimestamp(comment) + "\n";
            if (totalChars + header.length() + body.length() > maxIssueCommentsChars) {
                sb.append("\n(Issue comment context truncated due to size limits.)\n");
                break;
            }

            sb.append(header).append(body).append("\n\n");
            totalChars += header.length() + body.length() + 2;
            included++;
        }

        if (included == 0) {
            return "No non-empty issue comments were found.";
        }
        if (comments.size() > included) {
            sb.append("(Additional issue comments omitted after including ").append(included).append(" comments.)\n");
        }
        return sb.toString().strip();
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
            if (count >= maxTreeFilesForContext) {
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

    private String normalizeIssueCommentsContext(String issueCommentsContext) {
        return issueCommentsContext != null && !issueCommentsContext.isBlank()
                ? issueCommentsContext
                : "No issue comments were found.";
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private String extractCommentBody(Map<String, Object> comment) {
        Object body = comment.get("body");
        if (body instanceof String text) {
            return text.strip();
        }
        Object content = comment.get("content");
        if (content instanceof Map<?, ?> contentMap) {
            Object raw = contentMap.get("raw");
            if (raw instanceof String text) {
                return text.strip();
            }
            Object html = contentMap.get("html");
            if (html instanceof String text) {
                return text.strip();
            }
        }
        return "";
    }

    private String extractCommentAuthor(Map<String, Object> comment) {
        Object user = comment.get("user");
        String fromUser = extractActorName(user);
        if (!fromUser.isBlank()) {
            return fromUser;
        }
        Object author = comment.get("author");
        String fromAuthor = extractActorName(author);
        if (!fromAuthor.isBlank()) {
            return fromAuthor;
        }
        return "unknown";
    }

    private String extractActorName(Object actor) {
        if (actor instanceof String text) {
            return text;
        }
        if (actor instanceof Map<?, ?> actorMap) {
            for (String key : List.of("login", "username", "name", "display_name", "nickname")) {
                Object value = actorMap.get(key);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String extractCommentTimestamp(Map<String, Object> comment) {
        for (String key : List.of("created_at", "created_on", "updated_at", "updated_on")) {
            Object value = comment.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return " at " + text;
            }
        }
        return "";
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(comment truncated)";
    }

    /**
     * Builds the feedback message sent to the AI after a single tool execution.
     * Delegates to {@link #buildMultiToolFeedback(List, List)} for consistency.
     */
    public String buildToolFeedback(ImplementationPlan.ToolRequest toolRequest, ToolResult result) {
        return buildMultiToolFeedback(List.of(toolRequest), List.of(result));
    }

    /**
     * Builds a combined feedback message for multiple tool executions, keyed by each tool's ID.
     *
     * @param toolRequests Ordered list of tool requests
     * @param results      Corresponding results in the same order
     * @return Feedback message for the AI
     */
    public String buildMultiToolFeedback(List<ImplementationPlan.ToolRequest> toolRequests,
                                          List<ToolResult> results) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Tool Execution Results\n\n");

        boolean anyFailed = false;
        for (int i = 0; i < toolRequests.size(); i++) {
            ImplementationPlan.ToolRequest req = toolRequests.get(i);
            ToolResult result = i < results.size() ? results.get(i) : null;

            String id = (req.getId() != null && !req.getId().isBlank()) ? req.getId() : ("tool-" + (i + 1));
            sb.append("### Result for `").append(id).append("`: `").append(req.getTool());
            if (req.getArgs() != null && !req.getArgs().isEmpty()) {
                sb.append(" ").append(String.join(" ", req.getArgs()));
            }
            sb.append("`\n\n");

            if (result == null) {
                sb.append("⚠️ No result available.\n\n");
            } else if (result.success()) {
                sb.append("✅ **Success** (exit code 0)\n\n");
                sb.append(result.formatForAi()).append("\n\n");
            } else {
                sb.append("❌ **Failed** (exit code ").append(result.exitCode()).append(")\n\n");
                sb.append(result.formatForAi()).append("\n\n");
                anyFailed = true;
            }
        }

        if (anyFailed) {
            sb.append("Fix the errors using `write-file` or `patch-file` tools in `runTools`. ");
            sb.append("Include validation tools again to confirm the fix.");
        }

        return sb.toString();
    }

    /**
     * Builds the feedback message when the AI provided no runTools.
     */
    public String buildMissingToolFeedback() {
        return """
                ## Missing Tool Requests
                
                Your response did not include any `runTools`. All file changes and validation \
                must be performed via tool requests.
                
                **Please provide a response with `runTools`** that:
                1. Write/modify files using `write-file` or `patch-file`
                2. Validate the changes using a build/test tool
                
                Example:
                ```json
                "runTools": [
                  {"id": "a6f3c1d2-7e84-4b0a-9f12-e5d8c3a10001", "tool": "write-file", "args": ["src/Foo.java", "...content..."]},
                  {"id": "a6f3c1d2-7e84-4b0a-9f12-e5d8c3a10002", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                ]
                ```
                
                Available file tools: `write-file`, `patch-file`, `mkdir`, `delete-file`
                Available build tools (configured by admin): see system prompt.""";
    }

    /**
     * Feedback message used by the native-tooling strategy when the model
     * mutated files but did not call a build/test tool in the same round.
     * Validation is mandatory: the agent must run the project's own build to
     * prove the change compiles and tests still pass before we open the PR.
     */
    public String buildMissingValidationFeedback() {
        return """
                ## Validation is mandatory
                
                You modified files but did not call any build / test tool in this round, \
                so the change is unverified. Before we can open the pull request you must \
                run the project's own build through a validation tool call (for example \
                `mvn test -q -B`, `gradle build -q`, `npm test`, `cargo build`, `go build ./...`, \
                `dotnet test`, etc.), with arguments appropriate for this repository.
                
                Please issue one more round that:
                1. Runs the build / tests (preferring tests over compile-only when the project has them).
                2. If the build fails, reads the actual error, locates the cause and applies the necessary fix \
                   in the same round before re-running validation.
                
                Do not finish with "done" — wait for a green build first.""";
    }

    /**
     * Builds the pull request body text.
     */
    public String buildPrBody(Long issueNumber, ImplementationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Fixes #%d%n%n", issueNumber));
        sb.append("## Summary\n\n");
        if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
            sb.append(plan.getSummary()).append("\n\n");
        }
        sb.append("\n---\n");
        sb.append("*This PR was automatically generated by the AI implementation agent. Please review carefully before merging.*\n");
        return sb.toString();
    }
}
