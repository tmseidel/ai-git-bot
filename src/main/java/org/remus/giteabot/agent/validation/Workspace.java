package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.SshEndpoint;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A local checkout the bot works in, created by {@link WorkspaceService}.
 * <p>
 * It runs Git in its directory, holding the credentials in memory and materializing
 * authentication files only during remote commands. {@link #close()} removes the private
 * temporary parent, including any file a remote command left behind.
 */
@Slf4j
public class Workspace implements AutoCloseable {

    /** Git author and committer identity for commits the bot pushes. */
    static final String GIT_AUTHOR_NAME = "AI Agent";
    static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private static final String REPOSITORY_DIRECTORY_NAME = "repository";

    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final Path root;
    private final Consumer<Workspace> onCleanedUp;
    private Path credentialsFile;
    private Path sshPrivateKeyFile;
    private Path sshKnownHostsFile;
    private String repositoryRemote;
    private RepositoryCredentials repositoryCredentials;
    private boolean usesAuthorizationHeader;
    private volatile boolean closed;

    /**
     * @param root        the private temporary parent, which contains the repository directory
     * @param onCleanedUp called once the directory is gone
     */
    Workspace(Path root, Consumer<Workspace> onCleanedUp) {
        this.root = root;
        this.directory = root.resolve(REPOSITORY_DIRECTORY_NAME);
        this.onCleanedUp = onCleanedUp;
    }

    private Workspace(Path directory) {
        this.root = null;
        this.directory = directory;
        this.onCleanedUp = workspace -> { };
    }

    /**
     * Wraps a directory {@link WorkspaceService} did not prepare, e.g. the sandboxed PR test
     * workspace. Local Git commands work, but it has no credentials to reach a remote, and
     * {@link #close()} leaves the directory in place.
     */
    public static Workspace unmanaged(Path directory) {
        return new Workspace(directory);
    }

    /** The directory of the checkout. */
    public Path path() {
        return directory;
    }

    /**
     * Commits all changes in the workspace and pushes them to the remote.
     * <p>
     * If {@code createNewBranch} is {@code true} a new local branch is created first
     * ({@code git checkout -b branchName}).  Otherwise the workspace is assumed to be
     * already on the target branch (cloned with {@code --branch branchName}).
     *
     * @param branchName      Name of the target branch (new or existing)
     * @param commitMessage   Commit message
     * @param createNewBranch {@code true} to create the branch before committing
     * @return {@code true} if commit and push succeeded
     */
    public boolean commitAndPush(String branchName, String commitMessage, boolean createNewBranch) {
        return commitAndPush(branchName, commitMessage, createNewBranch, () -> { });
    }

    /**
     * Runs the caller's live write check immediately before push; exceptions abort
     * publication. The original overload preserves the contract of other workflows.
     */
    public boolean commitAndPush(String branchName, String commitMessage, boolean createNewBranch,
                                 Runnable beforePush) {
        synchronized (this) {
            if (closed || repositoryCredentials == null) {
                log.error("Cannot commit a closed workspace or one without authentication state: {}", directory);
                return false;
            }

            if (createNewBranch) {
                CommandResult checkoutResult = git(15, "checkout", "-b", branchName);
                if (!checkoutResult.success()) {
                    log.error("Failed to create branch '{}': {}", branchName, checkoutResult.output());
                    return false;
                }
            }

            CommandResult addResult = git(15, "add", "-A");
            if (!addResult.success()) {
                log.error("git add -A failed: {}", addResult.output());
                return false;
            }

            // The identity is passed only to this commit: the environment overrides any
            // user.*, author.* or committer.* entry the workspace's .git/config may contain.
            CommandResult commitResult = GitCommandRunner.run(directory.toFile(),
                    new String[]{"git", "commit", "-m", commitMessage}, 15, Map.of(
                            "GIT_AUTHOR_NAME", GIT_AUTHOR_NAME,
                            "GIT_AUTHOR_EMAIL", GIT_AUTHOR_EMAIL,
                            "GIT_COMMITTER_NAME", GIT_AUTHOR_NAME,
                            "GIT_COMMITTER_EMAIL", GIT_AUTHOR_EMAIL));
            if (!commitResult.success()) {
                // "nothing to commit" is not a real error
                if (commitResult.output().contains("nothing to commit")) {
                    log.warn("Nothing to commit in workspace — no file changes were made");
                    return false;
                }
                log.error("git commit failed: {}", commitResult.output());
                return false;
            }

            // The check may do network I/O under this workspace's lock. Keep it with
            // the push so cleanup cannot release authentication between them.
            beforePush.run();
            CommandResult pushResult = gitRemote(60, "push", "origin", branchName);
            if (!pushResult.success()) {
                log.error("git push failed: {}", pushResult.output());
                return false;
            }

            log.info("Successfully committed and pushed to branch '{}'", branchName);
            return true;
        }
    }

    /**
     * Stages changes to include new/deleted files in a bounded comment preview.
     * Call after checking the allowed file scope and before committing.
     */
    public String stagedDiff() {
        CommandResult add = git(15, "add", "-A");
        if (!add.success()) {
            throw new IllegalStateException("Cannot stage documentation diff: " + add.output());
        }
        CommandResult diff = git(15, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-color", "HEAD", "--");
        if (!diff.success()) {
            throw new IllegalStateException("Cannot read documentation diff: " + diff.output());
        }
        return diff.output();
    }

    /**
     * Returns whether the workspace contains changes that Git would commit.
     * Empty directories are intentionally ignored by Git and therefore return {@code false}.
     */
    public boolean hasUncommittedChanges() {
        CommandResult statusResult = git(10, "status", "--porcelain");
        if (!statusResult.success()) {
            log.warn("Could not inspect workspace git status: {}", statusResult.output());
            return true;
        }
        return !statusResult.output().isBlank();
    }

    /**
     * Returns the workspace-relative paths of every file Git currently sees as
     * changed (added, modified, renamed or untracked).
     * Parsed from {@code git status --porcelain}; rename entries surface their
     * destination path. Used by callers that need to assert which files are
     * about to be committed — e.g. the unit-test workflow's pre-commit guard.
     *
     * @return the changed paths (forward slashes), never {@code null}.
     */
    public List<String> listChangedFiles() {
        List<String> changed = new ArrayList<>();
        CommandResult statusResult = git(10, "status", "--porcelain");
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
     * uncommitted changes. Used by the optional
     * Critic / Reflection step to give the LLM a compact view of what is
     * about to be committed without paying for the full diff.
     *
     * @return a textual summary, possibly empty; never {@code null}.
     */
    public String diffStat() {
        CommandResult result = git(15, "diff", "--stat", "HEAD");
        if (!result.success()) {
            log.debug("git diff --stat failed: {}", result.output());
            return "";
        }
        String out = result.output();
        return out == null ? "" : out.strip();
    }

    /**
     * Removes the workspace, its private temporary parent, and any in-flight Git
     * authentication files. A failed removal is logged and retried by
     * {@link WorkspaceService} before it creates the next workspace.
     */
    @Override
    public void close() {
        cleanup();
    }

    /**
     * Same as {@link #close()}, reporting whether the workspace is gone.
     */
    boolean cleanup() {
        synchronized (this) {
            closed = true;
            repositoryRemote = null;
            repositoryCredentials = null;
            clearAuthenticationFiles();
            if (root == null) {
                return true;
            }
            try {
                deleteDirectory(root);
                onCleanedUp.accept(this);
                log.debug("Cleaned up workspace: {}", directory);
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to clean up workspace {}: {}", directory, e.getMessage());
                return false;
            }
        }
    }

    boolean closed() {
        return closed;
    }

    /** The private temporary parent, or {@code null} if unmanaged. */
    Path root() {
        return root;
    }

    String repositoryRemote() {
        return repositoryRemote;
    }

    void setAuthentication(String repositoryRemote, RepositoryCredentials repositoryCredentials,
                           boolean usesAuthorizationHeader) {
        this.repositoryRemote = repositoryRemote;
        this.repositoryCredentials = repositoryCredentials;
        this.usesAuthorizationHeader = usesAuthorizationHeader;
    }

    /** Runs a local Git command in the checkout. */
    CommandResult git(int timeoutSeconds, String... gitArgs) {
        String[] command = Stream.concat(Stream.of("git"), Arrays.stream(gitArgs)).toArray(String[]::new);
        return GitCommandRunner.run(directory.toFile(), command, timeoutSeconds);
    }

    /** Runs a Git command that reaches the remote in the checkout. */
    CommandResult gitRemote(int timeoutSeconds, String... gitArgs) {
        return gitRemote(directory.toFile(), timeoutSeconds, gitArgs);
    }

    /** Runs a Git command that reaches the remote in {@code workDir}, e.g. a clone in the parent. */
    CommandResult gitRemote(File workDir, int timeoutSeconds, String... gitArgs) {
        synchronized (this) {
            if (closed) {
                return new CommandResult(false, "Workspace was already cleaned up");
            }
            if (repositoryCredentials == null) {
                return new CommandResult(false, "Workspace authentication is unavailable");
            }
            CommandResult result = null;
            RuntimeException commandError = null;
            try {
                clearAuthenticationFiles();
                if (hasAuthenticationFiles()) {
                    result = new CommandResult(false, "Could not remove previous Git authentication files");
                } else {
                    createAuthenticationFiles(repositoryRemote, repositoryCredentials);
                    result = GitCommandRunner.run(workDir, withGitConfig(gitConfigArgs(), gitArgs), timeoutSeconds,
                            authorizationHeaderEnvironment());
                }
            } catch (IOException e) {
                result = new CommandResult(false, "Failed to prepare Git authentication: " + e.getMessage());
            } catch (RuntimeException e) {
                commandError = e;
            } finally {
                clearAuthenticationFiles();
            }
            if (hasAuthenticationFiles()) {
                cleanup();
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

    CommandResult fetchBranch(String branch) {
        return gitRemote(60, "fetch", "origin", "refs/heads/" + branch + ":refs/remotes/origin/" + branch);
    }

    // ---- authentication files --------------------------------------------

    Path credentialsFile() {
        return credentialsFile;
    }

    Path sshPrivateKeyFile() {
        return sshPrivateKeyFile;
    }

    Path sshKnownHostsFile() {
        return sshKnownHostsFile;
    }

    /**
     * Writes a git credential-store file <em>outside</em> the checkout in its
     * private temporary parent, so the token is never stored inside the cloned
     * repository. Returns {@code null} for local paths or blank tokens.
     */
    Path createCredentialsFile(String remote, String username, String token) throws IOException {
        if (token == null || token.isBlank()
                || remote.toLowerCase(Locale.ROOT).startsWith("file://")
                || isLocalClonePath(remote)
                || SshEndpoint.isSshRemote(remote)) {
            return null;
        }
        String protocol = remote.toLowerCase(Locale.ROOT).startsWith("https://")
                ? "https://" : "http://";
        String baseUrl = remote.substring(protocol.length());
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String host = baseUrl.contains("/") ? baseUrl.substring(0, baseUrl.indexOf('/')) : baseUrl;

        Path file = Files.createTempFile(root, "credentials-", ".store");
        credentialsFile = file;
        try {
            String credentialUsername = username == null || username.isBlank() ? "oauth2" : username;
            return writeSecretFile(file, protocol + credentialUsername + ":" + token + "@" + host + "\n");
        } catch (IOException e) {
            if (deleteSecretFile(file)) {
                credentialsFile = null;
            }
            throw e;
        }
    }

    void createAuthenticationFiles(String remote, RepositoryCredentials credentials) throws IOException {
        if (!credentials.usesSsh()) {
            if (!authorizationHeaderEnvironment().isEmpty()) {
                // The token travels as an HTTP header, see authorizationHeaderEnvironment.
                // Otherwise fall through, so opting in can never drop authentication.
                return;
            }
            createCredentialsFile(remote, credentials.username(), credentials.token());
            return;
        }
        if (credentials.sshPrivateKey() == null || credentials.sshPrivateKey().isBlank()
                || credentials.sshKnownHosts() == null || credentials.sshKnownHosts().isBlank()) {
            throw new IOException("SSH private key and known_hosts are required");
        }
        sshPrivateKeyFile = Files.createTempFile(root, "ssh-key-", ".tmp");
        writeSecretFile(sshPrivateKeyFile, normalizeSecret(credentials.sshPrivateKey()));
        sshKnownHostsFile = Files.createTempFile(root, "known-hosts-", ".tmp");
        writeSecretFile(sshKnownHostsFile, normalizeSecret(credentials.sshKnownHosts()));
    }

    void clearAuthenticationFiles() {
        if (deleteSecretFile(credentialsFile)) {
            credentialsFile = null;
        }
        if (deleteSecretFile(sshPrivateKeyFile)) {
            sshPrivateKeyFile = null;
        }
        if (deleteSecretFile(sshKnownHostsFile)) {
            sshKnownHostsFile = null;
        }
    }

    /** Git {@code -c} arguments used for every remote command in this workspace. */
    String[] gitConfigArgs() {
        List<String> args = new ArrayList<>();
        if (credentialsFile != null) {
            args.add("-c");
            args.add("credential.helper=");
            args.add("-c");
            args.add("credential.helper=store --file=" + credentialsFile.toAbsolutePath());
        }
        if (sshPrivateKeyFile != null && sshKnownHostsFile != null) {
            String sshCommand = "ssh -F /dev/null -i " + shellQuote(sshPrivateKeyFile)
                    + " -o UserKnownHostsFile=" + shellQuote(sshKnownHostsFile)
                    + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                    + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes";
            args.add("-c");
            args.add("core.sshCommand=" + sshCommand);
        }
        return args.toArray(String[]::new);
    }

    /**
     * Environment that makes Git send the token as a pre-emptive
     * {@code Authorization: Basic} header, scoped to the workspace remote.
     * Passed via {@code GIT_CONFIG_*} rather than {@code -c} so the token never
     * appears in the process arguments, and never persisted to {@code .git/config}.
     */
    Map<String, String> authorizationHeaderEnvironment() {
        if (!usesAuthorizationHeader) {
            return Map.of();
        }
        RepositoryCredentials credentials = repositoryCredentials;
        String remote = repositoryRemote;
        if (credentials == null || credentials.usesSsh()
                || credentials.token() == null || credentials.token().isBlank()
                || remote == null || !remote.toLowerCase(Locale.ROOT).matches("https?://.*")) {
            return Map.of();
        }
        String username = credentials.hasUsername() ? credentials.username() : "";
        String basic = Base64.getEncoder().encodeToString(
                (username + ":" + credentials.token()).getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http." + remote + ".extraheader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
    }

    static String[] withGitConfig(String[] gitConfig, String... gitArgs) {
        String[] command = new String[1 + gitConfig.length + gitArgs.length];
        command[0] = "git";
        System.arraycopy(gitConfig, 0, command, 1, gitConfig.length);
        System.arraycopy(gitArgs, 0, command, 1 + gitConfig.length, gitArgs.length);
        return command;
    }

    /** Applies owner-only permissions where the host filesystem supports them. */
    static void restrictToOwner(Path path, boolean isDirectory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    isDirectory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS);
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

    private boolean hasAuthenticationFiles() {
        return credentialsFile != null || sshPrivateKeyFile != null || sshKnownHostsFile != null;
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

    private String shellQuote(Path value) {
        return "'" + value.toAbsolutePath().normalize().toString().replace("'", "'\"'\"'") + "'";
    }

    private boolean isLocalClonePath(String cloneUrl) {
        return cloneUrl.startsWith("/") || cloneUrl.startsWith("\\\\")
                || cloneUrl.length() >= 3 && Character.isLetter(cloneUrl.charAt(0))
                && cloneUrl.charAt(1) == ':'
                && (cloneUrl.charAt(2) == '\\' || cloneUrl.charAt(2) == '/');
    }
}
