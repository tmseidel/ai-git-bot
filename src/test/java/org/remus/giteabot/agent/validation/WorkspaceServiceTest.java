package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {
    @InjectMocks
    private WorkspaceService workspaceService;
    @Mock
    private RepositoryApiClient repositoryClient;
    @TempDir
    Path tempDir;
    @Test
    void cleanupWorkspace_deletesDirectory() throws IOException {
        Path wsDir = tempDir.resolve("workspace");
        Files.createDirectories(wsDir.resolve("sub"));
        Files.writeString(wsDir.resolve("sub/file.txt"), "content");
        workspaceService.cleanupWorkspace(wsDir);
        assertThat(wsDir).doesNotExist();
    }
    @Test
    void cleanupWorkspace_nullPath_doesNotThrow() {
        workspaceService.cleanupWorkspace((Path) null);
        // no exception expected
    }

    @Test
    void prepareWorkspace_fallsBackToPrHeadRef_whenBranchCloneFails() throws Exception {
        // Create a local bare repo with a main branch and a refs/pull/42/head ref
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        Path localRepo = tempDir.resolve("local");
        Files.createDirectories(localRepo);
        runGit(localRepo, "init");
        runGit(localRepo, "config", "user.email", "test@test.com");
        runGit(localRepo, "config", "user.name", "Test");
        runGit(localRepo, "branch", "-M", "main");
        runGit(localRepo, "remote", "add", "origin", remoteDir.toAbsolutePath().toString());
        Files.writeString(localRepo.resolve("README.md"), "pr content");
        runGit(localRepo, "add", "README.md");
        runGit(localRepo, "commit", "-m", "pr commit");
        runGit(localRepo, "push", "-u", "origin", "main");
        // Push the same commit as a simulated PR head ref
        runGit(localRepo, "push", "origin", "main:refs/pull/42/head");

        // Now clone with a branch that does NOT exist in the remote, but prNumber=42
        // The --branch clone will fail, triggering the PR ref fallback
        RepositoryApiClient client = repositoryClient(remoteDir.toAbsolutePath().toString());
        WorkspaceResult result = workspaceService.prepareWorkspace(
                client, "any", "any", "nonexistent-branch", 42L);

        assertThat(result.success()).isTrue();
        assertThat(result.workspacePath()).isNotNull();

        // Verify the fallback created a real local branch, not detached HEAD
        assertThat(runGitCapture(result.workspacePath(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("nonexistent-branch");

        String content = Files.readString(result.workspacePath().resolve("README.md"));
        assertThat(content).isEqualTo("pr content");

        verify(client, times(1)).getRepositoryRemote("any", "any");
        verify(client, times(1)).getCredentials();

        workspaceService.cleanupWorkspace(result.workspacePath());
    }

    @Test
    void prepareWorkspace_fallbackRetainsExactlyOneWorkspaceDirectory() throws Exception {
        // Regression for the agentic review BLOCKER: when the branch clone fails
        // and the PR-ref fallback kicks in, the first workspace attempt must be
        // fully removed before the second is created — otherwise a partial
        // deletion could orphan the first attempt's credential-store file.
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString());

        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        Path localRepo = tempDir.resolve("local");
        Files.createDirectories(localRepo);
        runGit(localRepo, "init");
        runGit(localRepo, "config", "user.email", "test@test.com");
        runGit(localRepo, "config", "user.name", "Test");
        runGit(localRepo, "branch", "-M", "main");
        runGit(localRepo, "remote", "add", "origin", remoteDir.toAbsolutePath().toString());
        Files.writeString(localRepo.resolve("README.md"), "pr content");
        runGit(localRepo, "add", "README.md");
        runGit(localRepo, "commit", "-m", "pr commit");
        runGit(localRepo, "push", "-u", "origin", "main");
        runGit(localRepo, "push", "origin", "main:refs/pull/42/head");

        WorkspaceResult result = workspaceService.prepareWorkspace(
                repositoryClient(remoteDir.toAbsolutePath().toString()),
                "any", "any", "nonexistent-branch", 42L);

        assertThat(result.success()).isTrue();

        try (var children = Files.list(workspaceBaseDir)) {
            assertThat(children
                    .filter(path -> path.getFileName().toString().startsWith("agent-workspace-"))
                    .count())
                    .isEqualTo(1);
        }

        workspaceService.cleanupWorkspace(result.workspacePath());

        try (var children = Files.list(workspaceBaseDir)) {
            assertThat(children
                    .filter(path -> path.getFileName().toString().startsWith("agent-workspace-"))
                    .count())
                    .isZero();
        }
    }

    @Test
    void cleanupWorkspace_setupDeletesCredentialFileAndRootTogether() throws IOException {
        // The holder keeps the credential-file reference even when the file was
        // never registered for the workspace — exactly the
        // situation of the first attempt in the branch-clone fallback. Cleanup
        // must remove the file and the private parent together.
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", setup.workspaceDir());
        assertThat(credentials).isNotNull();

        workspaceService.cleanupWorkspace(setup);

        assertThat(credentials).doesNotExist();
        assertThat(setup.workspaceRoot()).doesNotExist();
    }

    @Test
    void cleanupWorkspace_setupNull_doesNotThrow() {
        workspaceService.cleanupWorkspace((WorkspaceSetup) null);
        // no exception expected
    }

    @Test
    void failedCleanupIsRetriedBeforeCreatingAnotherWorkspace() throws IOException {
        FailOnceCleanupWorkspaceService service = new FailOnceCleanupWorkspaceService();
        WorkspaceSetup failed = service.createWorkspaceSetup();

        assertThat(service.cleanupWorkspace(failed)).isFalse();
        assertThat(failed.workspaceRoot()).exists();

        WorkspaceSetup next = service.createWorkspaceSetup();
        assertThat(failed.workspaceRoot()).doesNotExist();
        service.cleanupWorkspace(next);
    }

    @Test
    void fetchBranch_rejectsWorkspaceWithoutAuthenticationState() {
        CommandResult result = workspaceService.fetchBranch(tempDir, "main");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("Workspace authentication is unavailable");
    }
    @Test
    void prepareWorkspace_returnsFailureWhenProviderResolutionFailsWithoutAllocatingWorkspace() {
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString());
        when(repositoryClient.getRepositoryRemote("owner", "repo"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        WorkspaceResult result = workspaceService.prepareWorkspace(
                repositoryClient, "owner", "repo", "main", null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("provider unavailable");
        assertThat(workspaceBaseDir).doesNotExist();
        verify(repositoryClient).getRepositoryRemote("owner", "repo");
        verify(repositoryClient, never()).getCredentials();
    }

    @Test
    void gitCommand_sshUsesIntegrationKeyAndPinnedHostKeys() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        RepositoryCredentials credentials = RepositoryCredentials
                .of("https://gitea.example.com", "git@gitea.example.com:owner/repo.git", "token")
                .withSsh("private key", "gitea.example.com ssh-ed25519 host-key");
        workspaceService.createAuthenticationFiles(credentials.cloneUrl(), credentials, setup);

        Path privateKey = setup.sshPrivateKeyFile();
        Path knownHosts = setup.sshKnownHostsFile();
        assertThat(Files.readString(privateKey)).isEqualTo("private key\n");
        assertThat(Files.readString(knownHosts)).isEqualTo("gitea.example.com ssh-ed25519 host-key\n");
        assertThat(workspaceService.withGitConfig(
                workspaceService.gitConfigArgs(setup), "clone", credentials.cloneUrl()))
                .containsExactly(
                        "git", "-c",
                        "core.sshCommand=ssh -F /dev/null -i '" + privateKey.toAbsolutePath() + "'"
                                + " -o UserKnownHostsFile='" + knownHosts.toAbsolutePath() + "'"
                                + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                                + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes",
                         "clone", credentials.cloneUrl());

        workspaceService.clearAuthenticationFiles(setup);
        assertThat(setup.sshPrivateKeyFile()).isNull();
        assertThat(setup.sshKnownHostsFile()).isNull();
        assertThat(privateKey).doesNotExist();
        assertThat(knownHosts).doesNotExist();
        workspaceService.cleanupWorkspace(setup);
    }

    @Test
    void createCredentialsFile_keepsTokenOutsideWorkspaceAndCleanupRemovesIt() throws IOException {
        Path workspace = workspaceService.createWorkspaceDirectory();

        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", workspace);

        assertThat(credentials.getParent()).isEqualTo(workspace.getParent());
        assertThat(credentials.getFileName().toString()).startsWith("credentials-");
        assertThat(credentials).isNotEqualTo(workspace.resolve(".git-credentials"));
        assertThat(credentials).isNotEqualTo(workspace.resolveSibling("repository.credentials"));
        assertThat(Files.readString(credentials)).isEqualTo("https://oauth2:test-token@git.example.com\n");

        workspaceService.cleanupWorkspace(workspace);

        assertThat(credentials).doesNotExist();
        assertThat(workspace.getParent()).doesNotExist();
    }

    @Test
    void authenticationFile_preservesConfiguredUsernameWithUppercaseScheme() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        RepositoryCredentials credentials = RepositoryCredentials.of(
                "https://api.bitbucket.org", "HTTPS://bitbucket.org", "alice", "app-password");

        workspaceService.createAuthenticationFiles(
                "HTTPS://bitbucket.org/owner/repo.git", credentials, setup);

        assertThat(Files.readString(setup.credentialsFile()))
                .isEqualTo("https://alice:app-password@bitbucket.org\n");
        workspaceService.cleanupWorkspace(setup);
    }

    @Test
    void createWorkspaceDirectory_usesConfiguredBaseDirectory() throws IOException {
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString());

        Path workspace = workspaceService.createWorkspaceDirectory();

        assertThat(workspace.startsWith(workspaceBaseDir.toAbsolutePath().normalize())).isTrue();
        assertThat(workspace.getParent().getParent()).isEqualTo(workspaceBaseDir.toAbsolutePath().normalize());

        workspaceService.cleanupWorkspace(workspace);

        assertThat(workspace).doesNotExist();
        assertThat(workspaceBaseDir).exists();
    }

    @Test
    void createCredentialsFile_rejectsWorkspaceWithoutPrivateParent() throws IOException {
        Path unmanagedWorkspace = tempDir.resolve("workspace");
        Files.createDirectories(unmanagedWorkspace);

        assertThatThrownBy(() -> workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", unmanagedWorkspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private credential directory");
    }

    @Test
    void gitConfigArgs_usesExternalCredentialStore() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", workspace);
        setup.setCredentialsFile(credentials);

        try {
            assertThat(workspaceService.gitConfigArgs(setup)).containsExactly(
                    "-c", "credential.helper=",
                    "-c", "credential.helper=store --file=" + credentials.toAbsolutePath());
        } finally {
            workspaceService.cleanupWorkspace(workspace);
        }
    }

    @Test
    void hasUncommittedChanges_detectsModifiedTrackedFile() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "changed");

        assertThat(workspaceService.hasUncommittedChanges(tempDir)).isTrue();
    }

    @Test
    void hasUncommittedChanges_ignoresEmptyDirectory() throws IOException, InterruptedException {
        initGitRepository(tempDir);
        Files.createDirectories(tempDir.resolve("empty-dir"));

        assertThat(workspaceService.hasUncommittedChanges(tempDir)).isFalse();
    }

    @Test
    void commitAndPush_disablesWorkspaceHooks() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();
        Files.createDirectories(workspace);
        initGitRepository(workspace);
        Path remote = tempDir.resolve("remote");
        Files.createDirectories(remote);
        runGit(remote, "init", "--bare");
        String branch = runGitCapture(workspace, "branch", "--show-current");
        runGit(workspace, "remote", "add", "origin", remote.toAbsolutePath().toString());
        runGit(workspace, "push", "-u", "origin", branch);
        setup.setAuthentication(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), ""));
        workspaceService.registerWorkspace(setup);

        Path hook = workspace.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(hook, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(workspace.resolve("README.md"), "changed");

        try {
            assertThat(workspaceService.commitAndPush(workspace, branch, "test commit",
                    "Test User", "test@example.com", false)).isTrue();
        } finally {
            workspaceService.cleanupWorkspace(setup);
        }
    }

    @Test
    void commitAndPush_withoutWorkspaceStateDoesNotCreateCommit() throws Exception {
        initGitRepository(tempDir);
        String previousHead = runGitCapture(tempDir, "rev-parse", "HEAD");
        Files.writeString(tempDir.resolve("README.md"), "changed");

        assertThat(workspaceService.commitAndPush(tempDir, "main", "test commit",
                "Test User", "test@example.com", false)).isFalse();
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

        assertThat(workspaceService.hasUncommittedChanges(tempDir)).isFalse();
        assertThat(marker).doesNotExist();
    }

    private void initGitRepository(Path dir) throws IOException, InterruptedException {
        runGit(dir, "init");
        runGit(dir, "config", "user.email", "test@example.com");
        runGit(dir, "config", "user.name", "Test User");
        Files.writeString(dir.resolve("README.md"), "initial");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-m", "initial");
    }

    private RepositoryApiClient repositoryClient(String remote) {
        when(repositoryClient.getRepositoryRemote("any", "any")).thenReturn(remote);
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote, "dummy-token"));
        return repositoryClient;
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

    private static final class FailOnceCleanupWorkspaceService extends WorkspaceService {
        private boolean fail = true;

        @Override
        void deleteDirectory(Path dir) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("simulated cleanup failure");
            }
            super.deleteDirectory(dir);
        }
    }
}
