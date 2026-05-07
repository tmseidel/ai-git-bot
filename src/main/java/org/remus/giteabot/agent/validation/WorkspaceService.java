package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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


    /**
     * Prepares a workspace by cloning the repository.
     *
     * @param owner        Repository owner
     * @param repo         Repository name
     * @param branch       The branch to clone
     * @param cloneBaseUrl The Git server base URL (e.g. {@code http://localhost:3000})
     * @param token        The API / clone token
     * @return {@link WorkspaceResult} containing the workspace path or error details
     */
    public WorkspaceResult prepareWorkspace(String owner, String repo, String branch,
                                            String cloneBaseUrl, String token) {
        try {
            Path tempDir = Files.createTempDirectory("agent-workspace-");
            log.info("Cloning repository to {} for workspace", tempDir);

            String cloneUrl = buildCloneUrl(owner, repo, cloneBaseUrl, token);
            CommandResult cloneResult = runCommand(tempDir.getParent().toFile(),
                    new String[]{"git", "clone", "--depth", "1", "--branch", branch,
                            cloneUrl, tempDir.getFileName().toString()},
                    60);

            if (!cloneResult.success()) {
                log.error("Failed to clone repository: {}", cloneResult.output());
                deleteDirectory(tempDir);
                return WorkspaceResult.failure("Failed to clone repository: " + cloneResult.output());
            }

            return WorkspaceResult.success(tempDir);

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

    String buildCloneUrl(String owner, String repo, String cloneBaseUrl, String token) {
        String protocol = cloneBaseUrl.startsWith("https://") ? "https" : "http";
        String baseUrl = cloneBaseUrl.replaceFirst("https?://", "");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // oauth2:TOKEN format works for GitLab, GitHub, Gitea, and Bitbucket
        return String.format("%s://oauth2:%s@%s/%s/%s.git", protocol, token, baseUrl, owner, repo);
    }

    private CommandResult runCommand(File workDir, String[] command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false,
                        "Command timed out after " + timeoutSeconds + " seconds");
            }

            boolean success = process.exitValue() == 0;
            return new CommandResult(success, output.toString());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to run command: {}", e.getMessage());
            return new CommandResult(false, "Exception: " + e.getMessage());
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

