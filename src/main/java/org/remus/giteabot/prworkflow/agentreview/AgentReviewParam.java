package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe parameter keys for {@link AgentReviewWorkflow}.
 */
public enum AgentReviewParam implements WorkflowParamName {

    /** Upper bound on the number of explore/answer rounds the agent may take. */
    MAX_TOOL_ROUNDS("maxToolRounds");

    private final String key;

    AgentReviewParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}


