package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    private WorkspaceService workspaceService;
    @TempDir
    Path tempDir;

    @BeforeEach
    void createWorkspaceService() {
        workspaceService = new WorkspaceService(null, 60);
    }

    @Test
    void close_deletesCredentialFileAndRootTogether() throws IOException {
        // The workspace keeps the credential-file reference even when no remote command
        // ran — exactly the situation of the first attempt in the branch-clone fallback.
        // Cleanup must remove the file and the private parent together.
        Workspace workspace = workspaceService.createWorkspace();
        Path credentials = workspace.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token");
        assertThat(credentials).isNotNull();

        workspace.close();

        assertThat(credentials).doesNotExist();
        assertThat(workspace.root()).doesNotExist();
    }

    @Test
    void close_leavesAnUnmanagedDirectoryInPlace() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("workspace"));

        Workspace.unmanaged(directory).close();

        assertThat(directory).exists();
    }

    @Test
    void fetchBranch_rejectsWorkspaceWithoutAuthenticationState() {
        CommandResult result = Workspace.unmanaged(tempDir).fetchBranch("main");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("Workspace authentication is unavailable");
    }

    @Test
    void gitRemote_rejectsAClosedWorkspace() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        workspace.setAuthentication("/tmp/remote", RepositoryCredentials.of("", "/tmp/remote", ""), false);
        workspace.close();

        CommandResult result = workspace.gitRemote(15, "fetch", "origin");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("Workspace was already cleaned up");
    }

    @Test
    void gitCommand_sshUsesIntegrationKeyAndPinnedHostKeys() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        RepositoryCredentials credentials = RepositoryCredentials
                .of("https://gitea.example.com", "git@gitea.example.com:owner/repo.git", "token")
                .withSsh("private key", "gitea.example.com ssh-ed25519 host-key");
        workspace.createAuthenticationFiles(credentials.cloneUrl(), credentials);

        Path privateKey = workspace.sshPrivateKeyFile();
        Path knownHosts = workspace.sshKnownHostsFile();
        assertThat(Files.readString(privateKey)).isEqualTo("private key\n");
        assertThat(Files.readString(knownHosts)).isEqualTo("gitea.example.com ssh-ed25519 host-key\n");
        assertThat(Workspace.withGitConfig(workspace.gitConfigArgs(), "clone", credentials.cloneUrl()))
                .containsExactly(
                        "git", "-c",
                        "core.sshCommand=ssh -F /dev/null -i '" + privateKey.toAbsolutePath() + "'"
                                + " -o UserKnownHostsFile='" + knownHosts.toAbsolutePath() + "'"
                                + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                                + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes",
                        "clone", credentials.cloneUrl());

        workspace.clearAuthenticationFiles();
        assertThat(workspace.sshPrivateKeyFile()).isNull();
        assertThat(workspace.sshKnownHostsFile()).isNull();
        assertThat(privateKey).doesNotExist();
        assertThat(knownHosts).doesNotExist();
        workspace.close();
    }

    @Test
    void createCredentialsFile_keepsTokenOutsideWorkspaceAndCleanupRemovesIt() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        Path directory = workspace.path();

        Path credentials = workspace.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token");

        assertThat(credentials.getParent()).isEqualTo(directory.getParent());
        assertThat(credentials.getFileName().toString()).startsWith("credentials-");
        assertThat(credentials).isNotEqualTo(directory.resolve(".git-credentials"));
        assertThat(credentials).isNotEqualTo(directory.resolveSibling("repository.credentials"));
        assertThat(Files.readString(credentials)).isEqualTo("https://oauth2:test-token@git.example.com\n");

        workspace.close();

        assertThat(credentials).doesNotExist();
        assertThat(directory.getParent()).doesNotExist();
    }

    @Test
    void authenticationFile_preservesConfiguredUsernameWithUppercaseScheme() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        RepositoryCredentials credentials = RepositoryCredentials.of(
                "https://api.bitbucket.org", "HTTPS://bitbucket.org", "alice", "api-token");

        workspace.createAuthenticationFiles("HTTPS://bitbucket.org/owner/repo.git", credentials);

        assertThat(Files.readString(workspace.credentialsFile()))
                .isEqualTo("https://alice:api-token@bitbucket.org\n");
        workspace.close();
    }

    @Test
    void authorizationHeader_sendsTokenAsBasicHeaderWithoutCredentialStore() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        String remote = "https://dev.azure.com/org/project/_git/repo";
        RepositoryCredentials credentials =
                RepositoryCredentials.of("https://dev.azure.com", "https://dev.azure.com", "pat");
        workspace.setAuthentication(remote, credentials, true);

        try {
            workspace.createAuthenticationFiles(remote, credentials);

            assertThat(workspace.credentialsFile()).isNull();
            assertThat(workspace.gitConfigArgs()).isEmpty();
            assertThat(workspace.authorizationHeaderEnvironment()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(
                            "GIT_CONFIG_COUNT", "1",
                            "GIT_CONFIG_KEY_0", "http." + remote + ".extraheader",
                            "GIT_CONFIG_VALUE_0", "Authorization: Basic "
                                    + Base64.getEncoder().encodeToString(":pat".getBytes())));
        } finally {
            workspace.close();
        }
    }

    @Test
    void authorizationHeader_notUsedByDefault() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        workspace.setAuthentication("https://git.example.com/owner/repo.git",
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "token"), false);

        try {
            assertThat(workspace.authorizationHeaderEnvironment()).isEmpty();
        } finally {
            workspace.close();
        }
    }

    @Test
    void gitConfigArgs_usesExternalCredentialStore() throws IOException {
        Workspace workspace = workspaceService.createWorkspace();
        Path credentials = workspace.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token");

        try {
            assertThat(workspace.gitConfigArgs()).containsExactly(
                    "-c", "credential.helper=",
                    "-c", "credential.helper=store --file=" + credentials.toAbsolutePath());
        } finally {
            workspace.close();
        }
    }

    @Test
    void diffStat_summarizesUncommittedChanges() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "changed");

        assertThat(Workspace.unmanaged(tempDir).diffStat())
                .contains("README.md")
                .contains("1 file changed");
    }

    @Test
    void diffStat_isEmptyWithoutChanges() throws IOException, InterruptedException {
        initGitRepository(tempDir);

        assertThat(Workspace.unmanaged(tempDir).diffStat()).isEmpty();
    }

    @Test
    void diffStat_isEmptyWhenGitFails() {
        assertThat(Workspace.unmanaged(tempDir).diffStat()).isEmpty();
    }

    @Test
    void stagedDiff_includesNewAndDeletedFiles() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Files.delete(tempDir.resolve("README.md"));
        Files.writeString(tempDir.resolve("NEW.md"), "new content");

        assertThat(Workspace.unmanaged(tempDir).stagedDiff())
                .contains("diff --git a/NEW.md b/NEW.md")
                .contains("+new content")
                .contains("diff --git a/README.md b/README.md")
                .contains("-initial");
    }

    @Test
    void stagedDiff_failsWhenGitFails() {
        assertThatThrownBy(() -> Workspace.unmanaged(tempDir).stagedDiff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot stage documentation diff");
    }

    @Test
    void hasUncommittedChanges_detectsModifiedTrackedFile() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "changed");

        assertThat(Workspace.unmanaged(tempDir).hasUncommittedChanges()).isTrue();
    }

    @Test
    void hasUncommittedChanges_ignoresEmptyDirectory() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Files.createDirectories(tempDir.resolve("empty-dir"));

        assertThat(Workspace.unmanaged(tempDir).hasUncommittedChanges()).isFalse();
    }

    @Test
    void commitAndPush_disablesWorkspaceHooks() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Workspace workspace = workspaceWithRemote();
        String branch = runGitCapture(workspace.path(), "branch", "--show-current");

        Path hook = workspace.path().resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(hook, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(workspace.path().resolve("README.md"), "changed");

        try {
            assertThat(workspace.commitAndPush(branch, "test commit", false)).isTrue();
        } finally {
            workspace.close();
        }
    }

    @Test
    void commitAndPush_usesBotGitIdentity() throws Exception {
        Workspace workspace = workspaceWithRemote();
        Path directory = workspace.path();
        String branch = runGitCapture(directory, "branch", "--show-current");
        // Repository config the workspace's code could have written must not change the identity
        runGit(directory, "config", "author.name", "Planted Author");
        runGit(directory, "config", "committer.email", "planted@example.com");
        Files.writeString(directory.resolve("README.md"), "changed");

        try {
            assertThat(workspace.commitAndPush(branch, "docs: update", false)).isTrue();
            assertThat(runGitCapture(tempDir.resolve("remote"), "log", "-1", "--format=%an <%ae>|%cn <%ce>", branch))
                    .isEqualTo("AI Agent <ai-agent@bot.local>|AI Agent <ai-agent@bot.local>");
            // The identity is not persisted into the workspace's repository config
            assertThat(runGitCapture(directory, "config", "user.name")).isEqualTo("Test User");
        } finally {
            workspace.close();
        }
    }

    @Test
    void commitAndPush_withoutWorkspaceStateDoesNotCreateCommit() throws Exception {
        initGitRepository(tempDir);
        String previousHead = runGitCapture(tempDir, "rev-parse", "HEAD");
        Files.writeString(tempDir.resolve("README.md"), "changed");

        assertThat(Workspace.unmanaged(tempDir).commitAndPush("main", "test commit", false)).isFalse();
        assertThat(runGitCapture(tempDir, "rev-parse", "HEAD")).isEqualTo(previousHead);
    }

    @Test
    void gitCommands_disableWorkspaceFsMonitor() throws Exception {
        initGitRepository(tempDir);
        Path monitorDirectory = tempDir.getParent().resolve(tempDir.getFileName() + "-fsmonitor");
        Files.createDirectories(monitorDirectory);
        Path marker = monitorDirectory.resolve("ran");
        Path monitor = monitorDirectory.resolve("monitor.sh");
        Files.writeString(monitor, "#!/bin/sh\ntouch " + marker + "\n");
        Assumptions.assumeTrue(Files.getFileStore(monitor).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(monitor, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        runGit(tempDir, "config", "core.fsmonitor", monitor.toString());

        runGit(tempDir, "status", "--porcelain");
        assertThat(marker).exists();
        Files.delete(marker);

        assertThat(Workspace.unmanaged(tempDir).hasUncommittedChanges()).isFalse();
        assertThat(marker).doesNotExist();
    }

    /** A managed workspace with an initial commit, already pushed to a bare {@code remote} in the temp dir. */
    private Workspace workspaceWithRemote() throws IOException, InterruptedException {
        Workspace workspace = workspaceService.createWorkspace();
        Path directory = workspace.path();
        Files.createDirectories(directory);
        initGitRepository(directory);
        Path remote = tempDir.resolve("remote");
        Files.createDirectories(remote);
        runGit(remote, "init", "--bare");
        String branch = runGitCapture(directory, "branch", "--show-current");
        runGit(directory, "remote", "add", "origin", remote.toAbsolutePath().toString());
        runGit(directory, "push", "-u", "origin", branch);
        workspace.setAuthentication(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), ""), false);
        return workspace;
    }

    private void initGitRepository(Path dir) throws IOException, InterruptedException {
        runGit(dir, "init");
        runGit(dir, "config", "user.email", "test@example.com");
        runGit(dir, "config", "user.name", "Test User");
        Files.writeString(dir.resolve("README.md"), "initial");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-m", "initial");
    }

    private String runGitCapture(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return output.trim();
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        assertThat(exitCode).isZero();
    }
}
