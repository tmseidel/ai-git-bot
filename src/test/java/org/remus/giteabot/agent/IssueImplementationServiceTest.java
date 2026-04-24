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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IssueImplementationServiceTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    @Mock
    private AiClient aiClient;

    @Mock
    private PromptService promptService;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private ToolExecutionService toolExecutionService;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private DiffApplyService diffApplyService;

    private AgentConfigProperties agentConfig;

    private IssueImplementationService service;

    @BeforeEach
    void setUp() {
        agentConfig = new AgentConfigProperties();
        agentConfig.setEnabled(true);
        agentConfig.setMaxFiles(10);
        agentConfig.setBranchPrefix("ai-agent/");
        service = new IssueImplementationService(repositoryClient, aiClient, promptService, agentConfig,
                sessionService, toolExecutionService, workspaceService, diffApplyService);
    }

    @Test
    void handleIssueAssigned_successfulFlow() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");

        String aiResponse = """
                ```json
                {
                  "summary": "Implemented the feature",
                  "fileChanges": [
                    {
                      "path": "src/Feature.java",
                      "operation": "CREATE",
                      "content": "public class Feature {}"
                    }
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt())).thenReturn(aiResponse);
        when(repositoryClient.createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"))).thenReturn(1L);

        service.handleIssueAssigned(payload);

        verify(repositoryClient).createBranch("testowner", "testrepo", "ai-agent/issue-42", "main");
        verify(repositoryClient).createOrUpdateFile(eq("testowner"), eq("testrepo"), eq("src/Feature.java"),
                eq("public class Feature {}"), anyString(), eq("ai-agent/issue-42"), isNull());
        verify(repositoryClient).createPullRequest(eq("testowner"), eq("testrepo"), anyString(), anyString(),
                eq("ai-agent/issue-42"), eq("main"));
        // Should post at least 2 comments: initial progress + success
        verify(repositoryClient, atLeast(2)).postComment(eq("testowner"), eq("testrepo"), eq(42L), anyString());
    }

    @Test
    void handleIssueAssigned_exceedsMaxFiles_postsWarning() {
        agentConfig.setMaxFiles(1);
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main")).thenReturn(List.of());
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");

        String aiResponse = """
                ```json
                {
                  "summary": "Too many changes",
                  "fileChanges": [
                    {"path": "a.java", "operation": "CREATE", "content": "A"},
                    {"path": "b.java", "operation": "CREATE", "content": "B"}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt())).thenReturn(aiResponse);

        service.handleIssueAssigned(payload);

        // Should not create branch or PR
        verify(repositoryClient, never()).createBranch(any(), any(), any(), any());
        verify(repositoryClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        // Should post warning about max files
        verify(repositoryClient, atLeast(1)).postComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("maximum allowed"));
    }

    @Test
    void handleIssueAssigned_aiReturnsInvalid_postsFailure() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main")).thenReturn(List.of());
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt())).thenReturn("I don't know how to do this");

        service.handleIssueAssigned(payload);

        verify(repositoryClient, never()).createBranch(any(), any(), any(), any());
        // Should post a comment about inability to generate a plan
        verify(repositoryClient, atLeast(1)).postComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("unable to generate"));
    }

    @Test
    void handleIssueAssigned_apiError_cleansUpBranch() {
        WebhookPayload payload = createIssuePayload();

        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(repositoryClient.getRepositoryTree("testowner", "testrepo", "main")).thenReturn(List.of());
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");

        String aiResponse = """
                ```json
                {
                  "summary": "Implemented feature",
                  "fileChanges": [
                    {"path": "src/Feature.java", "operation": "CREATE", "content": "class Feature {}"}
                  ]
                }
                ```
                """;
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt())).thenReturn(aiResponse);
        doNothing().when(repositoryClient).createBranch(any(), any(), any(), any());
        doThrow(new RuntimeException("API error")).when(repositoryClient)
                .createOrUpdateFile(any(), any(), any(), any(), any(), any(), any());

        service.handleIssueAssigned(payload);

        // Branch should be cleaned up
        verify(repositoryClient).deleteBranch("testowner", "testrepo", "ai-agent/issue-42");
        // Should post failure comment
        verify(repositoryClient, atLeast(1)).postComment(eq("testowner"), eq("testrepo"), eq(42L),
                contains("failed"));
    }

    @Test
    void handleIssueComment_multipleFileRequestRounds_fetchesAllRequestedFiles() {
        WebhookPayload payload = createCommentPayload("Please also update the config");

        AgentSession session = new AgentSession("testowner", "testrepo", 42L, "Add new feature X");
        session.setBranchName("ai-agent/issue-42");
        session.setPrNumber(1L);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);

        when(sessionService.getSessionByIssue("testowner", "testrepo", 42L))
                .thenReturn(Optional.of(session));
        when(repositoryClient.getDefaultBranch("testowner", "testrepo")).thenReturn("main");
        when(promptService.getSystemPrompt("agent")).thenReturn("You are an agent");
        when(sessionService.toAiMessages(any())).thenReturn(
                new ArrayList<>(List.of(AiMessage.builder().role("user").content("Please also update the config").build())));

        // First AI response: request files (round 1)
        String firstResponse = """
                ```json
                {
                  "summary": "Need to see config",
                  "requestFiles": ["src/Config.java"]
                }
                ```
                """;
        // Second AI response: request more files (round 2)
        String secondResponse = """
                ```json
                {
                  "summary": "Need to see model too",
                  "requestFiles": ["src/Model.java"]
                }
                ```
                """;
        // Third AI response: actual implementation
        String thirdResponse = """
                ```json
                {
                  "summary": "Updated config",
                  "fileChanges": [
                    {
                      "path": "src/Config.java",
                      "operation": "UPDATE",
                      "content": "class Config { int x; }"
                    }
                  ]
                }
                ```
                """;

        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(firstResponse, secondResponse, thirdResponse);

        when(repositoryClient.getFileContent("testowner", "testrepo", "src/Config.java", "ai-agent/issue-42"))
                .thenReturn("class Config {}");
        when(repositoryClient.getFileContent("testowner", "testrepo", "src/Model.java", "ai-agent/issue-42"))
                .thenReturn("class Model {}");
        when(repositoryClient.getFileSha("testowner", "testrepo", "src/Config.java", "ai-agent/issue-42"))
                .thenReturn("abc123");

        service.handleIssueComment(payload);

        // Verify file contents were fetched for both rounds
        verify(repositoryClient).getFileContent("testowner", "testrepo", "src/Config.java", "ai-agent/issue-42");
        verify(repositoryClient).getFileContent("testowner", "testrepo", "src/Model.java", "ai-agent/issue-42");
        // Verify AI was called 3 times (initial + 2 file request rounds)
        verify(aiClient, times(3)).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
        // Verify file change was applied
        verify(repositoryClient).createOrUpdateFile(eq("testowner"), eq("testrepo"), eq("src/Config.java"),
                eq("class Config { int x; }"), anyString(), eq("ai-agent/issue-42"), eq("abc123"));
    }

    @Test
    void handleIssueComment_contextToolRequest_executesToolAndContinues() {
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

        String firstResponse = """
                ```json
                {
                  "summary": "Need to search for usages",
                  "requestTools": [
                    {"tool": "rg", "args": ["ConfigService", "src"]}
                  ]
                }
                ```
                """;
        String secondResponse = """
                ```json
                {
                  "summary": "Updated config after tracing usages",
                  "fileChanges": [
                    {
                      "path": "src/Config.java",
                      "operation": "UPDATE",
                      "content": "class Config { boolean enabled = true; }"
                    }
                  ]
                }
                ```
                """;

        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(firstResponse, secondResponse);
        when(workspaceService.prepareWorkspace(eq("testowner"), eq("testrepo"), eq("ai-agent/issue-42"),
                eq(List.of()), isNull(), isNull()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/context-workspace"), List.of()));
        when(toolExecutionService.executeContextTool(Path.of("/tmp/context-workspace"), "rg",
                List.of("ConfigService", "src")))
                .thenReturn(new ToolResult(true, 0, "src/Config.java:12: ConfigService configService", ""));
        when(repositoryClient.getFileSha("testowner", "testrepo", "src/Config.java", "ai-agent/issue-42"))
                .thenReturn("abc123");

        service.handleIssueComment(payload);

        verify(toolExecutionService).executeContextTool(Path.of("/tmp/context-workspace"), "rg",
                List.of("ConfigService", "src"));
        verify(workspaceService).cleanupWorkspace(Path.of("/tmp/context-workspace"));
        verify(repositoryClient).createOrUpdateFile(eq("testowner"), eq("testrepo"), eq("src/Config.java"),
                eq("class Config { boolean enabled = true; }"), anyString(), eq("ai-agent/issue-42"), eq("abc123"));
    }

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
        issue.setBody("Please implement feature X that does Y and Z");
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
