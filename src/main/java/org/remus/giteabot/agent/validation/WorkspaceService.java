package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages local workspace directories for the AI agent.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Cloning a repository into a temporary directory</li>
 *     <li>Committing and pushing workspace changes back to the remote</li>
 *     <li>Cleaning up temporary workspace directories</li>
 * </ul>
 * <p>
 * File changes (write-file, patch-file, mkdir, delete-file) are now performed
 * directly via {@link org.remus.giteabot.agent.validation.ToolExecutionService}.
 */
@Slf4j
@Service
public class WorkspaceService {

    private final Path workspaceBaseDir;

    /** Creates a service that places workspaces under the system temporary directory. */
    public WorkspaceService() {
        this(null);
    }

    /** Creates a service that places workspaces below the configured absolute directory when set. */
    @Autowired
    public WorkspaceService(@Value("${giteabot.workspaces.dir:#{null}}") String configuredDir) {
        this.workspaceBaseDir = configuredDir == null || configuredDir.isBlank()
                ? null
                : Path.of(configuredDir).toAbsolutePath().normalize();
    }


    /**
     * Clones a repository workspace. When a branch-based shallow clone fails and
     * {@code prNumber} is non-null, falls back to cloning the default branch then
     * fetching {@code refs/pull/<prNumber>/head} (GitHub/Gitea fork-safe ref).
     */
    public WorkspaceResult prepareWorkspace(String owner, String repo, String branch,
                                            String cloneBaseUrl, String token, Long prNumber) {
        try {
            Path tempDir = createWorkspaceDirectory();
            log.info("Cloning repository to {} for workspace", tempDir);

            String cloneUrl = buildCloneUrl(owner, repo, cloneBaseUrl, token);
            CommandResult cloneResult = runCommand(tempDir.getParent().toFile(),
                    new String[]{"git", "clone", "--depth", "1", "--branch", branch,
                            cloneUrl, tempDir.getFileName().toString()},
                    60);

            if (cloneResult.success()) {
                return WorkspaceResult.success(tempDir);
            }

            // Fork PR fallback: clone default branch → fetch PR head ref
            if (prNumber != null) {
                log.info("Branch clone failed, falling back to PR head ref for PR #{}: {}",
                        prNumber, cloneResult.output());
                deleteDirectory(tempDir);
                tempDir = createWorkspaceDirectory();

                CommandResult defaultCloneResult = runCommand(tempDir.getParent().toFile(),
                        new String[]{"git", "clone", "--depth", "1",
                                cloneUrl, tempDir.getFileName().toString()},
                        60);

                if (!defaultCloneResult.success()) {
                    log.error("Fallback clone (default branch) also failed: {}",
                            defaultCloneResult.output());
                    deleteDirectory(tempDir);
                    return WorkspaceResult.failure(
                            "Failed to clone repository (branch: " + cloneResult.output()
                                    + "; default branch: " + defaultCloneResult.output() + ")");
                }

                CommandResult fetchResult = runCommand(tempDir.toFile(),
                        new String[]{"git", "fetch", "origin",
                                "refs/pull/" + prNumber + "/head"},
                        60);

                if (!fetchResult.success()) {
                    log.error("Failed to fetch PR head ref for PR #{}: {}", prNumber,
                            fetchResult.output());
                    deleteDirectory(tempDir);
                    return WorkspaceResult.failure(
                            "Failed to fetch PR head ref for PR #" + prNumber + ": "
                                    + fetchResult.output());
                }

                CommandResult checkoutResult = runCommand(tempDir.toFile(),
                        new String[]{"git", "checkout", "-B", branch, "FETCH_HEAD"}, 15);

                if (!checkoutResult.success()) {
                    log.error("Failed to checkout FETCH_HEAD for PR #{}: {}", prNumber,
                            checkoutResult.output());
                    deleteDirectory(tempDir);
                    return WorkspaceResult.failure(
                            "Failed to checkout FETCH_HEAD for PR #" + prNumber + ": "
                                    + checkoutResult.output());
                }

                return WorkspaceResult.success(tempDir);
            }

            // No fallback — report the original clone error
            log.error("Failed to clone repository: {}", cloneResult.output());
            deleteDirectory(tempDir);
            return WorkspaceResult.failure("Failed to clone repository: " + cloneResult.output());

        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            return WorkspaceResult.failure("Failed to prepare workspace: " + e.getMessage());
        }
    }

    /**
     * Commits all changes in the workspace and pushes them to the remote.
     * <p>
     * If {@code createNewBranch} is {@code true} a new local branch is created first
     * ({@code git checkout -b branchName}).  Otherwise the workspace is assumed to be
     * already on the target branch (cloned with {@code --branch branchName}).
     *
     * @param workspaceDir    The workspace directory
     * @param branchName      Name of the target branch (new or existing)
     * @param commitMessage   Commit message
     * @param authorName      Git author name
     * @param authorEmail     Git author e-mail
     * @param createNewBranch {@code true} to create the branch before committing
     * @return {@code true} if commit and push succeeded
     */
    public boolean commitAndPush(Path workspaceDir, String branchName, String commitMessage,
                                 String authorName, String authorEmail, boolean createNewBranch) {
        // Configure git author
        if (!runCommand(workspaceDir.toFile(),
                new String[]{"git", "config", "user.email", authorEmail}, 10).success()) {
            log.warn("Could not set git user.email, continuing anyway");
        }
        if (!runCommand(workspaceDir.toFile(),
                new String[]{"git", "config", "user.name", authorName}, 10).success()) {
            log.warn("Could not set git user.name, continuing anyway");
        }

        if (createNewBranch) {
            CommandResult checkoutResult = runCommand(workspaceDir.toFile(),
                    new String[]{"git", "checkout", "-b", branchName}, 15);
            if (!checkoutResult.success()) {
                log.error("Failed to create branch '{}': {}", branchName, checkoutResult.output());
                return false;
            }
        }

        CommandResult addResult = runCommand(workspaceDir.toFile(),
                new String[]{"git", "add", "-A"}, 15);
        if (!addResult.success()) {
            log.error("git add -A failed: {}", addResult.output());
            return false;
        }

        CommandResult commitResult = runCommand(workspaceDir.toFile(),
                new String[]{"git", "commit", "-m", commitMessage}, 15);
        if (!commitResult.success()) {
            // "nothing to commit" is not a real error
            if (commitResult.output().contains("nothing to commit")) {
                log.warn("Nothing to commit in workspace — no file changes were made");
                return false;
            }
            log.error("git commit failed: {}", commitResult.output());
            return false;
        }

        CommandResult pushResult = runCommand(workspaceDir.toFile(),
                new String[]{"git", "push", "origin", branchName}, 60);
        if (!pushResult.success()) {
            log.error("git push failed: {}", pushResult.output());
            return false;
        }

        log.info("Successfully committed and pushed to branch '{}'", branchName);
        return true;
    }

    /**
     * Returns whether the workspace contains changes that Git would commit.
     * Empty directories are intentionally ignored by Git and therefore return {@code false}.
     */
    public boolean hasUncommittedChanges(Path workspaceDir) {
        CommandResult statusResult = runCommand(workspaceDir.toFile(),
                new String[]{"git", "status", "--porcelain"}, 10);
        if (!statusResult.success()) {
            log.warn("Could not inspect workspace git status: {}", statusResult.output());
            return true;
        }
        return !statusResult.output().isBlank();
    }

    /**
     * Returns the workspace-relative paths of every file Git currently sees as
     * changed (added, modified, renamed or untracked) in {@code workspaceDir}.
     * Parsed from {@code git status --porcelain}; rename entries surface their
     * destination path. Used by callers that need to assert which files are
     * about to be committed — e.g. the unit-test workflow's pre-commit guard.
     *
     * @return the changed paths (forward slashes), never {@code null}.
     */
    public List<String> listChangedFiles(Path workspaceDir) {
        List<String> changed = new ArrayList<>();
        if (workspaceDir == null) {
            return changed;
        }
        CommandResult statusResult = runCommand(workspaceDir.toFile(),
                new String[]{"git", "status", "--porcelain"}, 10);
        if (!statusResult.success() || statusResult.output() == null) {
            log.warn("Could not list changed files via git status: {}",
                    statusResult.output());
            return changed;
        }
        for (String line : statusResult.output().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            // Porcelain v1 format: "XY <path>" or "XY <old> -> <new>".
            String entry = line.length() > 3 ? line.substring(3).trim() : line.trim();
            int arrow = entry.indexOf(" -> ");
            if (arrow >= 0) {
                entry = entry.substring(arrow + 4).trim();
            }
            // Drop surrounding quotes Git adds for paths with special chars.
            if (entry.length() >= 2 && entry.startsWith("\"") && entry.endsWith("\"")) {
                entry = entry.substring(1, entry.length() - 1);
            }
            if (!entry.isBlank()) {
                changed.add(entry.replace('\\', '/'));
            }
        }
        return changed;
    }

    /**
     * Step 7.3 — returns a {@code git diff --stat} style summary of the
     * uncommitted changes in {@code workspaceDir}. Used by the optional
     * Critic / Reflection step to give the LLM a compact view of what is
     * about to be committed without paying for the full diff.
     *
     * @return a textual summary, possibly empty; never {@code null}.
     */
    public String diffStat(Path workspaceDir) {
        if (workspaceDir == null) {
            return "";
        }
        CommandResult result = runCommand(workspaceDir.toFile(),
                new String[]{"git", "diff", "--stat", "HEAD"}, 15);
        if (!result.success()) {
            log.debug("git diff --stat failed: {}", result.output());
            return "";
        }
        String out = result.output();
        return out == null ? "" : out.strip();
    }


    /**
     * Cleans up a workspace directory by deleting it recursively.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir != null) {
            try {
                deleteDirectory(workspaceDir);
                log.debug("Cleaned up workspace: {}", workspaceDir);
            } catch (IOException e) {
                log.warn("Failed to clean up workspace {}: {}", workspaceDir, e.getMessage());
            }
        }
    }

    // ---- internal helpers ------------------------------------------------

    /** Creates a workspace below the configured Docker-bindable root when one is supplied. */
    Path createWorkspaceDirectory() throws IOException {
        if (workspaceBaseDir == null) {
            return Files.createTempDirectory("agent-workspace-");
        }
        Files.createDirectories(workspaceBaseDir);
        return Files.createTempDirectory(workspaceBaseDir, "agent-workspace-");
    }

    String buildCloneUrl(String owner, String repo, String cloneBaseUrl, String token) {
        // For local filesystem paths (used in tests and local development),
        // pass through as-is — git handles bare directory paths natively.
        if (cloneBaseUrl.startsWith("file://") || cloneBaseUrl.startsWith("/")) {
            return cloneBaseUrl;
        }
        String protocol = cloneBaseUrl.startsWith("https://") ? "https" : "http";
        String baseUrl = cloneBaseUrl.replaceFirst("https?://", "");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // oauth2:TOKEN format works for GitLab, GitHub, Gitea, and Bitbucket
        return String.format("%s://oauth2:%s@%s/%s/%s.git", protocol, token, baseUrl, owner, repo);
    }

    private CommandResult runCommand(File workDir, String[] command, int timeoutSeconds) {
        Path disabledHooksDirectory = null;
        Path emptyGlobalGitConfig = null;
        try {
            // This service performs trusted Git lifecycle operations after untrusted code ran in a workspace.
            // Disable local hooks and external Git configuration before reading workspace metadata.
            disabledHooksDirectory = Files.createTempDirectory("ai-git-bot-empty-hooks-");
            emptyGlobalGitConfig = Files.createTempFile(disabledHooksDirectory, "global-", ".gitconfig");
            List<String> gitCommand = new ArrayList<>(command.length + 7);
            gitCommand.add(command[0]);
            gitCommand.add("-c");
            gitCommand.add("core.hooksPath=" + disabledHooksDirectory.toAbsolutePath().normalize());
            gitCommand.add("-c");
            gitCommand.add("core.fsmonitor=false");
            gitCommand.add("-c");
            gitCommand.add("credential.helper=");
            for (int index = 1; index < command.length; index++) {
                gitCommand.add(command[index]);
            }
            ProcessBuilder pb = new ProcessBuilder(gitCommand);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironmentForGit(pb);
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalGitConfig.toString());

            Process process = pb.start();
            ProcessSupport.CommandResult result = ProcessSupport.waitFor(process, timeoutSeconds,
                    TimeUnit.SECONDS, 1_000_000);
            if (!result.finished()) {
                return new CommandResult(false,
                        "Command timed out after " + timeoutSeconds + " seconds");
            }

            return new CommandResult(result.exitCode() == 0, result.output());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to run command: {}", e.getMessage());
            return new CommandResult(false, "Exception: " + e.getMessage());
        } finally {
            if (emptyGlobalGitConfig != null) {
                try {
                    Files.deleteIfExists(emptyGlobalGitConfig);
                } catch (IOException e) {
                    log.warn("Failed to remove empty global Git config {}: {}",
                            emptyGlobalGitConfig, e.getMessage());
                }
            }
            if (disabledHooksDirectory != null) {
                try {
                    Files.deleteIfExists(disabledHooksDirectory);
                } catch (IOException e) {
                    log.warn("Failed to remove empty Git hooks directory {}: {}",
                            disabledHooksDirectory, e.getMessage());
                }
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete {}: {}", path, e.getMessage());
                            }
                        });
            }
        }
    }
}
