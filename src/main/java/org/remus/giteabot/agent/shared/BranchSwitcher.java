package org.remus.giteabot.agent.shared;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;

import org.remus.giteabot.agent.validation.Workspace;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies an optional {@code branch-switcher} tool request from the AI before
 * executing any other context tools. Used by both the coding and writer agents.
 * <p>
 * Behaviour:
 * <ul>
 *     <li>The first {@code branch-switcher} request is executed; on success its
 *     announced branch becomes the new "selected" base branch.</li>
 *     <li>Any further {@code branch-switcher} requests are ignored (logged).</li>
 *     <li>All other tool requests are passed through unchanged in the
 *     {@link Result#remainingToolRequests()} list.</li>
 * </ul>
 */
@Slf4j
public final class BranchSwitcher {

    private static final String BRANCH_SWITCHER_TOOL = "branch-switcher";

    private final ToolExecutionService toolExecutionService;

    public BranchSwitcher(ToolExecutionService toolExecutionService) {
        this.toolExecutionService = toolExecutionService;
    }

    public Result apply(Workspace workspace,
                        String baseBranch,
                        List<ImplementationPlan.ToolRequest> toolRequests,
                        Long issueNumber) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return new Result(baseBranch, baseBranch, List.of());
        }

        String selectedBranch = baseBranch;
        boolean switched = false;
        List<ImplementationPlan.ToolRequest> remaining = new ArrayList<>();

        for (ImplementationPlan.ToolRequest toolRequest : toolRequests) {
            if (toolRequest == null || toolRequest.getTool() == null || toolRequest.getTool().isBlank()) {
                continue;
            }

            if (BRANCH_SWITCHER_TOOL.equalsIgnoreCase(toolRequest.getTool()) && !switched) {
                ToolResult result = toolExecutionService.executeContextTool(
                        workspace, BRANCH_SWITCHER_TOOL, toolRequest.getArgs());
                String switchedBranch = BranchRefs.extractSwitchedBranch(result);
                if (switchedBranch != null && !switchedBranch.isBlank()) {
                    selectedBranch = switchedBranch;
                    switched = true;
                    log.info("Switched workspace/context branch to '{}' for issue #{}",
                            selectedBranch, issueNumber);
                } else {
                    log.warn("Branch switch request failed for issue #{}: {}",
                            issueNumber, ToolFailures.describe(result));
                }
                continue;
            }

            if (BRANCH_SWITCHER_TOOL.equalsIgnoreCase(toolRequest.getTool())) {
                log.info("Ignoring additional branch-switcher request for issue #{}", issueNumber);
                continue;
            }

            remaining.add(toolRequest);
        }

        return new Result(baseBranch, selectedBranch, remaining);
    }

    /**
     * Result of evaluating an optional branch-switch request.
     *
     * @param initialBranch         branch used before evaluating branch-switcher requests
     * @param selectedBranch        final selected base branch after evaluating branch-switcher
     * @param remainingToolRequests non-branch-switcher tool requests that should still be executed
     */
    public record Result(String initialBranch,
                         String selectedBranch,
                         List<ImplementationPlan.ToolRequest> remainingToolRequests) {
    }
}

