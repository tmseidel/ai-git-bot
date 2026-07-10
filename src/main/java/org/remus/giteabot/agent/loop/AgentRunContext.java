package org.remus.giteabot.agent.loop;

import lombok.Setter;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import java.nio.file.Path;

/**
 * Mutable, per-run context shared between {@link AgentLoop} and
 * {@link AgentStrategy} implementations.
 *
 * <p>{@link #baseBranch()} is mutable because strategies may switch the
 * checked-out branch via {@code branch-switcher} mid-run; the resulting branch
 * is reported back to the orchestrating service through this object.</p>
 */
public final class AgentRunContext {

    private final AgentSession session;
    private final String owner;
    private final String repo;
    private final Long issueNumber;
    private final Path workspaceDir;
    @Setter
    private String baseBranch;
    /**
     * The tooling mode the {@link AgentLoop} actually resolved for this run
     * (NATIVE function-calling vs LEGACY JSON). Set by the loop before the first
     * round so strategies can interpret a no-tool-call turn correctly: in NATIVE
     * mode a plain-language turn is a normal completion signal, whereas in LEGACY
     * mode the model is expected to emit a JSON plan.
     */
    @Setter
    private ToolingMode toolingMode;

    /**
     * Optional parsed diff summary for PR review workflows. When set, the
     * {@code pr-diff} tool can use it to extract per-file hunks without
     * re-fetching or re-parsing the full diff.
     */
    @Setter
    private DiffSummary diffSummary;

    public AgentRunContext(AgentSession session, String owner, String repo,
                           Long issueNumber, Path workspaceDir, String baseBranch) {
        this.session = session;
        this.owner = owner;
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.workspaceDir = workspaceDir;
        this.baseBranch = baseBranch;
    }

    public AgentSession session() { return session; }
    public String owner() { return owner; }
    public String repo() { return repo; }
    public Long issueNumber() { return issueNumber; }
    public Path workspaceDir() { return workspaceDir; }
    public String baseBranch() { return baseBranch; }
    public ToolingMode toolingMode() { return toolingMode; }
    public DiffSummary diffSummary() { return diffSummary; }
}

