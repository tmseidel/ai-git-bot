package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pin down the runtime built-in tool whitelist enforcement performed by
 * {@link AgentToolRouter#execute}. MCP tools and unknown tools are exempt
 * — only registered built-in tools are gated.
 */
class AgentToolRouterWhitelistTest {

    private final AgentConfigProperties agentConfig = new AgentConfigProperties();
    private final ToolCatalog catalog = new ToolCatalog(agentConfig);

    private AgentToolRouter newRouter(ToolExecutionService tes, Set<String> allowed,
                                      McpToolCatalog mcpCatalog,
                                      McpOrchestrationService mcpOrchestration) {
        return new AgentToolRouter(tes, catalog, mcpOrchestration, null,
                mcpCatalog, mock(RepositoryApiClient.class), allowed);
    }

    private static ToolCallContext ctx(String tool, List<String> args) {
        return new ToolCallContext(null, null, null, Path.of("/tmp/ws"),
                ImplementationPlan.ToolRequest.builder().id("id-1").tool(tool).args(args).build());
    }

    @Test
    void execute_allowedBuiltin_passesThroughToExecutor() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        when(tes.executeContextTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        AgentToolRouter router = newRouter(tes, Set.of("cat"), McpToolCatalog.empty(), null);

        ToolResult res = router.execute(AgentToolRouter.Mode.CODING,
                ctx("cat", List.of("README.md")));

        assertThat(res.success()).isTrue();
        verify(tes).executeContextTool(any(), eqStr("cat"), eqArgs("README.md"));
    }

    @Test
    void execute_disallowedBuiltin_isDeniedWithoutDispatch() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        AgentToolRouter router = newRouter(tes, Set.of("cat"), McpToolCatalog.empty(), null);

        ToolResult res = router.execute(AgentToolRouter.Mode.CODING,
                ctx("write-file", List.of("X.java", "x")));

        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("not enabled for this bot");
        verify(tes, never()).executeFileTool(any(), any(), any());
    }

    @Test
    void execute_nullWhitelist_disablesGatingForCompatibility() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        when(tes.executeFileTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        AgentToolRouter router = newRouter(tes, null, McpToolCatalog.empty(), null);

        ToolResult res = router.execute(AgentToolRouter.Mode.CODING,
                ctx("write-file", List.of("X.java", "x")));

        assertThat(res.success()).isTrue();
        verify(tes).executeFileTool(any(), eqStr("write-file"), any());
    }

    @Test
    void execute_mcpTool_isNotGatedByBuiltinWhitelist() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        McpOrchestrationService orchestration = mock(McpOrchestrationService.class);
        McpToolCatalog mcp = new McpToolCatalog(List.of(new McpToolDefinition(
                "srv", "do", "Do", "d", Map.of(), "srv.do")));
        when(orchestration.isMcpTool(any(), any())).thenReturn(true);
        when(orchestration.executeTool(any(), any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "mcp-ok", ""));
        AgentToolRouter router = newRouter(tes, Set.of("cat"), mcp, orchestration);

        ToolResult res = router.execute(AgentToolRouter.Mode.CODING,
                ctx("srv.do", List.of()));

        assertThat(res.success()).isTrue();
        verify(orchestration).executeTool(any(), any(), eqStr("srv.do"), any());
    }

    @Test
    void execute_writerMode_gatesGetIssueAgainstWhitelist() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        AgentToolRouter router = newRouter(tes, Set.of("cat"), McpToolCatalog.empty(), null);

        ToolResult res = router.execute(AgentToolRouter.Mode.WRITER,
                ctx("get-issue", List.of("12")));

        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("not enabled for this bot");
    }

    // Mockito argThat helpers kept inline to avoid an extra import.
    private static String eqStr(String expected) {
        return org.mockito.ArgumentMatchers.argThat(expected::equals);
    }

    @SafeVarargs
    private static <T> List<T> eqArgs(T... expected) {
        List<T> exp = List.of(expected);
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.equals(exp));
    }
}



