package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.PullRequestHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {
    /** Clone budget for the local fixture repositories, which are tiny. */
    private static final int CLONE_TIMEOUT_SECONDS = 60;

    private WorkspaceService workspaceService;
    @Mock
    private RepositoryApiClient repositoryClient;
    @TempDir
    Path tempDir;

    @BeforeEach
    void createWorkspaceService() {
        workspaceService = new WorkspaceService(null, CLONE_TIMEOUT_SECONDS);
        // Mirrors the interface default so a fixture can publish refs/pull/<n>/head
        // without every test restating it; the GitLab override has its own test.
        lenient().when(repositoryClient.getPullRequestHeadRef(any()))
                .thenAnswer(invocation -> "refs/pull/" + invocation.getArgument(0) + "/head");
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
        assertThat(result.workspace().path()).isNotNull();

        // Verify the fallback created a real local branch, not detached HEAD
        assertThat(runGitCapture(result.workspace().path(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("nonexistent-branch");

        String content = Files.readString(result.workspace().path().resolve("README.md"));
        assertThat(content).isEqualTo("pr content");

        verify(client, times(1)).getRepositoryRemote("any", "any");
        verify(client, times(1)).getCredentials();

        result.workspace().close();
    }

    @Test
    void prepareWorkspace_fetchesTheProvidersPullRequestHeadRef() throws Exception {
        // GitLab publishes the head commit under refs/merge-requests/<n>/head, so the
        // fallback must ask the client for the ref instead of hardcoding refs/pull/.
        Path remoteDir = tempDir.resolve("remote-gitlab");
        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        Path localRepo = tempDir.resolve("local-gitlab");
        Files.createDirectories(localRepo);
        runGit(localRepo, "init");
        runGit(localRepo, "config", "user.email", "test@test.com");
        runGit(localRepo, "config", "user.name", "Test");
        runGit(localRepo, "branch", "-M", "main");
        runGit(localRepo, "remote", "add", "origin", remoteDir.toAbsolutePath().toString());
        Files.writeString(localRepo.resolve("README.md"), "merge request content");
        runGit(localRepo, "add", "README.md");
        runGit(localRepo, "commit", "-m", "mr commit");
        runGit(localRepo, "push", "-u", "origin", "main");
        runGit(localRepo, "push", "origin", "main:refs/merge-requests/77/head");

        RepositoryApiClient client = repositoryClient(remoteDir.toAbsolutePath().toString());
        when(client.getPullRequestHeadRef(77L)).thenReturn("refs/merge-requests/77/head");

        WorkspaceResult result = workspaceService.prepareWorkspace(
                client, "any", "any", "nonexistent-branch", 77L);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(result.workspace().path().resolve("README.md")))
                .isEqualTo("merge request content");

        result.workspace().close();
    }

    @Test
    void prepareWorkspace_fallbackRetainsExactlyOneWorkspaceDirectory() throws Exception {
        // Regression for the agentic review BLOCKER: when the branch clone fails
        // and the PR-ref fallback kicks in, the first workspace attempt must be
        // fully removed before the second is created — otherwise a partial
        // deletion could orphan the first attempt's credential-store file.
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString(), CLONE_TIMEOUT_SECONDS);

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

        result.workspace().close();

        try (var children = Files.list(workspaceBaseDir)) {
            assertThat(children
                    .filter(path -> path.getFileName().toString().startsWith("agent-workspace-"))
                    .count())
                    .isZero();
        }
    }

    @Test
    void preparePullRequestWorkspace_clonesForkHeadBranchFromSourceRepository() throws Exception {
        // The reported failure: a fork PR whose head branch exists only in the
        // contributor's fork, under a name that is not a plain shell token.
        String headBranch = "`libc.obj`--always-recreate-file-if-it-opened-not-for-append,"
                + "-else-only-if-file-not-exist";
        createBareRepository("target", "target content");
        Path forkRemote = createBareRepository("fork", "fork main content");
        Path forkSource = tempDir.resolve("fork-source");
        runGit(forkSource, "checkout", "-b", headBranch);
        Files.writeString(forkSource.resolve("README.md"), "fork pr content");
        runGit(forkSource, "add", "README.md");
        runGit(forkSource, "commit", "-m", "fork pr commit");
        runGit(forkSource, "push", "origin", headBranch);

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 769L, headBranch))
                .thenReturn(new PullRequestHead("contributor", "project", headBranch,
                        runGitCapture(forkRemote, "rev-parse", "refs/heads/" + headBranch)));
        when(repositoryClient.getRepositoryRemote("contributor", "project"))
                .thenReturn(forkRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", forkRemote.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", headBranch, 769L,
                PullRequestWorkspaceMode.READ_ONLY);

        assertThat(result.success()).isTrue();
        assertThat(runGitCapture(result.workspace().path(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(headBranch);
        assertThat(Files.readString(result.workspace().path().resolve("README.md")))
                .isEqualTo("fork pr content");
        // The target repository must not be touched for a fork PR: that clone can
        // only fail because the contributor's branch does not live there.
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
        result.workspace().close();
    }

    @Test
    void preparePullRequestWorkspace_fallsBackToTargetPrRefWhenForkHeadIsGone() throws Exception {
        // The contributor deleted the branch but the PR is still open: the target
        // repository's refs/pull/<n>/head still carries the reviewed commit.
        Path targetRemote = createBareRepository("target", "pr head content");
        runGit(targetRemote, "update-ref", "refs/pull/9/head", "refs/heads/main");
        Path forkRemote = createBareRepository("fork", "fork main content");

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 9L, "deleted-branch"))
                .thenReturn(new PullRequestHead("contributor", "project", "deleted-branch", null));
        when(repositoryClient.getRepositoryRemote("contributor", "project"))
                .thenReturn(forkRemote.toString());
        when(repositoryClient.getRepositoryRemote("base", "project"))
                .thenReturn(targetRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", targetRemote.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "deleted-branch", 9L,
                PullRequestWorkspaceMode.READ_ONLY);

        assertThat(result.success()).isTrue();
        assertThat(runGitCapture(result.workspace().path(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("deleted-branch");
        assertThat(Files.readString(result.workspace().path().resolve("README.md")))
                .isEqualTo("pr head content");
        result.workspace().close();
    }

    @Test
    void preparePullRequestWorkspace_fallsBackToTargetPrRefWhenHeadLookupFails() throws Exception {
        Path targetRemote = createBareRepository("target", "pr head content");
        runGit(targetRemote, "update-ref", "refs/pull/11/head", "refs/heads/main");

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 11L, "stale-branch"))
                .thenThrow(new IllegalStateException(
                        "Gitea pull request head branch 'renamed' does not match expected branch "
                                + "'stale-branch'"));
        when(repositoryClient.getRepositoryRemote("base", "project"))
                .thenReturn(targetRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", targetRemote.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "stale-branch", 11L,
                PullRequestWorkspaceMode.READ_ONLY);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(result.workspace().path().resolve("README.md")))
                .isEqualTo("pr head content");
        result.workspace().close();
    }

    @Test
    void preparePullRequestWorkspace_nonAuthoritativeProviderKeepsBranchClone() throws Exception {
        Path remoteDir = createBareRepository("target", "branch content");
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(false);
        when(repositoryClient.getRepositoryRemote("base", "project")).thenReturn(remoteDir.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remoteDir.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "main", 9L,
                PullRequestWorkspaceMode.READ_ONLY);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(result.workspace().path().resolve("README.md")))
                .isEqualTo("branch content");
        // Providers without authoritative head resolution must not gain a new API call.
        verify(repositoryClient, never()).getPullRequestHead(any(), any(), any(), any());
        result.workspace().close();
    }

    @Test
    void preparePullRequestWorkspace_pushBackPushesToTheForkBranch() throws Exception {
        Path targetRemote = createBareRepository("target", "target content");
        Path forkRemote = createBareRepository("fork", "fork content");
        String targetBefore = runGitCapture(targetRemote, "rev-parse", "refs/heads/main");
        String forkBefore = runGitCapture(forkRemote, "rev-parse", "refs/heads/main");

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 7L, "main"))
                .thenReturn(new PullRequestHead("contributor", "project", "main", forkBefore));
        when(repositoryClient.getRepositoryRemote("contributor", "project"))
                .thenReturn(forkRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", forkRemote.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "main", 7L,
                PullRequestWorkspaceMode.PUSH_BACK);

        assertThat(result.success()).isTrue();
        Files.writeString(result.workspace().path().resolve("README.md"), "bot update");
        assertThat(result.workspace().commitAndPush("main", "docs: update", false)).isTrue();

        assertThat(runGitCapture(targetRemote, "rev-parse", "refs/heads/main")).isEqualTo(targetBefore);
        assertThat(runGitCapture(forkRemote, "rev-parse", "refs/heads/main")).isNotEqualTo(forkBefore);
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
        result.workspace().close();
    }

    @Test
    void preparePullRequestWorkspace_pushBackFailsClosedWhenHeadResolutionFails() {
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 7L, "main"))
                .thenThrow(new IllegalStateException("missing head repository"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "main", 7L,
                PullRequestWorkspaceMode.PUSH_BACK);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("missing head repository");
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
        verify(repositoryClient, never()).getCredentials();
    }

    @Test
    void preparePullRequestWorkspace_pushBackFailsClosedWhenForkBranchIsGone() throws Exception {
        // A PUSH_BACK workspace must be a checkout of the head branch: the target
        // repository's PR ref is a valid read-only substitute, but pushing from it
        // would land in the wrong repository.
        Path targetRemote = createBareRepository("target", "pr head content");
        runGit(targetRemote, "update-ref", "refs/pull/9/head", "refs/heads/main");
        Path forkRemote = createBareRepository("fork", "fork main content");

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 9L, "deleted-branch"))
                .thenReturn(new PullRequestHead("contributor", "project", "deleted-branch", null));
        when(repositoryClient.getRepositoryRemote("contributor", "project"))
                .thenReturn(forkRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", forkRemote.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "deleted-branch", 9L,
                PullRequestWorkspaceMode.PUSH_BACK);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("contributor/project").contains("deleted-branch");
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
    }

    @Test
    void preparePullRequestWorkspace_pushBackNonAuthoritativeProviderKeepsPrRefFallback() throws Exception {
        Path remoteDir = createBareRepository("provider-target", "pr content");
        runGit(remoteDir, "update-ref", "refs/pull/42/head", "refs/heads/main");
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(false);
        when(repositoryClient.getRepositoryRemote("base", "project")).thenReturn(remoteDir.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remoteDir.toString(), "dummy-token"));

        WorkspaceResult result = workspaceService.preparePullRequestWorkspace(
                repositoryClient, "base", "project", "missing-branch", 42L,
                PullRequestWorkspaceMode.PUSH_BACK);

        assertThat(result.success()).isTrue();
        assertThat(runGitCapture(result.workspace().path(), "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("missing-branch");
        result.workspace().close();
    }

    @Test
    void failedCleanupIsRetriedBeforeCreatingAnotherWorkspace() throws IOException {
        FailOnceCleanupWorkspaceService service = new FailOnceCleanupWorkspaceService();
        Workspace failed = service.createWorkspace();

        assertThat(failed.cleanup()).isFalse();
        assertThat(failed.root()).exists();

        Workspace next = service.createWorkspace();
        assertThat(failed.root()).doesNotExist();
        next.close();
    }

    @Test
    void prepareWorkspace_returnsFailureWhenProviderResolutionFailsWithoutAllocatingWorkspace() {
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString(), CLONE_TIMEOUT_SECONDS);
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
    void createWorkspace_usesConfiguredBaseDirectory() throws IOException {
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString(), CLONE_TIMEOUT_SECONDS);

        Workspace workspace = workspaceService.createWorkspace();

        assertThat(workspace.path().startsWith(workspaceBaseDir.toAbsolutePath().normalize())).isTrue();
        assertThat(workspace.root().getParent()).isEqualTo(workspaceBaseDir.toAbsolutePath().normalize());

        workspace.close();

        assertThat(workspace.root()).doesNotExist();
        assertThat(workspaceBaseDir).exists();
    }

    @Test
    void prepareWorkspace_sendsBasicHeaderWhenProviderOptsIn() throws IOException {
        java.util.List<String> authorizationHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/org/project/_git/repo";
            when(repositoryClient.getRepositoryRemote("any", "any")).thenReturn(remote);
            when(repositoryClient.getCredentials()).thenReturn(RepositoryCredentials.of("", remote, "pat"));
            when(repositoryClient.usesGitAuthorizationHeader()).thenReturn(true);

            WorkspaceResult result = workspaceService.prepareWorkspace(
                    repositoryClient, "any", "any", "main", null);

            assertThat(result.success()).isFalse();
            assertThat(authorizationHeaders).isNotEmpty().allMatch(header -> header.equals(
                    "Basic " + java.util.Base64.getEncoder().encodeToString(":pat".getBytes())));
        } finally {
            server.stop(0);
        }
    }

    private Path createBareRepository(String name, String content) throws IOException, InterruptedException {
        Path bare = tempDir.resolve(name + "-remote");
        Files.createDirectories(bare);
        runGit(bare, "init", "--bare");
        Path source = tempDir.resolve(name + "-source");
        Files.createDirectories(source);
        runGit(source, "init");
        runGit(source, "config", "user.email", "test@example.com");
        runGit(source, "config", "user.name", "Test User");
        runGit(source, "branch", "-M", "main");
        Files.writeString(source.resolve("README.md"), content);
        runGit(source, "add", "README.md");
        runGit(source, "commit", "-m", "initial");
        runGit(source, "remote", "add", "origin", bare.toString());
        runGit(source, "push", "origin", "main");
        return bare;
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

        FailOnceCleanupWorkspaceService() {
            super(null, CLONE_TIMEOUT_SECONDS);
        }

        private boolean fail = true;

        @Override
        Workspace newWorkspace(Path workspaceRoot, Consumer<Workspace> onCleanedUp) {
            return new Workspace(workspaceRoot, onCleanedUp) {
                @Override
                void deleteDirectory(Path dir) throws IOException {
                    if (fail) {
                        fail = false;
                        throw new IOException("simulated cleanup failure");
                    }
                    super.deleteDirectory(dir);
                }
            };
        }
    }
}
