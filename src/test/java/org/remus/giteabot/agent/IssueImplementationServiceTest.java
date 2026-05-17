package org.remus.giteabot.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueImplementationServiceTest {

    @Mock private RepositoryApiClient repositoryClient;
    @Mock private AiClient aiClient;
    @Mock private PromptService promptService;
    @Mock private AgentSessionService sessionService;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private WorkspaceService workspaceService;
    @Mock private McpOrchestrationService mcpOrchestrationService;
    private org.remus.giteabot.agent.tools.ToolCatalog toolCatalog;

    private IssueImplementationService service;

    private static final Path FAKE_WORKSPACE = Path.of("/tmp/test-workspace");

    @BeforeEach
    void setUp() {
        AgentConfigProperties agentConfig = new AgentConfigProperties();
        agentConfig.setEnabled(true);
        agentConfig.setMaxFiles(10);
        agentConfig.setBranchPrefix("ai-agent/");
        // Real catalog – classification (is*Tool, name lists) is no longer mocked.
        // TES is mocked only for execution methods.
        toolCatalog = new org.remus.giteabot.agent.tools.ToolCatalog(agentConfig);
        IssueImplementationContext context = new IssueImplementationContext(
                repositoryClient, aiClient, null, null, null, null, McpToolCatalog.empty(), null);
        service = new IssueImplementationService(context, promptService, agentConfig,
                sessionService, toolExecutionService, toolCatalog, workspaceService);

        lenient().when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);
    }

    // ---- handleIssueAssigned tests ----

    @Test
    void handleIssueAssigned_successfulFlow_writesFileAndValidates() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(repositoryClient.getIssueComments("testowner", "testrepo", 42L))
                .thenReturn(List.of(
                        Map.of("body", "Please keep backward compatibility", "user", Map.of("login", "alice")),
                        Map.of("body", "Also add a migration note", "user", Map.of("login", "bob"))));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        // AI implementation response with write-file + mvn (single loop call now —
        // the previous "Step 1: which files do you need?" pre-loop turn was folded
        // into the AgentLoop in 2026-05; the strategy still supports context-only
        // rounds, but tests only need to stub the meaningful turns).
        String implResponse = """
                ```json
                {
                  "summary": "Implemented the feature",
                  "runTools": [
                    {"id": "a1b2c3d4-1111-2222-3333-444455556666", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "b2c3d4e5-2222-3333-4444-555566667777", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(implResponse);

        // write-file is a file tool → executeFileTool
        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written: src/Feature.java", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        // Workspace cloned once
        verify(workspaceService).prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull());
        // write-file executed
        verify(toolExecutionService).executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"),
                eq(List.of("src/Feature.java", "public class Feature {}")));
        // mvn compile executed
        verify(toolExecutionService).executeTool(eq(FAKE_WORKSPACE), eq("mvn"),
                eq(List.of("compile", "-q", "-B")));
        // Committed and pushed
        verify(workspaceService).commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true));
        // PR created
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"));
        // No createOrUpdateFile calls (old API approach)
        verify(repositoryClient, never()).createOrUpdateFile(any(), any(), any(), any(), any(), any(), any());
        // workspace cleaned up
        verify(workspaceService).cleanupWorkspace(FAKE_WORKSPACE);
        // at least 2 comments posted
        verify(repositoryClient, atLeast(2)).postIssueComment(eq("testowner"), eq("testrepo"), eq(42L), anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient, times(1)).chat(anyList(), promptCaptor.capture(), anyString(), isNull(), anyInt());
        assertThat(promptCaptor.getAllValues().get(0)).contains("Please keep backward compatibility");
        assertThat(promptCaptor.getAllValues().get(0)).contains("Also add a migration note");
    }

    @Test
    void handleIssueAssigned_excludesBotAuthoredIssueCommentsFromPromptContext() {
        IssueImplementationService serviceWithBotUsername = createServiceWithBotUsername("ai_bot");
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(repositoryClient.getIssueComments("testowner", "testrepo", 42L))
                .thenReturn(List.of(
                        Map.of("body", "🤖 **AI Agent**: I've been assigned to this issue.",
                                "user", Map.of("login", "ai_bot")),
                        Map.of("body", "Human clarification that must be implemented",
                                "user", Map.of("login", "alice"))));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String implResponse = """
                ```json
                {
                  "summary": "Implemented the feature",
                  "runTools": [
                    {"id": "a1", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "b1", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(implResponse);
        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        serviceWithBotUsername.handleIssueAssigned(payload);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient, times(1)).chat(anyList(), promptCaptor.capture(), anyString(), isNull(), anyInt());
        assertThat(promptCaptor.getAllValues().get(0)).contains("Human clarification that must be implemented");
        assertThat(promptCaptor.getAllValues().get(0)).doesNotContain("I've been assigned to this issue");
    }

    @Test
    void handleIssueAssigned_fileToolFailureWithPassingValidation_retriesInsteadOfCommittingNoChanges() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String failedPatchResponse = """
                ```json
                {
                  "summary": "Try patch",
                  "runTools": [
                    {"id": "patch-1", "tool": "patch-file", "args": ["README.md", "missing", "replacement"]},
                    {"id": "validate-1", "tool": "mvn", "args": ["validate", "-q", "-B"]}
                  ]
                }
                ```
                """;
        String fixedWriteResponse = """
                ```json
                {
                  "summary": "Write file after patch failed",
                  "runTools": [
                    {"id": "write-1", "tool": "write-file", "args": ["README.md", "updated"]},
                    {"id": "validate-2", "tool": "mvn", "args": ["validate", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(failedPatchResponse, fixedWriteResponse);

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"), anyList()))
                .thenReturn(new ToolResult(false, 1, "", "patch-file: search text not found in file: README.md"));
        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written: README.md", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        verify(toolExecutionService).executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"),
                eq(List.of("README.md", "missing", "replacement")));
        verify(toolExecutionService).executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"),
                eq(List.of("README.md", "updated")));
        verify(workspaceService, times(1)).commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true));
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"));
    }

    @Test
    void handleIssueAssigned_workspacePreparationFails_postsError() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                .thenReturn(WorkspaceResult.failure("git clone failed"));

        service.handleIssueAssigned(payload);

        verify(repositoryClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        verify(repositoryClient, atLeast(1)).postIssueComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("Failed to prepare workspace"));
    }

    @Test
    void handleIssueAssigned_unhandledFailure_postsUnifiedInternalErrorComment() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenThrow(new RuntimeException("simulated coding failure"));

        service.handleIssueAssigned(payload);

        verify(repositoryClient, atLeastOnce()).postIssueComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("I hit an internal error while processing this request: `simulated coding failure`"));
    }

    @Test
    void handleIssueAssigned_contextRequestsBranchSwitcher_usesSwitchedBaseBranch() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String contextResponse = """
                ```json
                {
                  "summary": "Switch branch and inspect",
                  "requestTools": [
                    {"id": "ctx-001", "tool": "branch-switcher", "args": ["develop"]},
                    {"id": "ctx-002", "tool": "rg", "args": ["Feature", "src"]}
                  ]
                }
                ```
                """;
        String implResponse = """
                ```json
                {
                  "summary": "Implemented on develop",
                  "runTools": [
                    {"id": "f-001", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "v-001", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(contextResponse, implResponse);

        when(toolExecutionService.executeContextTool(eq(FAKE_WORKSPACE), eq("branch-switcher"), eq(List.of("develop"))))
                .thenReturn(new ToolResult(true, 0, "Switched workspace branch to: develop", ""));
        when(toolExecutionService.executeContextTool(eq(FAKE_WORKSPACE), eq("rg"), eq(List.of("Feature", "src"))))
                .thenReturn(new ToolResult(true, 0, "No matches found for pattern: Feature", ""));

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written: src/Feature.java", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("develop"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        verify(toolExecutionService).executeContextTool(eq(FAKE_WORKSPACE), eq("branch-switcher"), eq(List.of("develop")));
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("develop"));
    }

    @Test
    void handleIssueAssigned_contextBranchSwitcherFailure_fallsBackToOriginalBaseBranch() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String contextResponse = """
                ```json
                {
                  "summary": "Try branch switch",
                  "requestTools": [
                    {"id": "ctx-001", "tool": "branch-switcher", "args": ["develop"]}
                  ]
                }
                ```
                """;
        String implResponse = """
                ```json
                {
                  "summary": "Implemented on fallback branch",
                  "runTools": [
                    {"id": "f-001", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "v-001", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(contextResponse, implResponse);

        when(toolExecutionService.executeContextTool(eq(FAKE_WORKSPACE), eq("branch-switcher"), eq(List.of("develop"))))
                .thenReturn(new ToolResult(false, 1, null, null));

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written: src/Feature.java", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        verify(repositoryClient, never()).getRepositoryTree("testowner", "testrepo", "develop");
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"));
    }

    @Test
    void handleIssueAssigned_followUpContextRequest_readsFilesFromCurrentBaseBranch() {
        WebhookPayload payload = createIssuePayload();
        payload.getIssue().setRef("refs/heads/release/1.x");

        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "release/1.x"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "pom.xml")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("release/1.x"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String followUpContextResponse = """
                ```json
                {"summary": "Need pom", "requestFiles": ["pom.xml"]}
                ```
                """;
        String implResponse = """
                ```json
                {
                  "summary": "Implemented on release branch",
                  "runTools": [
                    {"id": "f-101", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "v-101", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(followUpContextResponse, implResponse);

        when(repositoryClient.getFileContent("testowner", "testrepo", "pom.xml", "release/1.x"))
                .thenReturn("<project />");

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written: src/Feature.java", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("release/1.x"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        verify(repositoryClient).getFileContent("testowner", "testrepo", "pom.xml", "release/1.x");
        verify(repositoryClient, never()).getFileContent("testowner", "testrepo", "pom.xml", "main");
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("release/1.x"));
    }

    @Test
    void handleIssueAssigned_aiReturnsNoTools_postsFailure() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main")).thenReturn(List.of());
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));
        // AI never provides runTools
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("I don't know how to do this");

        service.handleIssueAssigned(payload);

        verify(repositoryClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        verify(workspaceService, never()).commitAndPush(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void handleIssueAssigned_commitAndPushFails_postsError() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main")).thenReturn(List.of());
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String implResponse = """
                ```json
                {
                  "summary": "Implemented",
                  "runTools": [
                    {"id": "a1b2-0001", "tool": "write-file", "args": ["src/F.java", "class F {}"]},
                    {"id": "a1b2-0002", "tool": "mvn", "args": ["compile"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(implResponse);

        when(toolExecutionService.executeFileTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        when(toolExecutionService.executeTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        // Commit fails
        when(workspaceService.commitAndPush(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(false);

        service.handleIssueAssigned(payload);

        // No PR created
        verify(repositoryClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        // Error comment posted
        verify(repositoryClient, atLeast(1)).postIssueComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("pushing the branch failed"));
    }

    @Test
    void handleIssueAssigned_mcpFailureWithSuccessfulValidation_stillCompletes() {
        WebhookPayload payload = createIssuePayload();
        IssueImplementationService serviceWithMcp = createServiceWithMcp();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String implResponse = """
                ```json
                {
                  "summary": "Implement with MCP lookup",
                  "runTools": [
                    {"id": "mcp-1", "tool": "mcp:github:list_issues", "args": [{"owner":"tmseidel","repo":"ai-git-bot"}]},
                    {"id": "file-1", "tool": "write-file", "args": ["src/Feature.java", "public class Feature {}"]},
                    {"id": "val-1", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(implResponse);

        when(mcpOrchestrationService.isMcpTool(any(McpToolCatalog.class), eq("mcp:github:list_issues"))).thenReturn(true);
        when(mcpOrchestrationService.executeTool(any(), any(), eq("mcp:github:list_issues"), anyList()))
                .thenReturn(new ToolResult(false, 1, "", "cursor pagination required"));

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("write-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File written", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));

        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(true);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        serviceWithMcp.handleIssueAssigned(payload);

        verify(workspaceService).commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(true));
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"));
    }

    // ---- handleIssueComment tests ----

    @Test
    void handleIssueComment_requestsContextToolsThenImplements() {
        WebhookPayload payload = createCommentPayload("Please trace where Config is used");

        AgentSession session = new AgentSession("testowner", "testrepo", 42L, "Add new feature X");
        session.setBranchName("ai-agent/issue-42");
        session.setPrNumber(1L);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);

        when(sessionService.getSessionByIssue("testowner", "testrepo", 42L))
                .thenReturn(Optional.of(session));
        when(repositoryClient.getIssueComments("testowner", "testrepo", 42L))
                .thenReturn(List.of(Map.of("body", "Existing clarification from issue author", "user", Map.of("login", "alice"))));
        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(sessionService.toAiMessages(any())).thenReturn(
                new ArrayList<>(List.of(AiMessage.builder().role("user").content("Please trace where Config is used").build())));
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("ai-agent/issue-42"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        // First: request context tools
        String firstResponse = """
                ```json
                {
                  "summary": "Need to search for usages",
                  "requestTools": [
                    {"id": "ctx-001", "tool": "rg", "args": ["ConfigService", "src"]}
                  ]
                }
                ```
                """;
        // Second: implementation with patch-file + mvn
        String secondResponse = """
                ```json
                {
                  "summary": "Updated config after tracing usages",
                  "runTools": [
                    {"id": "f-001", "tool": "patch-file", "args": ["src/Config.java", "old", "new"]},
                    {"id": "v-001", "tool": "mvn", "args": ["compile"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(firstResponse, secondResponse);

        // Context tool stub (executeContextTool is called directly, no isContextTool check needed)
        when(toolExecutionService.executeContextTool(eq(FAKE_WORKSPACE), eq("rg"), anyList()))
                .thenReturn(new ToolResult(true, 0, "src/Config.java:12: ConfigService configService", ""));

        // File tool stubs for second round
        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File patched", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(true);

        service.handleIssueComment(payload);

        verify(toolExecutionService).executeContextTool(eq(FAKE_WORKSPACE), eq("rg"),
                eq(List.of("ConfigService", "src")));
        verify(toolExecutionService).executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"),
                eq(List.of("src/Config.java", "old", "new")));
        verify(workspaceService).commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(false));
        verify(workspaceService).cleanupWorkspace(FAKE_WORKSPACE);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient, times(2)).chat(anyList(), promptCaptor.capture(), anyString(), isNull(), anyInt());
        assertThat(promptCaptor.getAllValues().get(0)).contains("Existing clarification from issue author");
        assertThat(promptCaptor.getAllValues().get(0)).contains("Please trace where Config is used");
    }

    @Test
    void handleIssueComment_followUpContextRequest_readsFilesFromWorkingBranch() {
        WebhookPayload payload = createCommentPayload("Please inspect the current branch state");

        AgentSession session = new AgentSession("testowner", "testrepo", 42L, "Add new feature X");
        session.setBranchName("ai-agent/issue-42");
        session.setPrNumber(1L);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);

        when(sessionService.getSessionByIssue("testowner", "testrepo", 42L))
                .thenReturn(Optional.of(session));
        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(sessionService.toAiMessages(any())).thenReturn(
                new ArrayList<>(List.of(AiMessage.builder().role("user").content("Please inspect the current branch state").build())));
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("ai-agent/issue-42"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String firstResponse = """
                ```json
                {
                  "summary": "Need file contents from the working branch",
                  "requestFiles": ["README.md"]
                }
                ```
                """;
        String secondResponse = """
                ```json
                {
                  "summary": "Updated docs after reviewing working branch",
                  "runTools": [
                    {"id": "f-201", "tool": "patch-file", "args": ["README.md", "old", "new"]},
                    {"id": "v-201", "tool": "mvn", "args": ["compile"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(firstResponse, secondResponse);

        when(repositoryClient.getFileContent("testowner", "testrepo", "README.md", "ai-agent/issue-42"))
                .thenReturn("branch-specific readme");

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File patched", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(true);

        service.handleIssueComment(payload);

        verify(repositoryClient).getFileContent("testowner", "testrepo", "README.md", "ai-agent/issue-42");
        verify(repositoryClient, never()).getFileContent("testowner", "testrepo", "README.md", "main");
    }

    @Test
    void handleIssueComment_unhandledFailure_postsUnifiedInternalErrorComment() {
        WebhookPayload payload = createCommentPayload("Please continue");

        AgentSession session = new AgentSession("testowner", "testrepo", 42L, "Add new feature X");
        session.setBranchName("ai-agent/issue-42");
        session.setPrNumber(1L);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);

        when(sessionService.getSessionByIssue("testowner", "testrepo", 42L))
                .thenReturn(Optional.of(session));
        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("ai-agent/issue-42"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenThrow(new RuntimeException("follow-up coding failure"));

        service.handleIssueComment(payload);

        verify(repositoryClient).postIssueComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("I hit an internal error while processing this request: `follow-up coding failure`"));
    }

    @Test
    void handleIssueComment_mcpFailureWithSuccessfulValidation_stillCompletes() {
        WebhookPayload payload = createCommentPayload("Please continue");
        IssueImplementationService serviceWithMcp = createServiceWithMcp();

        AgentSession session = new AgentSession("testowner", "testrepo", 42L, "Add new feature X");
        session.setBranchName("ai-agent/issue-42");
        session.setPrNumber(1L);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);

        when(sessionService.getSessionByIssue("testowner", "testrepo", 42L))
                .thenReturn(Optional.of(session));
        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(sessionService.toAiMessages(any())).thenReturn(
                new ArrayList<>(List.of(AiMessage.builder().role("user").content("Please continue").build())));
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("ai-agent/issue-42"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        String response = """
                ```json
                {
                  "summary": "Follow-up",
                  "runTools": [
                    {"id": "mcp-1", "tool": "mcp:github:list_issues", "args": [{"owner":"tmseidel","repo":"ai-git-bot"}]},
                    {"id": "file-1", "tool": "patch-file", "args": ["README.md", "old", "new"]},
                    {"id": "val-1", "tool": "mvn", "args": ["compile"]}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt())).thenReturn(response);

        when(mcpOrchestrationService.isMcpTool(any(McpToolCatalog.class), eq("mcp:github:list_issues"))).thenReturn(true);
        when(mcpOrchestrationService.executeTool(any(), any(), eq("mcp:github:list_issues"), anyList()))
                .thenReturn(new ToolResult(false, 1, "", "cursor pagination required"));

        when(toolExecutionService.executeFileTool(eq(FAKE_WORKSPACE), eq("patch-file"), anyList()))
                .thenReturn(new ToolResult(true, 0, "File patched", ""));
        when(toolExecutionService.executeTool(eq(FAKE_WORKSPACE), eq("mvn"), anyList()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        when(workspaceService.commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(true);

        serviceWithMcp.handleIssueComment(payload);

        verify(workspaceService).commitAndPush(eq(FAKE_WORKSPACE), eq("ai-agent/issue-42"),
                anyString(), anyString(), anyString(), eq(false));
    }

    // ---- helpers ----

    private WebhookPayload createCommentPayload(String commentBody) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(100L);
        comment.setBody(commentBody);
        payload.setComment(comment);

        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(42L);
        issue.setTitle("Add new feature X");
        issue.setBody("Please implement feature X");
        payload.setIssue(issue);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }

    private WebhookPayload createIssuePayload() {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("assigned");

        WebhookPayload.Owner assignee = new WebhookPayload.Owner();
        assignee.setLogin("ai_bot");

        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(42L);
        issue.setTitle("Add new feature X");
        issue.setBody("Please implement feature X that does Y and Z");
        issue.setAssignee(assignee);
        payload.setIssue(issue);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }

    private IssueImplementationService createServiceWithMcp() {
        AgentConfigProperties agentConfig = new AgentConfigProperties();
        agentConfig.setEnabled(true);
        agentConfig.setMaxFiles(10);
        agentConfig.setBranchPrefix("ai-agent/");
        McpToolCatalog catalog = new McpToolCatalog(List.of(new McpToolDefinition(
                "github",
                "list_issues",
                "list_issues",
                "List issues",
                Map.of(),
                "mcp:github:list_issues"
        )));
        IssueImplementationContext context = new IssueImplementationContext(
                repositoryClient, aiClient, null, null, mcpOrchestrationService, null, catalog, null);
        return new IssueImplementationService(context, promptService, agentConfig,
                sessionService, toolExecutionService, toolCatalog, workspaceService);
    }

    private IssueImplementationService createServiceWithBotUsername(String botUsername) {
        AgentConfigProperties agentConfig = new AgentConfigProperties();
        agentConfig.setEnabled(true);
        agentConfig.setMaxFiles(10);
        agentConfig.setBranchPrefix("ai-agent/");
        IssueImplementationContext context = new IssueImplementationContext(
                repositoryClient, aiClient, null, botUsername, null, null, McpToolCatalog.empty(), null);
        return new IssueImplementationService(context, promptService, agentConfig,
                sessionService, toolExecutionService, toolCatalog, workspaceService);
    }
}
