package org.remus.giteabot.agent.validation;

/**
 * Result of workspace preparation — either the ready workspace
 * or an error description.
 */
public record WorkspaceResult(
        boolean success,
        Workspace workspace,
        String error
) {
    public static WorkspaceResult success(Workspace workspace) {
        return new WorkspaceResult(true, workspace, null);
    }

    public static WorkspaceResult failure(String error) {
        return new WorkspaceResult(false, null, error);
    }
}
