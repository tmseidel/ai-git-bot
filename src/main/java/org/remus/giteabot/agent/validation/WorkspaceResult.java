package org.remus.giteabot.agent.validation;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of workspace preparation — either a path to the ready workspace
 * or an error description.
 */
public record WorkspaceResult(
        boolean success,
        Path workspacePath,
        String error,
        List<String> failedDiffs
) {
    public static WorkspaceResult success(Path path) {
        return new WorkspaceResult(true, path, null, List.of());
    }

    public static WorkspaceResult success(Path path, List<String> failedDiffs) {
        return new WorkspaceResult(true, path, null, failedDiffs != null ? failedDiffs : List.of());
    }

    public static WorkspaceResult failure(String error) {
        return new WorkspaceResult(false, null, error, List.of());
    }

    /**
     * Returns {@code true} if any diff-based file changes failed to apply
     * during workspace preparation.
     */
    public boolean hasFailedDiffs() {
        return failedDiffs != null && !failedDiffs.isEmpty();
    }
}

