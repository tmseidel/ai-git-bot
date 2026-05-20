package org.remus.giteabot.prworkflow.deployment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.WorkflowDispatchRequest;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CiActionTriggerStrategyTest {

    private final BotRepository botRepository = mock(BotRepository.class);
    private final GiteaClientFactory clientFactory = mock(GiteaClientFactory.class);
    private final RepositoryApiClient apiClient = mock(RepositoryApiClient.class);

    private final CiActionTriggerStrategy strategy =
            new CiActionTriggerStrategy(botRepository, clientFactory);

    private DeploymentRequest request(String configJson) {
        DeploymentTarget target = new DeploymentTarget();
        target.setName("github-ci");
        target.setStrategyType(DeploymentStrategyType.CI_ACTION);
        target.setConfigJson(configJson);
        target.setTimeoutSeconds(600);
        target.setPreviewUrlTemplate("https://pr-{prNumber}.preview.example.com");
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setBotId(7L);
        run.setCallbackSecret("cbsecret");
        run.setStartedAt(Instant.now());
        run.setRepoOwner("acme");
        run.setRepoName("web");
        run.setPrNumber(123L);
        return new DeploymentRequest(run, target, "acme", "web", 123L,
                "abc123", "feature/x",
                "https://bot.acme.io/api/workflow-callback/42/cbsecret");
    }

    private void stubBot() {
        Bot bot = new Bot();
        bot.setName("bot1");
        GitIntegration integration = new GitIntegration();
        integration.setId(99L);
        integration.setProviderType(RepositoryType.GITHUB);
        integration.setUpdatedAt(Instant.now());
        bot.setGitIntegration(integration);
        when(botRepository.findByIdWithIntegrations(7L)).thenReturn(Optional.of(bot));
        when(clientFactory.getApiClient(any())).thenReturn(apiClient);
    }

    @Test
    void typeKeyIsCiAction() {
        assertThat(strategy.typeKey()).isEqualTo(DeploymentStrategyType.CI_ACTION);
        assertThat(strategy.awaitsCallback()).isTrue();
    }

    @Test
    void rejectsMissingWorkflowRef() {
        DeploymentResult result = strategy.trigger(request("{}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("workflowRef");
    }

    @Test
    void rejectsInvalidJson() {
        DeploymentResult result = strategy.trigger(request("not-json"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
    }

    @Test
    void rejectsMissingBot() {
        when(botRepository.findByIdWithIntegrations(7L)).thenReturn(Optional.empty());
        DeploymentResult result = strategy.trigger(request("{\"workflowRef\":\"preview.yml\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("no longer exists");
    }

    @Test
    void dispatchesAndReturnsPendingWithHandle() {
        stubBot();
        when(apiClient.dispatchWorkflow(any())).thenReturn("987654");

        String cfg = "{\"workflowRef\":\"preview.yml\","
                + "\"refTemplate\":\"refs/pull/{prNumber}/head\","
                + "\"previewUrlOutput\":\"preview_url\","
                + "\"pollIntervalSeconds\":20,"
                + "\"inputs\":{\"callbackUrl\":\"{callbackUrl}\",\"sha\":\"{sha}\"}}";
        DeploymentResult result = strategy.trigger(request(cfg));

        assertThat(result.status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(result.handleJson()).contains("\"strategy\":\"CI_ACTION\"");
        assertThat(result.handleJson()).contains("\"runId\":\"987654\"");
        assertThat(result.handleJson()).contains("\"provider\":\"GITHUB\"");

        ArgumentCaptor<WorkflowDispatchRequest> captor =
                ArgumentCaptor.forClass(WorkflowDispatchRequest.class);
        verify(apiClient).dispatchWorkflow(captor.capture());
        WorkflowDispatchRequest dispatched = captor.getValue();
        assertThat(dispatched.owner()).isEqualTo("acme");
        assertThat(dispatched.repo()).isEqualTo("web");
        assertThat(dispatched.workflowRef()).isEqualTo("preview.yml");
        assertThat(dispatched.gitRef()).isEqualTo("refs/pull/123/head");
        assertThat(dispatched.inputs()).containsEntry("sha", "abc123");
        assertThat(dispatched.inputs()).containsEntry("callbackUrl",
                "https://bot.acme.io/api/workflow-callback/42/cbsecret");

        Optional<CiActionTriggerStrategy.Handle> parsed =
                CiActionTriggerStrategy.parseHandle(result.handleJson());
        assertThat(parsed).isPresent();
        assertThat(parsed.get().pollIntervalSeconds()).isEqualTo(20);
        assertThat(parsed.get().previewUrlOutput()).isEqualTo("preview_url");
        assertThat(parsed.get().integrationId()).isEqualTo(99L);
    }

    @Test
    void unsupportedProviderIsRejected() {
        stubBot();
        when(apiClient.dispatchWorkflow(any()))
                .thenThrow(new UnsupportedOperationException("nope"));
        DeploymentResult result = strategy.trigger(request("{\"workflowRef\":\"preview.yml\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("CI_ACTION dispatch");
    }

    @Test
    void networkFailureIsTranslatedToFailed() {
        stubBot();
        when(apiClient.dispatchWorkflow(any()))
                .thenThrow(new IllegalStateException("network down"));
        DeploymentResult result = strategy.trigger(request("{\"workflowRef\":\"preview.yml\"}"));
        assertThat(result.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(result.errorMessage()).contains("network down");
    }

    @Test
    void parseHandleIgnoresNonCiActionDocuments() {
        assertThat(CiActionTriggerStrategy.parseHandle("{\"strategy\":\"WEBHOOK\"}")).isEmpty();
        assertThat(CiActionTriggerStrategy.parseHandle("not-json")).isEmpty();
        assertThat(CiActionTriggerStrategy.parseHandle(null)).isEmpty();
    }

    @Test
    void clampPollIntervalRespectsBounds() {
        assertThat(CiActionTriggerStrategy.clampPollInterval(0))
                .isEqualTo(CiActionTriggerStrategy.MIN_POLL_INTERVAL_SECONDS);
        assertThat(CiActionTriggerStrategy.clampPollInterval(9999))
                .isEqualTo(CiActionTriggerStrategy.MAX_POLL_INTERVAL_SECONDS);
        assertThat(CiActionTriggerStrategy.clampPollInterval(30)).isEqualTo(30);
    }

    @Test
    void placeholderSubstitutionCoversAllFields() {
        DeploymentRequest req = request("{\"workflowRef\":\"x\"}");
        String resolved = CiActionTriggerStrategy.applyPlaceholders(
                "{repoOwner}/{repoName}#{prNumber}@{sha}/{branch}/r={runId}/{callbackSecret}", req);
        assertThat(resolved).isEqualTo("acme/web#123@abc123/feature/x/r=42/cbsecret");
    }
}

