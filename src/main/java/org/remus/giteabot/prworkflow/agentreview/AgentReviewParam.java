package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe parameter keys for {@link AgentReviewWorkflow}.
 */
public enum AgentReviewParam implements WorkflowParamName {

    /** Upper bound on the number of explore/answer rounds the agent may take. */
    MAX_TOOL_ROUNDS("maxToolRounds"),

    /** Opt-in progress comments for review and clarification runs. */
    ENABLE_PROGRESS_COMMENTS("enableProgressComments"),

    /**
     * When true, the workflow may post a formal PR review decision. The decision itself is computed
     * from the persisted findings and the thresholds below — it is never asked of the model.
     */
    ENABLE_FORMAL_REVIEW_DECISION("enableFormalReviewDecision"),

    /** Maximum allowed number of BLOCKER findings for a formal APPROVE decision. */
    BLOCKER_THRESHOLD("blockerThreshold"),

    /** Maximum allowed number of MEDIUM findings for a formal APPROVE decision. */
    MEDIUM_THRESHOLD("mediumThreshold"),

    /** Maximum allowed number of LOW findings for a formal APPROVE decision. */
    LOW_THRESHOLD("lowThreshold"),

    /**
     * Accept agentic-review feedback and retain supplemental learning evidence. Structured review
     * generation and rendering do not depend on it.
     */
    FEEDBACK_ENABLED("feedbackEnabled");

    private final String key;

    AgentReviewParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}


