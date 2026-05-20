package org.remus.giteabot.prworkflow.deployment.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.deployment.DeploymentRequest;
import org.remus.giteabot.prworkflow.deployment.DeploymentResult;
import org.remus.giteabot.prworkflow.deployment.DeploymentStatus;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationRepository;
import org.remus.giteabot.systemsettings.McpToolSelectionService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MCPDeploymentStrategyTest {

    private McpOrchestrationService orchestration;
    private McpConfigurationRepository configRepo;
    private McpToolSelectionService selectionService;
    private MCPDeploymentStrategy strategy;
    private McpConfiguration mcpConfig;

    @BeforeEach
    void setUp() {
        orchestration = mock(McpOrchestrationService.class);
        configRepo = mock(McpConfigurationRepository.class);
        selectionService = mock(McpToolSelectionService.class);
        strategy = new MCPDeploymentStrategy(orchestration, configRepo, selectionService);

        mcpConfig = new McpConfiguration();
        mcpConfig.setId(7L);
        mcpConfig.setName("platform");
        when(configRepo.findById(7L)).thenReturn(Optional.of(mcpConfig));
        when(orchestration.discoverTools(mcpConfig)).thenReturn(McpToolCatalog.empty());
    }

    @Test
    void typeKeyAndCallbackFlagMatchSpec() {
        assertThat(strategy.typeKey()).isEqualTo(DeploymentStrategyType.MCP);
        assertThat(strategy.awaitsCallback()).isFalse();
    }

    @Test
    void triggerReturnsReadyWhenDeployToolReportsPreviewUrl() {
        when(selectionService.selectedQualifiedToolNameSet(7L))
                .thenReturn(Set.of("platform/deploy"));
        when(orchestration.executeTool(eq(mcpConfig), any(), eq("platform/deploy"), any()))
                .thenReturn(new ToolResult(true, 0,
                        "{ \"previewUrl\": \"https://pr-42.preview.acme.io\" }", ""));

        DeploymentRequest req = request("{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform/deploy\" }");
        DeploymentResult result = strategy.trigger(req);

        assertThat(result.status()).isEqualTo(DeploymentStatus.READY);
        assertThat(result.previewUrl()).isEqualTo("https://pr-42.preview.acme.io");
        assertThat(result.handleJson()).contains("\"strategy\":\"MCP\"");
    }

    @Test
    void triggerReturnsPendingWhenDeployToolOnlyHandsBackHandle() {
        when(selectionService.selectedQualifiedToolNameSet(7L))
                .thenReturn(Set.of("platform/deploy"));
        when(orchestration.executeTool(eq(mcpConfig), any(), eq("platform/deploy"), any()))
                .thenReturn(new ToolResult(true, 0,
                        "{ \"handle\": { \"deploymentId\": \"d-1\" } }", ""));

        DeploymentResult result = strategy.trigger(request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform/deploy\" }"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(result.previewUrl()).isNull();
        assertThat(result.handleJson()).contains("\"deploymentId\":\"d-1\"");
    }

    @Test
    void triggerReturnsFailedWhenDeployToolReturnsErrorStatus() {
        when(selectionService.selectedQualifiedToolNameSet(7L))
                .thenReturn(Set.of("platform/deploy"));
        when(orchestration.executeTool(eq(mcpConfig), any(), eq("platform/deploy"), any()))
                .thenReturn(new ToolResult(true, 0,
                        "{ \"status\": \"failed\", \"error\": \"quota exceeded\" }", ""));

        DeploymentResult result = strategy.trigger(request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform/deploy\" }"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(result.errorMessage()).contains("quota exceeded");
    }

    @Test
    void triggerRejectsNonWhitelistedDeployTool() {
        when(selectionService.selectedQualifiedToolNameSet(7L)).thenReturn(Set.of());

        DeploymentResult result = strategy.trigger(request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform/deploy\" }"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage())
                .contains("not whitelisted")
                .contains("platform/deploy");
        verify(orchestration, never()).executeTool(any(), any(), any(), any());
    }

    @Test
    void triggerRejectsWhenMcpConfigurationMissing() {
        when(configRepo.findById(7L)).thenReturn(Optional.empty());

        DeploymentResult result = strategy.trigger(request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform/deploy\" }"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(result.errorMessage()).contains("not found");
    }

    @Test
    void triggerRejectsInvalidConfigJson() {
        DeploymentResult result = strategy.trigger(request("not-json"));

        assertThat(result.status()).isEqualTo(DeploymentStatus.REJECTED);
    }

    @Test
    void pollUsesStatusToolAndUpgradesPendingToReady() {
        when(selectionService.selectedQualifiedToolNameSet(7L))
                .thenReturn(Set.of("platform/deploy", "platform/status"));
        when(orchestration.executeTool(eq(mcpConfig), any(), eq("platform/status"), any()))
                .thenReturn(new ToolResult(true, 0,
                        "{ \"status\": \"ready\", \"previewUrl\": \"https://pr-7.acme.io\" }", ""));

        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setPrNumber(7L);
        run.setRepoOwner("acme");
        run.setRepoName("web");
        run.setDeploymentHandleJson(
                "{ \"strategy\":\"MCP\", \"mcpConfigurationId\":7, \"deployTool\":\"platform/deploy\","
                + " \"statusTool\":\"platform/status\" }");

        DeploymentResult result = strategy.poll(run);

        assertThat(result.status()).isEqualTo(DeploymentStatus.READY);
        assertThat(result.previewUrl()).isEqualTo("https://pr-7.acme.io");
    }

    @Test
    void pollFallsBackToDefaultWhenHandleHasNoStatusTool() {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setPreviewUrl("https://known.acme.io");
        run.setDeploymentHandleJson(
                "{ \"strategy\":\"MCP\", \"mcpConfigurationId\":7, \"deployTool\":\"d\" }");

        DeploymentResult result = strategy.poll(run);

        assertThat(result.status()).isEqualTo(DeploymentStatus.READY);
        assertThat(result.previewUrl()).isEqualTo("https://known.acme.io");
        verify(orchestration, never()).executeTool(any(), any(), any(), any());
    }

    @Test
    void teardownInvokesTeardownToolWhenPresent() {
        when(orchestration.executeTool(eq(mcpConfig), any(), eq("platform/teardown"), any()))
                .thenReturn(new ToolResult(true, 0, "{}", ""));

        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setPrNumber(9L);
        run.setRepoOwner("acme");
        run.setRepoName("web");
        run.setDeploymentHandleJson(
                "{ \"strategy\":\"MCP\", \"mcpConfigurationId\":7, \"deployTool\":\"d\","
                + " \"teardownTool\":\"platform/teardown\" }");

        strategy.teardown(run);

        verify(orchestration).executeTool(eq(mcpConfig), any(), eq("platform/teardown"), any());
    }

    @Test
    void teardownIsNoOpWhenHandleMissing() {
        strategy.teardown(new PrWorkflowRun());

        verify(orchestration, never()).executeTool(any(), any(), any(), any());
    }

    @Test
    void renderArgumentsExpandsPlaceholdersFromRequest() {
        McpDeploymentConfig cfg = new McpDeploymentConfig(7L, "d", null, null,
                java.util.Map.of("branch", "{branch}", "sha", "{sha}", "pr", "{prNumber}"));

        java.util.Map<String, Object> args = strategy.renderArguments(cfg, request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"d\" }"));

        assertThat(args).containsEntry("branch", "feature/x");
        assertThat(args).containsEntry("sha", "abc123");
        assertThat(args).containsEntry("pr", "1234");
    }

    @Test
    void renderArgumentsDefaultsToFullPlaceholderEnvelope() {
        McpDeploymentConfig cfg = new McpDeploymentConfig(7L, "d", null, null, java.util.Map.of());

        java.util.Map<String, Object> args = strategy.renderArguments(cfg, request(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"d\" }"));

        assertThat(args.keySet())
                .containsExactlyInAnyOrder(
                        "prNumber", "sha", "branch",
                        "repoOwner", "repoName",
                        "runId", "callbackUrl", "callbackSecret");
    }

    // ---- helpers ----

    private DeploymentRequest request(String configJson) {
        DeploymentTarget target = new DeploymentTarget();
        target.setName("platform-preview");
        target.setStrategyType(DeploymentStrategyType.MCP);
        target.setConfigJson(configJson);

        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setCallbackSecret("cb-secret");

        return new DeploymentRequest(
                run, target,
                "acme", "web",
                1234L,
                "abc123",
                "feature/x",
                "https://bot.acme.io/api/workflow-callback/42/cb-secret");
    }

    @SuppressWarnings("unused")
    private static List<String> singleArg(String arg) {
        return List.of(arg);
    }
}

