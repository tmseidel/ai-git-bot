package org.remus.giteabot.agent.validation;

import java.nio.file.Path;

/**
 * Lifecycle holder of one workspace attempt: the private temporary parent
 * ({@code workspaceRoot}) and the external git credential-store file created
 * for it.
 *
 * <p>Both resources are created together with {@code WorkspaceSetup} and are
 * cleaned up together via {@link WorkspaceService#cleanupWorkspace} — the
 * credential-file reference can therefore never be dropped by a retry path
 * (such as the branch-clone fallback in
 * {@link WorkspaceService#prepareWorkspace}), even when a directory deletion
 * only partially succeeds.</p>
 */
class WorkspaceSetup {

    private final Path workspaceRoot;
    private Path credentialsFile;

    WorkspaceSetup(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /** The private temporary parent of this attempt (contains the marker file). */
    Path workspaceRoot() {
        return workspaceRoot;
    }

    /** The repository child directory that git clones into. */
    Path workspaceDir() {
        return workspaceRoot.resolve(WorkspaceService.REPOSITORY_DIRECTORY_NAME);
    }

    /** The external credential-store file of this attempt, or {@code null} if none. */
    Path credentialsFile() {
        return credentialsFile;
    }

    void setCredentialsFile(Path credentialsFile) {
        this.credentialsFile = credentialsFile;
    }
}