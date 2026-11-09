package org.remus.giteabot.agent.loop;

import lombok.Setter;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import org.remus.giteabot.agent.validation.Workspace;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Mutable, per-run context shared between {@link AgentLoop} and
 * {@link AgentStrategy} implementations.
 */
public final class AgentRunContext {

    private final AgentSession session;
    private final String owner;
    private final String repo;
    private final Long issueNumber;
    private final Long commentTargetNumber;
    private final Workspace workspace;
    @Setter
    private String baseBranch;
    @Setter
    private ToolingMode toolingMode;
    @Setter
    private DiffSummary diffSummary;
    @Setter
    private Consumer<ToolCallRecord> auditToolCallConsumer;
    @Setter
    private Consumer<ProgressUpdate> progressConsumer;

    private AgentRunContext(AgentSession session, String owner, String repo,
                            Long issueNumber, Long commentTargetNumber,
                            Workspace workspace, String baseBranch) {
        this.session = session;
        this.owner = owner;
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.commentTargetNumber = commentTargetNumber;
        this.workspace = workspace;
        this.baseBranch = baseBranch;
    }

    /**
     * Context for a run that implements the issue: the branch, the commit message, the pull
     * request and the run's own comments all name that issue.
     */
    public static AgentRunContext forIssue(AgentSession session, String owner, String repo,
                                           Long issueNumber, Workspace workspace, String baseBranch) {
        return new AgentRunContext(session, owner, repo, issueNumber, issueNumber, workspace, baseBranch);
    }

    /**
     * Context for a follow-up run, which starts from a comment on the pull request.
     *
     * @param issueNumber         the issue this run implements — names the branch, the commit and
     *                            every log line
     * @param commentTargetNumber where the run's comments go: the thread the comment arrived on.
     *                            Equal to the issue number unless the comment was posted on the
     *                            pull request, whose reader is watching that thread instead.
     */
    public static AgentRunContext forFollowUp(AgentSession session, String owner, String repo,
                                              Long issueNumber, Long commentTargetNumber,
                                              Workspace workspace, String baseBranch) {
        return new AgentRunContext(session, owner, repo, issueNumber,
                Objects.requireNonNull(commentTargetNumber, "commentTargetNumber"),
                workspace, baseBranch);
    }

    /**
     * Context for a run whose subject is the pull request itself, such as a review. The number it
     * names and the thread its comments go to are the same one.
     */
    public static AgentRunContext forPullRequest(AgentSession session, String owner, String repo,
                                                 Long pullRequestNumber, Workspace workspace,
                                                 String baseBranch) {
        return new AgentRunContext(session, owner, repo, pullRequestNumber, pullRequestNumber,
                workspace, baseBranch);
    }

    public AgentSession session() { return session; }
    public String owner() { return owner; }
    public String repo() { return repo; }
    public Long issueNumber() { return issueNumber; }
    public Long commentTargetNumber() { return commentTargetNumber; }
    public Workspace workspace() { return workspace; }
    public String baseBranch() { return baseBranch; }
    public ToolingMode toolingMode() { return toolingMode; }
    public DiffSummary diffSummary() { return diffSummary; }
    public Consumer<ToolCallRecord> auditToolCallConsumer() { return auditToolCallConsumer; }
    public Consumer<ProgressUpdate> progressConsumer() { return progressConsumer; }

    /** Reports the current round and activity, with inspection tool calls just completed. */
    public record ProgressUpdate(int round, String activity, int completedToolCalls) {}

    public record ToolCallRecord(
            String toolName,
            String arguments,
            String resultExcerpt,
            boolean success,
            long durationMs,
            Long inputTokens,
            Long outputTokens,
            int round
    ) {}
}
