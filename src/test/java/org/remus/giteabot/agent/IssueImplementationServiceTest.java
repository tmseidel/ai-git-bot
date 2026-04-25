package org.remus.giteabot.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IssueImplementationServiceTest {

    @Mock private RepositoryApiClient repositoryClient;
    @Mock private AiClient aiClient;
    @Mock private PromptService promptService;
    @Mock private AgentSessionService sessionService;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private WorkspaceService workspaceService;

    private IssueImplementationService service;

    private static final Path FAKE_WORKSPACE = Path.of("/tmp/test-workspace");

    @BeforeEach
    void setUp() {
        AgentConfigProperties agentConfig = new AgentConfigProperties();
        agentConfig.setEnabled(true);
        agentConfig.setMaxFiles(10);
        agentConfig.setBranchPrefix("ai-agent/");
        service = new IssueImplementationService(repositoryClient, aiClient, promptService, agentConfig,
                sessionService, toolExecutionService, workspaceService);

        // Default stubs – marked lenient so tests that don't reach buildToolsInfo() don't fail
        lenient().when(toolExecutionService.getAvailableTools()).thenReturn(List.of("mvn"));
        lenient().when(toolExecutionService.getAvailableFileTools()).thenReturn(List.of("write-file", "patch-file", "mkdir", "delete-file"));
        lenient().when(toolExecutionService.getAvailableContextTools()).thenReturn(List.of("branch-switcher", "rg", "cat", "find", "tree"));
        // isValidationTool is the authoritative check for validation tools; delegate to getAvailableTools()
        lenient().when(toolExecutionService.isValidationTool(anyString()))
                .thenAnswer(inv -> Objects.equals("mvn", inv.getArgument(0)));
    }

    // ---- handleIssueAssigned tests ----

    @Test
    void handleIssueAssigned_successfulFlow_writesFileAndValidates() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("main"),
                isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(FAKE_WORKSPACE));

        // AI context response (no tool requests, just requestFiles)
        String contextResponse = """
                ```json
                {"summary": "Need context", "requestFiles": []}
                ```
                """;
        // AI implementation response with write-file + mvn
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
                .thenReturn(contextResponse, implResponse);

        // write-file is a file tool → executeFileTool
        when(toolExecutionService.isFileTool("write-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("write-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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
        verify(repositoryClient, atLeast(2)).postComment(eq("testowner"), eq("testrepo"), eq(42L), anyString());
    }

    @Test
    void handleIssueAssigned_workspacePreparationFails_postsError() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                .thenReturn(WorkspaceResult.failure("git clone failed"));

        service.handleIssueAssigned(payload);

        verify(repositoryClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        verify(repositoryClient, atLeast(1)).postComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("Failed to prepare workspace"));
    }

    @Test
    void handleIssueAssigned_contextRequestsBranchSwitcher_usesSwitchedBaseBranch() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "develop"))
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

        when(toolExecutionService.isFileTool("write-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("write-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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

        when(toolExecutionService.isFileTool("write-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("write-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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

        String initialContextResponse = """
                ```json
                {"summary": "Need initial context", "requestFiles": []}
                ```
                """;
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
                .thenReturn(initialContextResponse, followUpContextResponse, implResponse);

        when(repositoryClient.getFileContent("testowner", "testrepo", "pom.xml", "release/1.x"))
                .thenReturn("<project />");

        when(toolExecutionService.isFileTool("write-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("write-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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

        String contextResponse = """
                ```json
                {"summary": "Need context", "requestFiles": []}
                ```
                """;
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
                .thenReturn(contextResponse, implResponse);

        when(toolExecutionService.isFileTool("write-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("write-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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
        verify(repositoryClient, atLeast(1)).postComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("pushing the branch failed"));
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
        when(toolExecutionService.isFileTool("patch-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("patch-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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

        when(toolExecutionService.isFileTool("patch-file")).thenReturn(true);
        when(toolExecutionService.isFileTool("mvn")).thenReturn(false);
        when(toolExecutionService.isContextTool("mvn")).thenReturn(false);
        when(toolExecutionService.isSilentTool("patch-file")).thenReturn(true);
        when(toolExecutionService.isSilentTool("mvn")).thenReturn(false);
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
}
