package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.model.FileChange;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
 *     <li>Applying {@link FileChange}s (create / update / delete) — including
 *         diff-based updates via {@link DiffApplyService}</li>
 *     <li>Cleaning up temporary workspace directories</li>
 * </ul>
 */
@Slf4j
@Service
public class WorkspaceService {

    private final DiffApplyService diffApplyService;

    public WorkspaceService(DiffApplyService diffApplyService) {
        this.diffApplyService = diffApplyService;
    }

    /**
     * Prepares a workspace by cloning the repository and applying file changes.
     *
     * @param owner       Repository owner
     * @param repo        Repository name
     * @param branch      The branch to clone
     * @param fileChanges The file changes to apply after cloning
     * @param cloneBaseUrl The Git server base URL (e.g. {@code http://localhost:3000})
     * @param token       The API / clone token
     * @return {@link WorkspaceResult} containing the workspace path or error details
     */
    public WorkspaceResult prepareWorkspace(String owner, String repo, String branch,
                                            List<FileChange> fileChanges,
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

            List<String> failedDiffs = new ArrayList<>();
            for (FileChange change : fileChanges) {
                try {
                    applyFileChangeToWorkspace(tempDir, change);
                } catch (DiffApplyService.DiffApplyException e) {
                    log.warn("Diff application failed for {} during workspace preparation: {}",
                            change.getPath(), e.getMessage());
                    failedDiffs.add(change.getPath());
                }
            }

            return WorkspaceResult.success(tempDir, failedDiffs);

        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            return WorkspaceResult.failure("Failed to prepare workspace: " + e.getMessage());
        }
    }

    /**
     * Applies a single {@link FileChange} to the workspace directory.
     * <p>
     * For diff-based UPDATE operations the existing file is read, the diff is
     * applied via {@link DiffApplyService}, and the result is written back.
     * For CREATE or full-content UPDATE operations the content is written directly.
     *
     * @param workspaceDir The workspace directory (cloned repo root)
     * @param change       The file change to apply
     * @throws IOException if a file operation fails
     */
    public void applyFileChangeToWorkspace(Path workspaceDir, FileChange change) throws IOException {
        Path filePath = workspaceDir.resolve(change.getPath());

        switch (change.getOperation()) {
            case CREATE -> {
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, change.getContent());
            }
            case UPDATE -> {
                Files.createDirectories(filePath.getParent());
                if (change.isDiffBased()) {
                    if (Files.exists(filePath)) {
                        String existingContent = Files.readString(filePath);
                        try {
                            String newContent = diffApplyService.applyDiff(existingContent, change.getDiff());
                            Files.writeString(filePath, newContent);
                        } catch (DiffApplyService.DiffApplyException e) {
                            log.warn("Diff application failed for {}: {}. " +
                                            "Keeping original file content — the agent should provide " +
                                            "the complete file content instead of a diff.",
                                    change.getPath(), e.getMessage());
                            // Original file content is preserved (writeString was never called).
                            // Rethrow so callers can collect the failure and ask the AI for full content.
                            throw e;
                        }
                    } else {
                        log.warn("Diff-based update for {} but file does not exist in workspace, skipping",
                                change.getPath());
                    }
                } else {
                    Files.writeString(filePath, change.getContent());
                }
            }
            case DELETE -> Files.deleteIfExists(filePath);
        }
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

