package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link CodingAgentStrategy} that pin down the
 * three documented edge cases the previous {@code runToolImplementationLoop}
 * supported (Step 4B docs):
 * <ul>
 *     <li>Multi-round validation retry (legacy {@code attempt--}-style budget).</li>
 *     <li>{@code IGNORE_MCP_AFTER_VALIDATION_SUCCESS} policy.</li>
 *     <li>File-only success without a validation tool.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CodingAgentStrategyTest {

    @Mock private RepositoryApiClient repositoryClient;
    @Mock private AgentSessionService sessionService;
    @Mock private WorkspaceService workspaceService;
    @Mock private IssueNotificationService notificationService;
    @Mock private BranchSwitcher branchSwitcher;
    @Mock private AgentToolRouter toolRouter;

    private AgentConfigProperties agentConfig;
    private ToolCatalog toolCatalog;
    private AgentRunContext ctx;
    private AgentPromptBuilder promptBuilder;
    private AiResponseParser responseParser;

    @BeforeEach
    void setUp() {
        agentConfig = new AgentConfigProperties();
        agentConfig.getValidation().setEnabled(true);
        agentConfig.getBudget().setMaxValidationRetries(3);
        agentConfig.getValidation().setMaxToolExecutions(10);
        // Real catalog: "mvn" is validation (config default), "write-file" is FILE, etc.
        toolCatalog = new ToolCatalog(agentConfig);
        promptBuilder = new AgentPromptBuilder();
        responseParser = new AiResponseParser();
        AgentSession session = new AgentSession("o", "r", 1L, "t");
        ctx = new AgentRunContext(session, "o", "r", 1L, Path.of("/tmp/ws"), "main");
    }

    private CodingAgentStrategy newStrategy() {
        return new CodingAgentStrategy("sys", promptBuilder, responseParser, notificationService,
                sessionService, branchSwitcher, toolRouter, toolCatalog,
                workspaceService, agentConfig, null, McpToolCatalog.empty(), null,
                (owner, repo, branch, files, tools, ws) -> "fetched-context");
    }

    @Test
    void step_validationFailsThenSucceeds_continuesAndFinishesAfterRetry() {
        // Round 1: write-file + mvn — mvn fails. Round 2: write-file + mvn — mvn passes.
        String failing = """
                ```json
                {"summary":"v1","runTools":[
                    {"id":"a","tool":"write-file","args":["X.java","x"]},
                    {"id":"b","tool":"mvn","args":["compile"]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(false, 1, "", "compile error")) // mvn fail
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", "")); // mvn pass
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        CodingAgentStrategy strategy = newStrategy();

        // Round 1 -> Continue (validation failed, attempt incremented to 2)
        StepDecision d1 = strategy.step(ctx, failing, 1);
        assertThat(d1).isInstanceOf(StepDecision.Continue.class);
        // Round 2 -> Finish success (validation passed, workspace has changes)
        StepDecision d2 = strategy.step(ctx, failing, 2);
        assertThat(d2).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d2).outcome().success()).isTrue();
    }

    @Test
    void step_ignoreMcpAfterValidationSuccessPolicy_treatsMcpFailureAsSuccess() {
        agentConfig.getValidation().setNonValidationFailurePolicy(
                AgentConfigProperties.ValidationConfig.NonValidationFailurePolicy.IGNORE_MCP_AFTER_VALIDATION_SUCCESS);
        // Build a strategy with a McpOrchestrationService + catalog that knows "mcp-tool".
        var orchestration = mock(org.remus.giteabot.mcp.McpOrchestrationService.class);
        var catalog = new McpToolCatalog(List.of(
                new org.remus.giteabot.mcp.McpToolDefinition(
                        "s", "mcp-tool", "Mcp Tool", "d", java.util.Map.of(), "s.mcp-tool")));
        when(orchestration.isMcpTool(any(), eq("mcp-tool"))).thenReturn(true);
        lenient().when(orchestration.isMcpTool(any(), eq("mvn"))).thenReturn(false);
        lenient().when(orchestration.isMcpTool(any(), eq("write-file"))).thenReturn(false);
        CodingAgentStrategy strategy = new CodingAgentStrategy("sys", promptBuilder, responseParser,
                notificationService, sessionService, branchSwitcher, toolRouter,
                toolCatalog, workspaceService, agentConfig, orchestration, catalog, null,
                (a, b, c, d, e, f) -> "ctx");

        String response = """
                ```json
                {"summary":"x","runTools":[
                    {"id":"a","tool":"write-file","args":["F.java","x"]},
                    {"id":"b","tool":"mvn","args":["compile"]},
                    {"id":"c","tool":"mcp-tool","args":[]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // mvn pass
                .thenReturn(new ToolResult(false, 1, "", "mcp boom")); // mcp fail
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        StepDecision d = strategy.step(ctx, response, 1);

        assertThat(d).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d).outcome().success()).isTrue();
    }

    @Test
    void step_fileOnlyResponseWithoutValidationTool_finishesAsSuccess() {
        String response = """
                ```json
                {"summary":"file-only","runTools":[
                    {"id":"a","tool":"write-file","args":["F.java","x"]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        StepDecision d = newStrategy().step(ctx, response, 1);

        assertThat(d).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d).outcome().success()).isTrue();
        verify(workspaceService).hasUncommittedChanges(any());
    }
}


