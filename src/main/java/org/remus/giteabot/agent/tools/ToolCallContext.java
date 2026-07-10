package org.remus.giteabot.agent.tools;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import java.nio.file.Path;
import java.util.List;

/**
 * Carries everything an {@link AgentToolRouter} needs to
 * execute a single AI tool request. Replaces the long parameter lists that used
 * to be passed around in the agent services.
 *
 * @param owner       repository owner login
 * @param repo        repository name
 * @param issueNumber number of the issue currently being processed
 * @param workspaceDir directory of the cloned workspace
 * @param request     parsed tool request from the AI plan
 * @param diffSummary optional parsed diff summary for PR review workflows;
 *                    may be {@code null} when not applicable
 */
public record ToolCallContext(String owner,
                              String repo,
                              Long issueNumber,
                              Path workspaceDir,
                              ImplementationPlan.ToolRequest request,
                              DiffSummary diffSummary) {

    /**
     * Backward-compatible constructor without a diff summary.
     */
    public ToolCallContext(String owner, String repo, Long issueNumber,
                           Path workspaceDir, ImplementationPlan.ToolRequest request) {
        this(owner, repo, issueNumber, workspaceDir, request, null);
    }

    /** Convenience accessor: never returns {@code null}. */
    public List<String> args() {
        List<String> args = request != null ? request.getArgs() : null;
        return args != null ? args : List.of();
    }

    /** Convenience accessor: never returns {@code null} but may return blank. */
    public String tool() {
        return request != null && request.getTool() != null ? request.getTool() : "";
    }
}

