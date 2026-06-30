package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
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
        workspaceService.cleanupWorkspace(null);
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
    void buildCloneUrl_http() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "http://git.example.com", "mytoken");
        assertThat(url).isEqualTo("http://oauth2:mytoken@git.example.com/owner/repo.git");
    }
    @Test
    void buildCloneUrl_https_trailingSlash() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "https://git.example.com/", "tok");
        assertThat(url).isEqualTo("https://oauth2:tok@git.example.com/owner/repo.git");
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
