package org.remus.giteabot.workflowrun;

/** Status of a {@link WorkflowRun} and of each of its AI interactions. */
public enum RunStatus {
    /** Still in progress. */
    RUNNING,
    SUCCEEDED,
    ERROR
}
