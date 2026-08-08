package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class WorkspaceServiceTest {
    private WorkspaceService workspaceService;
    @TempDir
    Path tempDir;
    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService();
    }
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
        WorkspaceResult result = workspaceService.prepareWorkspace(
                "any", "any", "nonexistent-branch",
                remoteDir.toAbsolutePath().toString(), "dummy-token", 42L);

        assertThat(result.success()).isTrue();
        assertThat(result.workspacePath()).isNotNull();

        // Verify the fallback created a real local branch, not detached HEAD
        assertThat(runGitCapture(result.workspacePath(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("nonexistent-branch");

        String content = Files.readString(result.workspacePath().resolve("README.md"));
        assertThat(content).isEqualTo("pr content");

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
                "any", "any", "nonexistent-branch",
                remoteDir.toAbsolutePath().toString(), "dummy-token", 42L);

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
        // never registered in the credentialsByWorkspace map — exactly the
        // situation of the first attempt in the branch-clone fallback. Cleanup
        // must remove the file and the private parent together.
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com", "test-token", setup.workspaceDir());
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
    void buildCloneUrl_http() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "http://git.example.com");
        assertThat(url).isEqualTo("http://git.example.com/owner/repo.git");
    }
    @Test
    void buildCloneUrl_https_trailingSlash() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "https://git.example.com/");
        assertThat(url).isEqualTo("https://git.example.com/owner/repo.git");
    }

    @Test
    void createCredentialsFile_keepsTokenOutsideWorkspaceAndCleanupRemovesIt() throws IOException {
        Path workspace = workspaceService.createWorkspaceDirectory();

        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com", "test-token", workspace);

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
                "https://git.example.com", "test-token", unmanagedWorkspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private credential directory");
    }

    @Test
    void credentialConfigForWorkspace_usesExternalCredentialStore() throws IOException {
        Path workspace = workspaceService.createWorkspaceDirectory();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com", "test-token", workspace);

        try {
            workspaceService.registerCredentialsFile(workspace, credentials);

            assertThat(workspaceService.credentialConfigForWorkspace(workspace)).containsExactly(
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
        initGitRepository(tempDir);
        Path remote = tempDir.resolve("remote");
        Files.createDirectories(remote);
        runGit(remote, "init", "--bare");
        String branch = runGitCapture(tempDir, "branch", "--show-current");
        runGit(tempDir, "remote", "add", "origin", remote.toAbsolutePath().toString());
        runGit(tempDir, "push", "-u", "origin", branch);

        Path hook = tempDir.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        Assumptions.assumeTrue(Files.getFileStore(hook).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(hook, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(tempDir.resolve("README.md"), "changed");

        assertThat(workspaceService.commitAndPush(tempDir, branch, "test commit",
                "Test User", "test@example.com", false)).isTrue();
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
