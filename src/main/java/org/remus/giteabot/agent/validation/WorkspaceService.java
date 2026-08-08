package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    static final String REPOSITORY_DIRECTORY_NAME = "repository";
    private static final String WORKSPACE_ROOT_MARKER = ".agent-workspace";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final List<String> GIT_ENVIRONMENT_ALLOWLIST = List.of(
            "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP",
            "SystemRoot", "windir", "COMSPEC", "PATHEXT",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "SSL_CERT_FILE", "SSL_CERT_DIR");
    private final Path workspaceBaseDir;
    private final ConcurrentMap<Path, Path> credentialsByWorkspace = new ConcurrentHashMap<>();

    /** Creates a service that places workspaces under the system temporary directory. */
    public WorkspaceService() {
        this(null);
    }

    /** Creates a service that places private workspace parents under the configured directory. */
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
     *
     * <p>Credentials are never embedded in the remote URL: the clone/push use a
     * git credential-store file placed <em>outside</em> the workspace, so the
     * token never lands in {@code .git/config}, in git error output, or in the
     * agent's file/search tools.</p>
     */
    public WorkspaceResult prepareWorkspace(String owner, String repo, String branch,
                                            String cloneBaseUrl, String token, Long prNumber) {
        WorkspaceSetup setup = null;
        try {
            setup = createWorkspaceSetup();
            Path workspaceDir = setup.workspaceDir();
            log.info("Cloning repository to {} for workspace", workspaceDir);

            String cloneUrl = buildCloneUrl(owner, repo, cloneBaseUrl);
            Path credentialsFile = createCredentialsFile(cloneBaseUrl, token, workspaceDir);
            setup.setCredentialsFile(credentialsFile);
            String[] credentialConfig = credentialConfigArgs(credentialsFile);
            CommandResult cloneResult = runCommand(workspaceDir.getParent().toFile(),
                    withCredentialConfig(credentialConfig,
                            "clone", "--depth", "1", "--branch", branch,
                            cloneUrl, workspaceDir.getFileName().toString()),
                    60);

            if (cloneResult.success()) {
                registerCredentialsFile(workspaceDir, credentialsFile);
                return WorkspaceResult.success(workspaceDir);
            }

            // Fork PR fallback: clone default branch → fetch PR head ref
            if (prNumber != null) {
                log.info("Branch clone failed, falling back to PR head ref for PR #{}: {}",
                        prNumber, cloneResult.output());
                // Tear down the failed attempt (private parent AND its credential
                // file) before starting the retry, so a partial directory deletion
                // can never orphan the token file — the setup holder keeps both
                // references alive until both are deleted.
                cleanupWorkspace(setup);
                setup = createWorkspaceSetup();
                workspaceDir = setup.workspaceDir();
                credentialsFile = createCredentialsFile(cloneBaseUrl, token, workspaceDir);
                setup.setCredentialsFile(credentialsFile);
                credentialConfig = credentialConfigArgs(credentialsFile);

                CommandResult defaultCloneResult = runCommand(workspaceDir.getParent().toFile(),
                        withCredentialConfig(credentialConfig,
                                "clone", "--depth", "1",
                                cloneUrl, workspaceDir.getFileName().toString()),
                        60);

                if (!defaultCloneResult.success()) {
                    log.error("Fallback clone (default branch) also failed: {}",
                            defaultCloneResult.output());
                    cleanupWorkspace(setup);
                    return WorkspaceResult.failure(
                            "Failed to clone repository (branch: " + cloneResult.output()
                                    + "; default branch: " + defaultCloneResult.output() + ")");
                }

                registerCredentialsFile(workspaceDir, credentialsFile);

                CommandResult fetchResult = runCommand(workspaceDir.toFile(),
                        withCredentialConfig(credentialConfigForWorkspace(workspaceDir),
                                "fetch", "origin", "refs/pull/" + prNumber + "/head"),
                        60);

                if (!fetchResult.success()) {
                    log.error("Failed to fetch PR head ref for PR #{}: {}", prNumber,
                            fetchResult.output());
                    cleanupWorkspace(setup);
                    return WorkspaceResult.failure(
                            "Failed to fetch PR head ref for PR #" + prNumber + ": "
                                    + fetchResult.output());
                }

                CommandResult checkoutResult = runCommand(workspaceDir.toFile(),
                        new String[]{"git", "checkout", "-B", branch, "FETCH_HEAD"}, 15);

                if (!checkoutResult.success()) {
                    log.error("Failed to checkout FETCH_HEAD for PR #{}: {}", prNumber,
                            checkoutResult.output());
                    cleanupWorkspace(setup);
                    return WorkspaceResult.failure(
                            "Failed to checkout FETCH_HEAD for PR #" + prNumber + ": "
                                    + checkoutResult.output());
                }

                return WorkspaceResult.success(workspaceDir);
            }

            // No fallback — report the original clone error
            log.error("Failed to clone repository: {}", cloneResult.output());
            cleanupWorkspace(setup);
            return WorkspaceResult.failure("Failed to clone repository: " + cloneResult.output());

        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            cleanupWorkspace(setup);
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
                withCredentialConfig(credentialConfigForWorkspace(workspaceDir),
                        "push", "origin", branchName), 60);
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
     * Cleans up a workspace directory and its private temporary parent, including
     * the external git credential-store file created by {@link #prepareWorkspace}.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir == null) {
            return;
        }
        Path workspaceRoot = workspaceRootFor(workspaceDir);
        WorkspaceSetup setup = new WorkspaceSetup(workspaceRoot != null ? workspaceRoot : workspaceDir);
        setup.setCredentialsFile(credentialsByWorkspace.remove(workspaceKey(workspaceDir)));
        cleanupWorkspace(setup);
    }

    /**
     * Cleans up a whole {@link WorkspaceSetup}: the credential-store file and
     * the private temporary parent are deleted together. Keeping both
     * references in one holder guarantees that no retry path can drop the
     * credential file after a partial directory deletion.
     */
    void cleanupWorkspace(WorkspaceSetup setup) {
        if (setup == null) {
            return;
        }
        deleteCredentialsFile(setup.credentialsFile());
        try {
            deleteDirectory(setup.workspaceRoot());
            log.debug("Cleaned up workspace: {}", setup.workspaceDir());
        } catch (IOException e) {
            log.warn("Failed to clean up workspace {}: {}", setup.workspaceDir(), e.getMessage());
        }
    }

    // ---- internal helpers ------------------------------------------------

    /**
     * Builds the credential-free clone URL. For local filesystem paths (used in
     * tests and local development), passes through as-is.
     */
    String buildCloneUrl(String owner, String repo, String cloneBaseUrl) {
        if (cloneBaseUrl.startsWith("file://") || cloneBaseUrl.startsWith("/")) {
            return cloneBaseUrl;
        }
        String protocol = cloneBaseUrl.startsWith("https://") ? "https" : "http";
        String baseUrl = cloneBaseUrl.replaceFirst("https?://", "");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return String.format("%s://%s/%s/%s.git", protocol, baseUrl, owner, repo);
    }

    /**
     * Writes a git credential-store file <em>outside</em> the workspace in its
     * private temporary parent, so the token is never stored inside the cloned
     * repository. Returns {@code null} for local paths or blank tokens.
     */
    Path createCredentialsFile(String cloneBaseUrl, String token, Path workspaceDir)
            throws IOException {
        if (token == null || token.isBlank()
                || cloneBaseUrl.startsWith("file://") || cloneBaseUrl.startsWith("/")) {
            return null;
        }
        String protocol = cloneBaseUrl.startsWith("https://") ? "https" : "http";
        String baseUrl = cloneBaseUrl.replaceFirst("https?://", "");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String host = baseUrl.contains("/") ? baseUrl.substring(0, baseUrl.indexOf('/')) : baseUrl;

        Path workspaceRoot = workspaceRootFor(workspaceDir);
        if (workspaceRoot == null) {
            throw new IOException("Workspace does not have a private credential directory");
        }
        Path credentialsFile = Files.createTempFile(workspaceRoot, "credentials-", ".store");
        restrictToOwner(credentialsFile, false);
        Files.writeString(credentialsFile, protocol + "://oauth2:" + token + "@" + host + "\n");
        return credentialsFile;
    }

    /** Creates a private temporary parent and returns its repository child path. */
    Path createWorkspaceDirectory() throws IOException {
        return createWorkspaceSetup().workspaceDir();
    }

    /**
     * Creates a new workspace attempt as a single {@link WorkspaceSetup} holding
     * the private temporary parent (with its marker file) and, once created, the
     * external credential-store file. The holder is the unit of cleanup, so both
     * always travel together through retry and error paths.
     */
    WorkspaceSetup createWorkspaceSetup() throws IOException {
        Path workspaceRoot;
        if (workspaceBaseDir == null) {
            workspaceRoot = Files.createTempDirectory("agent-workspace-");
        } else {
            Files.createDirectories(workspaceBaseDir);
            workspaceRoot = Files.createTempDirectory(workspaceBaseDir, "agent-workspace-");
        }
        try {
            restrictToOwner(workspaceRoot, true);
            Files.createFile(workspaceRoot.resolve(WORKSPACE_ROOT_MARKER));
            return new WorkspaceSetup(workspaceRoot);
        } catch (IOException e) {
            deleteDirectory(workspaceRoot);
            throw e;
        }
    }

    /** Returns the private temporary parent created for the supplied workspace, if any. */
    private Path workspaceRootFor(Path workspaceDir) {
        if (workspaceDir == null || workspaceDir.getFileName() == null
                || !REPOSITORY_DIRECTORY_NAME.equals(workspaceDir.getFileName().toString())) {
            return null;
        }
        Path workspaceRoot = workspaceDir.getParent();
        if (workspaceRoot == null) {
            return null;
        }
        Path marker = workspaceRoot.resolve(WORKSPACE_ROOT_MARKER);
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) ? workspaceRoot : null;
    }

    /** Applies owner-only permissions where the host filesystem supports them. */
    private void restrictToOwner(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    directory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS);
            return;
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs do not expose POSIX permissions through NIO.
        }
        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        if (directory) {
            file.setExecutable(false, false);
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }

    /**
     * git {@code -c} arguments that authenticate via the credential-store file
     * used for every authenticated Git command.
     */
    private String[] credentialConfigArgs(Path credentialsFile) {
        if (credentialsFile == null) {
            return new String[0];
        }
        return new String[]{
                "-c", "credential.helper=",
                "-c", "credential.helper=store --file=" + credentialsFile.toAbsolutePath()};
    }

    private String[] withCredentialConfig(String[] credentialConfig, String... gitArgs) {
        String[] command = new String[1 + credentialConfig.length + gitArgs.length];
        command[0] = "git";
        System.arraycopy(credentialConfig, 0, command, 1, credentialConfig.length);
        System.arraycopy(gitArgs, 0, command, 1 + credentialConfig.length, gitArgs.length);
        return command;
    }

    void registerCredentialsFile(Path workspaceDir, Path credentialsFile) {
        if (credentialsFile == null) {
            return;
        }
        credentialsByWorkspace.put(workspaceKey(workspaceDir), credentialsFile);
    }

    String[] credentialConfigForWorkspace(Path workspaceDir) {
        return credentialConfigArgs(credentialsByWorkspace.get(workspaceKey(workspaceDir)));
    }

    private Path workspaceKey(Path workspaceDir) {
        return workspaceDir.toAbsolutePath().normalize();
    }

    /** Deletes a credential-store file after a failed workspace setup. */
    private void deleteCredentialsFile(Path credentialsFile) {
        if (credentialsFile != null) {
            try {
                Files.deleteIfExists(credentialsFile);
            } catch (IOException e) {
                log.warn("Failed to delete git credentials file {}: {}", credentialsFile, e.getMessage());
            }
        }
    }

    private CommandResult runCommand(File workDir, String[] command, int timeoutSeconds) {
        Path disabledHooksDirectory = null;
        Path emptyGlobalGitConfig = null;
        try {
            // Git reads repository-controlled configuration after untrusted code ran in the workspace.
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
            scrubEnvironmentForGit(pb);
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalGitConfig.toString());

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

    /** Keeps credentials and application secrets out of Git subprocesses. */
    private void scrubEnvironmentForGit(ProcessBuilder processBuilder) {
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        for (String name : GIT_ENVIRONMENT_ALLOWLIST) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
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
