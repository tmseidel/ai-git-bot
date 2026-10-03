package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Git repositories exercise publication, comment previews and workspace cleanup. */
class ReadmeSyncWritebackTest {
    @TempDir Path root;
    private Path remote;
    private String originalHead;
    private RepositoryApiClient client;
    private ReadmeSyncService service;
    private final AtomicReference<Path> checkout = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private String generatedText = "updated\n";

    @BeforeEach
    void setUp() throws Exception {
        remote = Files.createDirectory(root.resolve("remote.git"));
        Path seed = Files.createDirectory(root.resolve("seed"));
        git(remote, "init", "--bare");
        git(seed, "init", "-b", "feature/docs");
        git(seed, "config", "user.name", "Test");
        git(seed, "config", "user.email", "test@example.com");
        Files.writeString(seed.resolve("README.md"), "original\n");
        Files.writeString(seed.resolve("obsolete.md"), "obsolete\n");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "original");
        originalHead = git(seed, "rev-parse", "HEAD");
        git(seed, "remote", "add", "origin", remote.toString());
        git(seed, "push", "origin", "feature/docs");

        client = mock(RepositoryApiClient.class, CALLS_REAL_METHODS);
        doReturn(remote.toString()).when(client).getRepositoryRemote("acme", "repo");
        when(client.getCredentials()).thenReturn(RepositoryCredentials.of("", remote.toString(), ""));
        when(client.getPullRequestDiff("acme", "repo", 42L)).thenReturn("diff --git a/x b/x\n+changed");
        when(client.getPullRequestDetails("acme", "repo", 42L))
                .thenReturn(Optional.of(details(PullRequestState.OPEN)));
        doReturn(43L).when(client).createPullRequest(eq("acme"), eq("repo"), anyString(), anyString(), anyString(),
                eq("feature/docs"));
        ReadmeSyncAgent agent = mock(ReadmeSyncAgent.class);
        when(agent.write(any(), any(), anyString(), any(), anyInt())).thenAnswer(invocation -> {
            ReadmeSyncToolContext context = invocation.getArgument(1);
            checkout.set(context.workspace());
            Files.writeString(context.workspace().resolve("README.md"), generatedText);
            Files.writeString(context.workspace().resolve("new.md"), "new documentation\n");
            Files.delete(context.workspace().resolve("obsolete.md"));
            context.recordUpdated("README.md");
            context.recordCreated("new.md");
            context.recordDeleted("obsolete.md");
            return new ReadmeSyncAgent.Result(3, "done", false);
        });
        service = new ReadmeSyncService(client, mock(AiClient.class), new SystemPrompt(),
                new WorkspaceService(root.resolve("workspaces").toString()), agent);
    }

    @ParameterizedTest
    @EnumSource(value = SuiteLifecycleMode.class, names = {"COMMIT_TO_PR", "OFFER_AS_PR"})
    void closedAtPush_doesNotRecreateBranchAndCommentsWithAddedEditedDeletedFiles(SuiteLifecycleMode mode)
            throws Exception {
        when(client.getPullRequestDetails("acme", "repo", 42L)).thenAnswer(invocation -> {
            assertThat(git(checkout.get(), "log", "-1", "--format=%s")).startsWith("docs: sync");
            git(remote, "update-ref", "-d", "refs/heads/feature/docs");
            return Optional.of(details(PullRequestState.MERGED));
        });

        ReadmeSyncService.Result result = service.run(request(mode));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        assertThat(git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/")).isEmpty();
        assertThat(preview()).contains("```diff", "-original", "+updated", "+new documentation", "-obsolete");
        assertThat(checkout.get()).doesNotExist();
        verify(client, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = SuiteLifecycleMode.class, names = {"COMMIT_TO_PR", "OFFER_AS_PR"})
    void openPullRequest_keepsExistingLifecycle(SuiteLifecycleMode mode) throws Exception {
        ReadmeSyncService.Result result = service.run(request(mode));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.SUCCESS);
        String sourceHead = git(remote, "rev-parse", "refs/heads/feature/docs");
        if (mode == SuiteLifecycleMode.COMMIT_TO_PR) {
            assertThat(sourceHead).isNotEqualTo(originalHead);
            assertThat(git(remote, "show", "feature/docs:README.md")).isEqualTo("updated");
        } else {
            assertThat(sourceHead).isEqualTo(originalHead);
            verify(client).createPullRequest(eq("acme"), eq("repo"), anyString(), anyString(),
                    startsWith("ai-docs/pr-42-"), eq("feature/docs"));
        }
        verify(client, never()).postPullRequestComment(anyString(), anyString(), anyLong(), contains("```diff"));
        assertThat(checkout.get()).doesNotExist();
    }

    @Test
    void largeDiff_hasExplicitTruncationNoticeAndFitsOneComment() throws Exception {
        generatedText = "完整文档\n".repeat(100_000);
        when(client.getPullRequestDetails("acme", "repo", 42L)).thenReturn(Optional.empty());

        ReadmeSyncService.Result result = service.run(request(SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        assertThat(preview()).contains("Diff truncated", "not a complete patch", "+完整文档").doesNotContain("\uFFFD");
        assertThat(preview().getBytes(StandardCharsets.UTF_8).length).isLessThan(65_536);
        assertThat(git(remote, "rev-parse", "refs/heads/feature/docs")).isEqualTo(originalHead);
    }

    @Test
    void stateLookupAndCommentFailure_stillStopsPushAndCleansWorkspace() throws Exception {
        when(client.getPullRequestDetails("acme", "repo", 42L)).thenThrow(new IllegalStateException("API unavailable"));
        doThrow(new IllegalStateException("comments unavailable")).when(client)
                .postPullRequestComment(anyString(), anyString(), anyLong(), anyString());

        ReadmeSyncService.Result result = service.run(request(SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        assertThat(result.summary()).contains("API unavailable");
        assertThat(preview()).contains("API unavailable", "+updated");
        assertThat(git(remote, "rev-parse", "refs/heads/feature/docs")).isEqualTo(originalHead);
        assertThat(checkout.get()).doesNotExist();
    }

    @Test
    void offerClosedAfterPush_doesNotCreateFollowUpAndCommentsWithDiff() throws Exception {
        when(client.getPullRequestDetails("acme", "repo", 42L))
                .thenReturn(Optional.of(details(PullRequestState.OPEN)), Optional.of(details(PullRequestState.MERGED)));

        ReadmeSyncService.Result result = service.run(request(SuiteLifecycleMode.OFFER_AS_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        assertThat(preview()).contains("+updated").doesNotContain("no changes were pushed");
        assertThat(git(remote, "rev-parse", "refs/heads/feature/docs")).isEqualTo(originalHead);
        verify(client, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cancelledDuringStateLookup_stopsPush() throws Exception {
        when(client.getPullRequestDetails("acme", "repo", 42L)).thenAnswer(invocation -> {
            cancelled.set(true);
            return Optional.of(details(PullRequestState.OPEN));
        });

        assertThatThrownBy(() -> service.run(request(SuiteLifecycleMode.COMMIT_TO_PR)))
                .isInstanceOf(WorkflowCancelledException.class);

        assertThat(git(remote, "rev-parse", "refs/heads/feature/docs")).isEqualTo(originalHead);
        assertThat(checkout.get()).doesNotExist();
    }

    @Test
    void reportOnly_needsNoStateQuery() throws Exception {
        ReadmeSyncService.Result result = service.run(request(SuiteLifecycleMode.EPHEMERAL));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.SUCCESS);
        verify(client, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
        assertThat(git(remote, "rev-parse", "refs/heads/feature/docs")).isEqualTo(originalHead);
    }

    private String preview() {
        var comments = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).postPullRequestComment(eq("acme"), eq("repo"), eq(42L), comments.capture());
        return comments.getAllValues().stream().filter(c -> c.contains("```diff")).findFirst().orElseThrow();
    }

    private ReadmeSyncService.Request request(SuiteLifecycleMode mode) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repository.setOwner(owner);
        repository.setName("repo");
        payload.setRepository(repository);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(42L);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/docs");
        pr.setHead(head);
        payload.setPullRequest(pr);
        return new ReadmeSyncService.Request(new PrWorkflowContext(new Bot(), payload, 7L,
                (label, message) -> { }, cancelled::get), List.of("*.md"), 12, mode, null);
    }

    private static PullRequestDetails details(PullRequestState state) {
        return new PullRequestDetails(null, null, state, null, null, null, null);
    }

    private String git(Path directory, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(root, "git-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Git timed out: " + command);
        }
        String text = Files.readString(output);
        assertThat(process.exitValue()).as("%s: %s", command, text).isZero();
        return text.strip();
    }
}
