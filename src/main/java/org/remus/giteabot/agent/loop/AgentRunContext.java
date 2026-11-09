package org.remus.giteabot.agent.loop;

import lombok.Setter;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import org.remus.giteabot.agent.validation.Workspace;
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

    public AgentRunContext(AgentSession session, String owner, String repo,
                           Long issueNumber, Workspace workspace, String baseBranch) {
        this(session, owner, repo, issueNumber, issueNumber, workspace, baseBranch);
    }

    /**
     * @param issueNumber          the issue this run implements — names the branch, the commit and
     *                             every log line
     * @param commentTargetNumber  where the run's comments go. Equal to the issue number except on a
     *                             follow-up, which starts from a comment on the pull request: the
     *                             reader is watching that thread, so the progress stays there.
     */
    public AgentRunContext(AgentSession session, String owner, String repo,
                           Long issueNumber, Long commentTargetNumber,
                           Workspace workspace, String baseBranch) {
        this.session = session;
        this.owner = owner;
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.commentTargetNumber = commentTargetNumber != null ? commentTargetNumber : issueNumber;
        this.workspace = workspace;
        this.baseBranch = baseBranch;
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
