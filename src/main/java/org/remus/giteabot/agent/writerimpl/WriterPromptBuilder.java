package org.remus.giteabot.agent.writerimpl;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

import java.util.List;

public class WriterPromptBuilder {

    /**
     * A human comment on the originating issue, as passed to the writer's initial prompt.
     *
     * @param author    the commenter's username, or {@code null} when the provider did not name one
     * @param body      the comment text
     * @param timestamp the provider's creation timestamp, or {@code null}
     */
    public record DiscussionComment(String author, String body, String timestamp) {
    }

    /**
     * Initial prompt for an assignment run.
     *
     * @param discussionContext the {@code ## Discussion} section from
     *                          {@link #buildDiscussionContext(List, int, int)}, or {@code null}/blank
     *                          when the issue has no human comments
     */
    public String buildInitialPrompt(Long issueNumber, String issueTitle, String issueBody, String treeContext,
                                     String discussionContext) {
        String discussion = discussionContext != null && !discussionContext.isBlank()
                ? discussionContext.strip() + "\n\n" : "";
        return String.format("""
                ## Originating issue
                Number: #%d
                Title: %s
                
                Body:
                %s
                
                %s## Repository files
                %s
                
                Improve this issue or ask the minimum critical follow-up questions.
                """, issueNumber, issueTitle, issueBody != null ? issueBody : "(empty)", discussion, treeContext);
    }

    /**
     * Renders the human comments on the originating issue as a {@code ## Discussion} section.
     * The comments must be in chronological order. When they do not fit into {@code maxTotalChars},
     * the oldest are dropped and the section says so; a single comment is cut at
     * {@code maxSingleCommentChars}.
     *
     * @return the section, or an empty string when there is nothing to show
     */
    public String buildDiscussionContext(List<DiscussionComment> comments, int maxTotalChars,
                                         int maxSingleCommentChars) {
        if (comments == null || comments.isEmpty()) {
            return "";
        }
        // Walk newest to oldest so the most recent answers survive the size limit.
        java.util.Deque<String> entries = new java.util.ArrayDeque<>();
        int totalChars = 0;
        for (int i = comments.size() - 1; i >= 0; i--) {
            DiscussionComment comment = comments.get(i);
            String body = comment.body().strip();
            if (body.length() > maxSingleCommentChars) {
                body = body.substring(0, maxSingleCommentChars) + "\n...(comment truncated)";
            }
            String author = comment.author() != null && !comment.author().isBlank()
                    ? "@" + comment.author() : "unknown user";
            String timestamp = comment.timestamp() != null && !comment.timestamp().isBlank()
                    ? " at " + comment.timestamp() : "";
            String entry = "### " + author + timestamp + "\n" + body + "\n\n";
            if (totalChars + entry.length() > maxTotalChars) {
                break;
            }
            entries.addFirst(entry);
            totalChars += entry.length();
        }
        int omitted = comments.size() - entries.size();

        StringBuilder sb = new StringBuilder("""
                ## Discussion
                
                Comments posted on the originating issue, oldest first. They may answer the open questions or \
                confirm or correct the assumptions in the body above. Fold those answers into \
                "revisedIssueDraft" and do not repeat an answered question in "clarifyingQuestions" or \
                "openQuestions". Only ask about what is still unanswered.
                
                """);
        if (omitted > 0) {
            sb.append("(Truncated: the ").append(omitted).append(" oldest comment")
                    .append(omitted == 1 ? " was" : "s were").append(" omitted to fit the size limit.)\n\n");
        }
        entries.forEach(sb::append);
        return sb.toString().strip();
    }

    public String buildContinuationPrompt(String commentBody) {
        return "The issue author answered:\n\n" + (commentBody != null ? commentBody : "");
    }

    public String buildToolFeedback(List<ImplementationPlan.ToolRequest> requests, List<ToolResult> results) {
        StringBuilder sb = new StringBuilder("## Writer tool results\n\n");
        for (int i = 0; i < requests.size(); i++) {
            ImplementationPlan.ToolRequest request = requests.get(i);
            ToolResult result = results.get(i);
            sb.append("### Result for `").append(request.getId()).append("`: `")
                    .append(request.getTool()).append("`\n\n");
            if (result.success()) {
                sb.append(result.output() == null || result.output().isBlank() ? "(no output)" : result.output());
            } else {
                sb.append("Failed: ").append(result.error() == null ? result.output() : result.error());
            }
            sb.append("\n\n");
        }
        sb.append("Use these results to continue. If no critical questions remain, return the final revised issue draft.");
        return sb.toString();
    }

    /**
     * Delivered as the follow-up of the wrap-up round, once the repository-context
     * budget is spent: the model gets one final round to answer from what it has
     * already read. Generated at runtime, so it cannot be clobbered by an edited
     * {@code system_prompts} row.
     */
    public String buildWrapUpInstruction() {
        return """
                ## Context rounds exhausted

                You have used all repository-context rounds for this run and cannot call tools any more.

                Return your final JSON answer now, from what you have already read:
                - If the issue can be improved with that information, set "readyToCreate": true and fill
                  "revisedIssueDraft" (plus "assumptions" for anything you inferred).
                - If a fact is still missing, put the specific question in "clarifyingQuestions" and name the file
                  or behaviour you could not verify.

                A further tool request ends this run and the author is asked for details instead.
                """;
    }

    public String buildIssueBody(Long originatingIssueNumber, WriterPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Originates from #").append(originatingIssueNumber).append("\n\n");
        if (plan.getQualityAssessment() != null && !plan.getQualityAssessment().isBlank()) {
            sb.append("## Quality assessment\n\n").append(plan.getQualityAssessment()).append("\n\n");
        }
        sb.append(plan.getRevisedIssueDraft() != null ? plan.getRevisedIssueDraft() : "");
        appendList(sb, "Assumptions", plan.getAssumptions());
        appendList(sb, "Open questions", plan.getOpenQuestions());
        sb.append("\n\n---\n*This issue was automatically drafted by the AI technical-writer agent.*\n");
        return sb.toString();
    }

    public String buildClarifyingQuestionComment(WriterPlan plan) {
        StringBuilder sb = new StringBuilder("🤖 **AI Technical Writer**\n\n");
        boolean hasAssessment = plan.getQualityAssessment() != null && !plan.getQualityAssessment().isBlank();
        if (hasAssessment) {
            sb.append("**Quality assessment:** ").append(plan.getQualityAssessment()).append("\n\n");
        }
        List<String> questions = plan.getClarifyingQuestions();
        if (questions != null && !questions.isEmpty()) {
            sb.append("I need the issue author to answer these questions before I can create the improved issue:\n\n");
            for (String question : questions) {
                if (question == null || question.isBlank()) {
                    continue;
                }
                sb.append("- ").append(question).append("\n");
            }
        } else if (!hasAssessment) {
            // Neither structured questions nor free-form assessment: ask the
            // author generically for more context so the comment is never empty.
            sb.append("I do not yet have enough information to draft an improved issue. ")
                    .append("Could you please add more context (acceptance criteria, intended user, ")
                    .append("affected components, examples) and mention me again?\n");
        }
        // If we have an assessment but no structured questions, the assessment
        // itself typically already lists the open points — don't append a second
        // boilerplate paragraph that would duplicate or contradict it.
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append("\n\n## ").append(title).append("\n\n");
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
    }

    public String buildTreeContext(List<RepositoryTreeEntry> tree, int maxFiles) {
        if (tree == null || tree.isEmpty()) {
            return "No repository tree is available.";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (RepositoryTreeEntry entry : tree) {
            if (count >= maxFiles) {
                sb.append("... (truncated, ").append(tree.size() - count).append(" more entries)\n");
                break;
            }
            if (entry.isFile() && entry.path() != null && !entry.path().isBlank()) {
                sb.append("- ").append(entry.path()).append("\n");
                count++;
            }
        }
        return sb.isEmpty() ? "No repository files found." : sb.toString();
    }
}
