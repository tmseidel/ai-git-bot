package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe parameter keys for {@link AgentReviewWorkflow}.
 */
public enum AgentReviewParam implements WorkflowParamName {

    /** Upper bound on the number of explore/answer rounds the agent may take. */
    MAX_TOOL_ROUNDS("maxToolRounds"),

    /** When true, the workflow may post a formal PR review decision (approve/request-changes). */
    ENABLE_FORMAL_REVIEW_DECISION("enableFormalReviewDecision"),

    /** Operator-provided criteria for when to approve, request changes, or leave the PR unchanged. */
    FORMAL_REVIEW_DECISION_PROMPT("formalReviewDecisionPrompt"),

    /** Maximum allowed number of BLOCKER findings for a formal APPROVE decision. */
    BLOCKER_THRESHOLD("blockerThreshold"),

    /** Maximum allowed number of MEDIUM findings for a formal APPROVE decision. */
    MEDIUM_THRESHOLD("mediumThreshold"),

    /** Maximum allowed number of LOW findings for a formal APPROVE decision. */
    LOW_THRESHOLD("lowThreshold");

    private final String key;

    AgentReviewParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}


