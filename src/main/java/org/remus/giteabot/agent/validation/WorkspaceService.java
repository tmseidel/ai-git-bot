package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.SshEndpoint;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    private final Path workspaceBaseDir;
    private final ConcurrentMap<Path, WorkspaceSetup> setupsByWorkspace = new ConcurrentHashMap<>();

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
     * <p>The repository client resolves the credential-free remote and its
     * credentials once before any workspace is allocated. HTTP credentials use
     * a credential-store file outside the checkout; SSH credentials use private
     * key and {@code known_hosts} files in the same private temporary parent.</p>
     */
    public WorkspaceResult prepareWorkspace(RepositoryApiClient repositoryClient,
                                            String owner, String repo, String branch, Long prNumber) {
        final String repositoryRemote;
        final RepositoryCredentials credentials;
        try {
            repositoryRemote = repositoryClient.getRepositoryRemote(owner, repo);
            credentials = repositoryClient.getCredentials();
            if (repositoryRemote == null || repositoryRemote.isBlank() || credentials == null) {
                throw new IllegalStateException("Repository client returned incomplete checkout configuration");
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Failed to resolve repository checkout for {}/{}: {}", owner, repo, message, e);
            return WorkspaceResult.failure("Failed to resolve repository checkout: " + message);
        }

        WorkspaceSetup setup = null;
        try {
            setup = createWorkspaceSetup();
            Path workspaceDir = setup.workspaceDir();
            log.info("Cloning repository to {} for workspace", workspaceDir);

            setup.setAuthentication(repositoryRemote, credentials);
            CommandResult cloneResult = runRemoteCommand(setup, workspaceDir.getParent().toFile(), 60,
                    "clone", "--depth", "1", "--branch", branch,
                    repositoryRemote, workspaceDir.getFileName().toString());

            if (cloneResult.success()) {
                registerWorkspace(setup);
                return WorkspaceResult.success(workspaceDir);
            }

            // Fork PR fallback: clone default branch → fetch PR head ref
            if (prNumber != null) {
                log.info("Branch clone failed, falling back to PR head ref for PR #{}: {}",
                        prNumber, cloneResult.output());
                // Tear down the failed attempt before starting the retry.
                if (!cleanupWorkspace(setup)) {
                    return WorkspaceResult.failure("Failed to clean up the initial clone attempt");
                }
                setup = createWorkspaceSetup();
                workspaceDir = setup.workspaceDir();
                setup.setAuthentication(repositoryRemote, credentials);

                CommandResult defaultCloneResult = runRemoteCommand(setup,
                        workspaceDir.getParent().toFile(), 60,
                        "clone", "--depth", "1", repositoryRemote, workspaceDir.getFileName().toString());

                if (!defaultCloneResult.success()) {
                    log.error("Fallback clone (default branch) also failed: {}",
                            defaultCloneResult.output());
                    cleanupWorkspace(setup);
                    return WorkspaceResult.failure(
                            "Failed to clone repository (branch: " + cloneResult.output()
                                    + "; default branch: " + defaultCloneResult.output() + ")");
                }

                registerWorkspace(setup);

                CommandResult fetchResult = runRemoteCommand(setup, workspaceDir.toFile(), 60,
                        "fetch", "origin", "refs/pull/" + prNumber + "/head");

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
        } catch (RuntimeException e) {
            cleanupWorkspace(setup);
            throw e;
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
        WorkspaceSetup setup = setupsByWorkspace.get(workspaceKey(workspaceDir));
        if (setup == null) {
            log.error("Cannot commit workspace without authentication state: {}", workspaceDir);
            return false;
        }
        synchronized (setup) {
            if (setup.closed() || setup.repositoryCredentials() == null) {
                log.error("Cannot commit a closed workspace: {}", workspaceDir);
                return false;
            }

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

            CommandResult pushResult = runRemoteCommand(setup, workspaceDir.toFile(), 60,
                    "push", "origin", branchName);
            if (!pushResult.success()) {
                log.error("git push failed: {}", pushResult.output());
                return false;
            }

            log.info("Successfully committed and pushed to branch '{}'", branchName);
            return true;
        }
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
     * Cleans up a workspace directory, its private temporary parent, and any
     * in-flight Git authentication files.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir == null) {
            return;
        }
        Path workspaceRoot = workspaceRootFor(workspaceDir);
        WorkspaceSetup setup = setupsByWorkspace.get(workspaceKey(workspaceDir));
        if (setup == null) {
            setup = new WorkspaceSetup(workspaceRoot != null ? workspaceRoot : workspaceDir);
        }
        cleanupWorkspace(setup);
    }

    /**
     * Cleans up a whole {@link WorkspaceSetup}, including any authentication
     * files left by an interrupted remote Git command.
     */
    boolean cleanupWorkspace(WorkspaceSetup setup) {
        if (setup == null) {
            return true;
        }
        synchronized (setup) {
            setup.close();
            clearAuthenticationFiles(setup);
            try {
                deleteDirectory(setup.workspaceRoot());
                setupsByWorkspace.remove(workspaceKey(setup.workspaceDir()), setup);
                log.debug("Cleaned up workspace: {}", setup.workspaceDir());
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to clean up workspace {}: {}", setup.workspaceDir(), e.getMessage());
                return false;
            }
        }
    }

    // ---- internal helpers ------------------------------------------------

    /**
     * Writes a git credential-store file <em>outside</em> the workspace in its
     * private temporary parent, so the token is never stored inside the cloned
     * repository. Returns {@code null} for local paths or blank tokens.
     */
    Path createCredentialsFile(String repositoryRemote, String username, String token,
                               Path workspaceDir) throws IOException {
        return createCredentialsFile(repositoryRemote, username, token, workspaceDir, null);
    }

    private Path createCredentialsFile(String repositoryRemote, String username, String token,
                                       Path workspaceDir, WorkspaceSetup setup) throws IOException {
        if (token == null || token.isBlank()
                || repositoryRemote.toLowerCase(Locale.ROOT).startsWith("file://")
                || isLocalClonePath(repositoryRemote)
                || isSshCloneUrl(repositoryRemote)) {
            return null;
        }
        String protocol = repositoryRemote.toLowerCase(Locale.ROOT).startsWith("https://")
                ? "https://" : "http://";
        String baseUrl = repositoryRemote.substring(protocol.length());
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String host = baseUrl.contains("/") ? baseUrl.substring(0, baseUrl.indexOf('/')) : baseUrl;

        Path workspaceRoot = workspaceRootFor(workspaceDir);
        if (workspaceRoot == null) {
            throw new IOException("Workspace does not have a private credential directory");
        }
        Path credentialsFile = Files.createTempFile(workspaceRoot, "credentials-", ".store");
        if (setup != null) {
            setup.setCredentialsFile(credentialsFile);
        }
        try {
            String credentialUsername = username == null || username.isBlank() ? "oauth2" : username;
            return writeSecretFile(credentialsFile,
                    protocol + credentialUsername + ":" + token + "@" + host + "\n");
        } catch (IOException e) {
            if (deleteSecretFile(credentialsFile) && setup != null) {
                setup.setCredentialsFile(null);
            }
            throw e;
        }
    }

    void createAuthenticationFiles(String repositoryRemote, RepositoryCredentials credentials,
                                    WorkspaceSetup setup) throws IOException {
        if (!credentials.usesSsh()) {
            createCredentialsFile(repositoryRemote, credentials.username(), credentials.token(),
                    setup.workspaceDir(), setup);
            return;
        }
        if (credentials.sshPrivateKey() == null || credentials.sshPrivateKey().isBlank()
                || credentials.sshKnownHosts() == null || credentials.sshKnownHosts().isBlank()) {
            throw new IOException("SSH private key and known_hosts are required");
        }
        Path privateKey = Files.createTempFile(setup.workspaceRoot(), "ssh-key-", ".tmp");
        setup.setSshPrivateKeyFile(privateKey);
        writeSecretFile(privateKey, normalizeSecret(credentials.sshPrivateKey()));
        Path knownHosts = Files.createTempFile(setup.workspaceRoot(), "known-hosts-", ".tmp");
        setup.setSshKnownHostsFile(knownHosts);
        writeSecretFile(knownHosts, normalizeSecret(credentials.sshKnownHosts()));
    }

    void clearAuthenticationFiles(WorkspaceSetup setup) {
        if (deleteSecretFile(setup.credentialsFile())) {
            setup.setCredentialsFile(null);
        }
        if (deleteSecretFile(setup.sshPrivateKeyFile())) {
            setup.setSshPrivateKeyFile(null);
        }
        if (deleteSecretFile(setup.sshKnownHostsFile())) {
            setup.setSshKnownHostsFile(null);
        }
    }

    private String normalizeSecret(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private Path writeSecretFile(Path file, String content) throws IOException {
        restrictToOwner(file, false);
        Files.writeString(file, content);
        return file;
    }

    /** Creates a private temporary parent and returns its repository child path. */
    Path createWorkspaceDirectory() throws IOException {
        return createWorkspaceSetup().workspaceDir();
    }

    /**
     * Creates a new workspace attempt with a private temporary parent and marker.
     */
    WorkspaceSetup createWorkspaceSetup() throws IOException {
        retryFailedCleanups();
        Path workspaceRoot;
        if (workspaceBaseDir == null) {
            workspaceRoot = Files.createTempDirectory("agent-workspace-");
        } else {
            Files.createDirectories(workspaceBaseDir);
            workspaceRoot = Files.createTempDirectory(workspaceBaseDir, "agent-workspace-");
        }
        WorkspaceSetup setup = new WorkspaceSetup(workspaceRoot);
        registerWorkspace(setup);
        try {
            restrictToOwner(workspaceRoot, true);
            Files.createFile(workspaceRoot.resolve(WORKSPACE_ROOT_MARKER));
            return setup;
        } catch (IOException | RuntimeException e) {
            cleanupWorkspace(setup);
            throw e;
        }
    }

    private void retryFailedCleanups() {
        for (WorkspaceSetup setup : setupsByWorkspace.values()) {
            if (setup.closed()) {
                cleanupWorkspace(setup);
            }
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
            // Use the native ACL view when POSIX permissions are unavailable.
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            throw new IOException("Owner-only permissions are unsupported for " + path);
        }
        AclEntry ownerAccess = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(AclEntryPermission.values())
                .build();
        aclView.setAcl(List.of(ownerAccess));
    }

    /** Git {@code -c} arguments used for every remote command in one workspace. */
    String[] gitConfigArgs(WorkspaceSetup setup) {
        List<String> args = new ArrayList<>();
        if (setup != null && setup.credentialsFile() != null) {
            args.add("-c");
            args.add("credential.helper=");
            args.add("-c");
            args.add("credential.helper=store --file=" + setup.credentialsFile().toAbsolutePath());
        }
        if (setup != null && setup.sshPrivateKeyFile() != null && setup.sshKnownHostsFile() != null) {
            String sshCommand = "ssh -F /dev/null -i " + shellQuote(setup.sshPrivateKeyFile())
                    + " -o UserKnownHostsFile=" + shellQuote(setup.sshKnownHostsFile())
                    + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                    + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes";
            args.add("-c");
            args.add("core.sshCommand=" + sshCommand);
        }
        return args.toArray(String[]::new);
    }

    String[] withGitConfig(String[] gitConfig, String... gitArgs) {
        String[] command = new String[1 + gitConfig.length + gitArgs.length];
        command[0] = "git";
        System.arraycopy(gitConfig, 0, command, 1, gitConfig.length);
        System.arraycopy(gitArgs, 0, command, 1 + gitConfig.length, gitArgs.length);
        return command;
    }

    private String shellQuote(Path value) {
        return "'" + value.toAbsolutePath().normalize().toString().replace("'", "'\"'\"'") + "'";
    }

    private boolean isSshCloneUrl(String cloneUrl) {
        return SshEndpoint.isSshRemote(cloneUrl);
    }

    private boolean isLocalClonePath(String cloneUrl) {
        return cloneUrl.startsWith("/") || cloneUrl.startsWith("\\\\")
                || cloneUrl.length() >= 3 && Character.isLetter(cloneUrl.charAt(0))
                && cloneUrl.charAt(1) == ':'
                && (cloneUrl.charAt(2) == '\\' || cloneUrl.charAt(2) == '/');
    }

    void registerWorkspace(WorkspaceSetup setup) {
        synchronized (setup) {
            if (!setup.closed()) {
                setupsByWorkspace.put(workspaceKey(setup.workspaceDir()), setup);
            }
        }
    }

    CommandResult fetchBranch(Path workspaceDir, String branch) {
        return runRemoteCommand(setupsByWorkspace.get(workspaceKey(workspaceDir)),
                workspaceDir.toFile(), 60,
                "fetch", "origin", "refs/heads/" + branch + ":refs/remotes/origin/" + branch);
    }

    private CommandResult runRemoteCommand(WorkspaceSetup setup, File workDir, int timeoutSeconds,
                                           String... gitArgs) {
        if (setup == null) {
            return new CommandResult(false, "Workspace authentication is unavailable");
        }
        synchronized (setup) {
            if (setup.closed()) {
                return new CommandResult(false, "Workspace was already cleaned up");
            }
            if (setup.repositoryCredentials() == null) {
                return new CommandResult(false, "Workspace authentication is unavailable");
            }
            CommandResult result = null;
            RuntimeException commandError = null;
            try {
                clearAuthenticationFiles(setup);
                if (hasAuthenticationFiles(setup)) {
                    result = new CommandResult(false, "Could not remove previous Git authentication files");
                } else {
                    createAuthenticationFiles(setup.repositoryRemote(), setup.repositoryCredentials(), setup);
                    result = runCommand(workDir, withGitConfig(gitConfigArgs(setup), gitArgs), timeoutSeconds);
                }
            } catch (IOException e) {
                result = new CommandResult(false, "Failed to prepare Git authentication: " + e.getMessage());
            } catch (RuntimeException e) {
                commandError = e;
            } finally {
                clearAuthenticationFiles(setup);
            }
            if (hasAuthenticationFiles(setup)) {
                cleanupWorkspace(setup);
                if (commandError != null) {
                    commandError.addSuppressed(new IOException("Could not remove Git authentication files"));
                    throw commandError;
                }
                return new CommandResult(false, "Could not remove Git authentication files");
            }
            if (commandError != null) {
                throw commandError;
            }
            return result;
        }
    }

    private boolean hasAuthenticationFiles(WorkspaceSetup setup) {
        return setup.credentialsFile() != null || setup.sshPrivateKeyFile() != null
                || setup.sshKnownHostsFile() != null;
    }

    private Path workspaceKey(Path workspaceDir) {
        return workspaceDir.toAbsolutePath().normalize();
    }

    /** Deletes an authentication file after a failed or completed workspace. */
    private boolean deleteSecretFile(Path file) {
        if (file == null) {
            return true;
        }
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete Git authentication file {}: {}", file, e.getMessage());
            return false;
        }
    }

    private CommandResult runCommand(File workDir, String[] command, int timeoutSeconds) {
        Path disabledHooksDirectory = null;
        Path emptyGlobalGitConfig = null;
        try {
            // Git reads repository-controlled configuration after untrusted code ran in the workspace.
            disabledHooksDirectory = Files.createTempDirectory("ai-git-bot-empty-hooks-");
            emptyGlobalGitConfig = Files.createTempFile(disabledHooksDirectory, "global-", ".gitconfig");
            List<String> gitCommand = new ArrayList<>(command.length + 6);
            gitCommand.add(command[0]);
            gitCommand.add("-c");
            gitCommand.add("core.hooksPath=" + disabledHooksDirectory.toAbsolutePath().normalize());
            gitCommand.add("-c");
            gitCommand.add("core.fsmonitor=false");
            gitCommand.add("-c");
            gitCommand.add("credential.helper=");
            gitCommand.addAll(Arrays.asList(command).subList(1, command.length));
            ProcessBuilder pb = new ProcessBuilder(gitCommand);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironmentForGit(pb);
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalGitConfig.toString());

            ProcessSupport.CommandResult result = ProcessSupport.run(
                    pb, timeoutSeconds, TimeUnit.SECONDS, 1024 * 1024);
            if (!result.finished()) {
                return new CommandResult(false,
                        "Command timed out after " + timeoutSeconds + " seconds");
            }

            return new CommandResult(result.exitCode() == 0, result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command was interrupted", e);
        } catch (IOException e) {
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

    void deleteDirectory(Path dir) throws IOException {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(dir)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                log.warn("Failed to delete {}: {}", path, e.getMessage());
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
