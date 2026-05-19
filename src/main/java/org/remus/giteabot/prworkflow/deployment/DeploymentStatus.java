package org.remus.giteabot.prworkflow.deployment;

/**
 * Lifecycle states reported by a {@link DeploymentStrategy}. These map onto
 * {@link org.remus.giteabot.prworkflow.PrWorkflowRunStatus} but stay decoupled
 * so strategy authors do not have to reach into the workflow-run package.
 */
public enum DeploymentStatus {
    /** Deployment is in flight; the strategy will deliver a callback (or be polled). */
    PENDING,
    /** Preview environment is reachable and ready for tests. */
    READY,
    /** Deployment failed irrecoverably for this PR/SHA. */
    FAILED,
    /** Strategy could not even start the deployment (config error, network). */
    REJECTED
}
