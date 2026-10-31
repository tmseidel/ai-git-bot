package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wiring from a bot's tool configuration to a PR-workflow run's surface. The selection must
 * reach the run: a bot that never opted into {@code execute-code} (the Default configuration does
 * not seed it — see {@code ExecuteCodeOptInConfigurationTest}) must not get the sandbox on a
 * readme-sync / i18n / unit-test / e2e run, and a read-only tool an operator removed must stay
 * removed. Before this wiring, {@code WorkflowToolSurfaceFactory} added both unconditionally and
 * the workflow agents' static fallback did the same.
 */
class WorkflowToolSurfaceFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> WORKFLOW_TOOLS = Set.of("doc-write", "doc-delete");

    private final BotToolSelectionService selections = mock(BotToolSelectionService.class);
    private final WorkflowToolSurfaceFactory factory = new WorkflowToolSurfaceFactory(
            mock(ToolExecutionService.class),
            new ToolCatalog(new AgentConfigProperties()),
            mock(McpOrchestrationService.class),
            mock(McpToolSelectionService.class),
            selections,
            mock(PythonExecutionService.class));

    private WorkflowToolSurface runFor(Set<String> botTools) {
        when(selections.allowedBuiltinTools(any())).thenReturn(botTools);
        return factory.create(mock(Bot.class), mock(RepositoryApiClient.class), WORKFLOW_TOOLS,
                "acme", "repo", 42L, Workspace.unmanaged(Path.of("/tmp/ws")));
    }

    @Test
    void aWorkflowRunOffersExecuteCodeOnlyWhenTheBotSelectsIt() {
        WorkflowToolSurface optedIn = runFor(Set.of("execute-code", "cat", "rg"));
        assertThat(optedIn.callable()).contains("execute-code");
        assertThat(optedIn.advertised()).extracting(ToolDescriptor::name).contains("execute-code");

        WorkflowToolSurface optedOut = runFor(Set.of("cat", "rg"));
        assertThat(optedOut.callable()).doesNotContain("execute-code");
        assertThat(optedOut.advertised()).extracting(ToolDescriptor::name).doesNotContain("execute-code");
        // The router's whitelist is the same set, so a stray call is refused in the executor's words.
        assertThat(optedOut.executeRouted("execute-code", JSON.createObjectNode().put("code", "print(1)")))
                .contains("not enabled for this bot");
    }

    @Test
    void aWorkflowRunDoesNotRestoreAReadOnlyToolTheOperatorRemoved() {
        WorkflowToolSurface surface = runFor(Set.of("cat"));

        assertThat(surface.callable()).contains("cat").doesNotContain("rg", "get-issue");
    }
}
