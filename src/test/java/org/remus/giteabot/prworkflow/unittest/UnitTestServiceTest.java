package org.remus.giteabot.prworkflow.unittest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.prworkflow.unittest.agents.UnitTestAuthorAgent;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestRunner;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestOutcome;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageResult;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitTestServiceTest {

    @Mock private RepositoryApiClient repositoryClient;
    @Mock private AiClient aiClient;
    @Mock private SystemPrompt systemPrompt;
    @Mock private WorkspaceService workspaceService;
    @Mock private FrameworkDetector frameworkDetector;
    @Mock private UnitTestAuthorAgent authorAgent;
    @Mock private UnitTestRunner runner;
    @Mock private UnitTestSuiteRepository suiteRepository;
    @InjectMocks private UnitTestService service;

    @Test
    void generate_passesPrNumberForForkSafeCheckoutFallback() {
        WebhookPayload payload = payload();
        PrWorkflowContext context = new PrWorkflowContext(
                new Bot(), payload, 1L, (name, log) -> { }, () -> false);
        UnitTestService.Request request = new UnitTestService.Request(
                context, null, 1, 1, SuiteLifecycleMode.EPHEMERAL);
        when(repositoryClient.getPullRequestDiff("acme", "repo", 42L)).thenReturn("diff");
        when(workspaceService.prepareWorkspace(
                repositoryClient, "acme", "repo", "feature/test", 42L))
                .thenReturn(WorkspaceResult.failure("stop"));

        UnitTestService.Result result = service.generate(request);

        assertThat(result.status()).isEqualTo(UnitTestService.Result.Status.FAILED);
        verify(workspaceService).prepareWorkspace(
                repositoryClient, "acme", "repo", "feature/test", 42L);
    }

    @Test
    void commitToPr_usesWritablePullRequestWorkspace() {
        WebhookPayload payload = payload();
        PrWorkflowContext context = new PrWorkflowContext(
                new Bot(), payload, 1L, (name, log) -> { }, () -> false);
        UnitTestService.Request request = new UnitTestService.Request(
                context, null, 1, 1, SuiteLifecycleMode.COMMIT_TO_PR);
        when(repositoryClient.getPullRequestDiff("acme", "repo", 42L)).thenReturn("diff");
        when(workspaceService.prepareWritablePullRequestWorkspace(
                repositoryClient, "acme", "repo", "feature/test", 42L))
                .thenReturn(WorkspaceResult.failure("stop"));

        UnitTestService.Result result = service.generate(request);

        assertThat(result.status()).isEqualTo(UnitTestService.Result.Status.FAILED);
        verify(workspaceService).prepareWritablePullRequestWorkspace(
                repositoryClient, "acme", "repo", "feature/test", 42L);
    }

    @Test
    void commitToPr_pushFailure_isWorkflowFailureEvenWhenTestsPass(@TempDir Path workspace) {
        WebhookPayload payload = payload();
        PrWorkflowContext context = new PrWorkflowContext(
                new Bot(), payload, 1L, (name, log) -> { }, () -> false);
        UnitTestService.Request request = new UnitTestService.Request(
                context, UnitTestFramework.MAVEN, 1, 1, SuiteLifecycleMode.COMMIT_TO_PR);
        when(repositoryClient.getPullRequestDiff("acme", "repo", 42L)).thenReturn("diff");
        when(workspaceService.prepareWritablePullRequestWorkspace(
                repositoryClient, "acme", "repo", "feature/test", 42L))
                .thenReturn(WorkspaceResult.success(workspace));
        when(suiteRepository.save(any(UnitTestSuite.class))).thenAnswer(invocation -> {
            UnitTestSuite suite = invocation.getArgument(0);
            suite.setId(10L);
            return suite;
        });
        when(authorAgent.write(any(), any(), anyString(), any(), eq(1)))
                .thenReturn(new UnitTestAuthorAgent.Result(1, "written", false));
        when(workspaceService.hasUncommittedChanges(workspace)).thenReturn(true);
        when(workspaceService.listChangedFiles(workspace))
                .thenReturn(List.of("src/test/java/GeneratedTest.java"));
        when(workspaceService.commitAndPush(eq(workspace), eq("feature/test"), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(false);
        when(runner.run(any())).thenReturn(UnitTestOutcome.passed(
                "all tests passed", 1, CoverageResult.unknown(), null));
        when(suiteRepository.findByIdWithCases(10L)).thenReturn(Optional.empty());

        UnitTestService.Result result = service.generate(request);

        assertThat(result.status()).isEqualTo(UnitTestService.Result.Status.FAILED);
        assertThat(result.summary()).contains("could not be committed");
    }

    private static WebhookPayload payload() {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setOwner(owner);
        repository.setName("repo");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/test");
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(42L);
        pullRequest.setHead(head);
        WebhookPayload payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setPullRequest(pullRequest);
        return payload;
    }
}
