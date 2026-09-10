package org.remus.giteabot.prworkflow.unittest;

import org.junit.jupiter.api.Test;
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
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import static org.assertj.core.api.Assertions.assertThat;
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
