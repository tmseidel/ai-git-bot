package org.remus.giteabot.issueworkflow.triage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IssueTriageService}: the agent loop wiring (read-only
 * context gathering before the routing decision), untrusted-output
 * validation, and the comment/assign execution paths.
 */
@ExtendWith(MockitoExtension.class)
class IssueTriageServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PARAMS = Map.of(
            "systemPrompt", "ROUTING PROMPT",
            "assignees", "Alice,Bob,claude-bot,issue_hemingway");

    @Mock
    private AiClientFactory aiClientFactory;
    @Mock
    private GiteaClientFactory giteaClientFactory;
    @Mock
    private AgentSessionService sessionService;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private McpOrchestrationService mcpOrchestrationService;
    @Mock
    private McpToolSelectionService mcpToolSelectionService;
    @Mock
    private BotToolSelectionService botToolSelectionService;
    @Mock
    private AiClient aiClient;
    @Mock
    private RepositoryApiClient repoClient;

    private IssueTriageService service;
    private Bot bot;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() {
        service = new IssueTriageService(aiClientFactory, giteaClientFactory, sessionService,
                toolExecutionService, new ToolCatalog(new AgentConfigProperties()), workspaceService,
                mcpOrchestrationService, mcpToolSelectionService, botToolSelectionService,
                new AgentConfigProperties());
        lenient().when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        lenient().when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        lenient().when(workspaceService.prepareWorkspace(eq(repoClient), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/triage-ws")));
        lenient().when(repoClient.getDefaultBranch("owner", "repo")).thenReturn("main");
        lenient().when(repoClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "src/App.java")));
        lenient().when(sessionService.toAiMessages(any())).thenReturn(List.of());
        lenient().when(mcpOrchestrationService.discoverTools(any())).thenReturn(McpToolCatalog.empty());
        lenient().when(mcpToolSelectionService.filterCatalogForPrompt(any(), any()))
                .thenReturn(McpToolCatalog.empty());
        // A null whitelist means "no built-in tool filtering" (same as the test
        // paths of the coding/writer agents); Mockito would otherwise default
        // to an empty set, which blocks every tool.
        lenient().when(botToolSelectionService.allowedBuiltinTools(any())).thenReturn(null);

        bot = new Bot();
        bot.setName("Triage Bot");
        bot.setUsername("triage-bot");

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("repo");
        repository.setOwner(owner);
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(42L);
        issue.setTitle("Button color is wrong");
        issue.setBody("The submit button should be blue.");
        payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setIssue(issue);
    }

    // ---- native tool-calling mode ----

    @Test
    void nativeMode_validToolCall_postsReasonThenAssignsCanonicalName() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"alice\",\"reason\":\"Frontend styling fix\"}"));

        service.triage(bot, payload, PARAMS);

        InOrder order = inOrder(repoClient);
        order.verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("Frontend styling fix"));
        order.verify(repoClient).assignIssue("owner", "repo", 42L, "Alice");
    }

    @Test
    void nativeMode_advertisesReadOnlyToolsPlusAssignIssue() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"none\",\"reason\":\"Unclear\"}"));

        service.triage(bot, payload, PARAMS);

        ArgumentCaptor<List<ToolDescriptor>> tools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatWithTools(anyList(), anyString(), tools.capture(), prompt.capture(),
                isNull(), anyInt());
        List<String> names = tools.getValue().stream().map(ToolDescriptor::name).toList();
        assertTrue(names.contains("assign_issue"));
        assertTrue(names.contains("cat"));
        assertTrue(names.contains("rg"));
        assertFalse(names.contains("write-file"));
        String schema = tools.getValue().stream()
                .filter(d -> "assign_issue".equals(d.name())).findFirst().orElseThrow()
                .jsonSchema().toString();
        assertTrue(schema.contains("Alice"));
        assertTrue(schema.contains("none"));
        assertTrue(prompt.getValue().startsWith("ROUTING PROMPT"));
    }

    @Test
    void nativeMode_gathersContextBeforeDeciding() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("rg", "{\"args\":[\"submit button\"]}"))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Alice\",\"reason\":\"Frontend fix\"}"));
        when(toolExecutionService.executeContextTool(any(), eq("rg"), anyList()))
                .thenReturn(new ToolResult(true, 0, "src/ui/SubmitButton.java", ""));

        service.triage(bot, payload, PARAMS);

        verify(toolExecutionService).executeContextTool(any(), eq("rg"), anyList());
        ArgumentCaptor<List<AiMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(aiClient, times(2)).chatWithTools(history.capture(), anyString(), anyList(),
                anyString(), isNull(), anyInt());
        List<AiMessage> secondRoundHistory = history.getAllValues().get(1);
        assertTrue(secondRoundHistory.stream().anyMatch(m -> "tool".equals(m.getRole())
                && m.getToolResult() != null && m.getToolResult().contains("SubmitButton")));
        verify(repoClient).assignIssue("owner", "repo", 42L, "Alice");
    }

    @Test
    void nativeMode_invalidDecisionIsCorrectedWithinTheLoop() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Mallory\",\"reason\":\"Taking over\"}"))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Bob\",\"reason\":\"Backend work\"}"));

        service.triage(bot, payload, PARAMS);

        verify(aiClient, times(2)).chatWithTools(anyList(), anyString(), anyList(), anyString(),
                isNull(), anyInt());
        verify(repoClient).assignIssue("owner", "repo", 42L, "Bob");
    }

    @Test
    void nativeMode_repeatedlyInvalidDecision_failsWithErrorComment() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Mallory\",\"reason\":\"Taking over\"}"));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("No assignment was made"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void nativeMode_budgetExhausted_failsWithErrorComment() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        // The model keeps gathering context and never commits to a decision.
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("rg", "{\"args\":[\"button\"]}"));
        when(toolExecutionService.executeContextTool(any(), eq("rg"), anyList()))
                .thenReturn(new ToolResult(true, 0, "hit", ""));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("No assignment was made"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- JSON-only (legacy) mode ----

    @Test
    void jsonMode_contextRoundThenTerminalJson_assigns() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"requestTools\":[{\"id\":\"1\",\"tool\":\"rg\",\"args\":[\"submit\"]}]}")
                .thenReturn("{\"assignment\":\"Bob\",\"reason\":\"Backend API change\"}");
        when(toolExecutionService.executeContextTool(any(), eq("rg"), anyList()))
                .thenReturn(new ToolResult(true, 0, "src/api/SubmitEndpoint.java", ""));

        service.triage(bot, payload, PARAMS);

        verify(aiClient, times(2)).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
        verify(toolExecutionService).executeContextTool(any(), eq("rg"), anyList());
        verify(repoClient).assignIssue("owner", "repo", 42L, "Bob");
    }

    @Test
    void jsonMode_none_postsReasonWithoutAssigning() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"none\",\"reason\":\"Cannot determine the domain\"}");

        service.triage(bot, payload, PARAMS);

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("Cannot determine the domain"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void jsonMode_unparseableResponse_isRejectedWithErrorComment() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("I have no idea what to do here.");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("No assignment was made"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- validation (shared between modes) ----

    @Test
    void selfAssignment_isRejectedToPreventLoops() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"triage-bot\",\"reason\":\"I should do it myself\"}");
        Map<String, Object> params = Map.of(
                "systemPrompt", "ROUTING PROMPT", "assignees", "Alice,triage-bot");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, params));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void multiLineReason_isRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"Alice\",\"reason\":\"line one\\nline two\"}");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- execution failure paths ----

    @Test
    void nonAssignableAccount_postsErrorCommentAndFails() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Alice\",\"reason\":\"Frontend work\"}"));
        doThrow(new IllegalArgumentException("User 'Alice' is not assignable on owner/repo"))
                .when(repoClient).assignIssue("owner", "repo", 42L, "Alice");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        // Reason comment stays visible, error comment explains the failure.
        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L), contains("Frontend work"));
        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("could not assign to `Alice`"));
    }

    @Test
    void workspacePreparationFails_postsErrorCommentAndFails() {
        when(workspaceService.prepareWorkspace(eq(repoClient), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(WorkspaceResult.failure("disk full"));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("could not prepare the read-only repository context"));
        verifyNoInteractions(aiClient);
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void missingIssueInPayload_isANoOp() {
        WebhookPayload empty = new WebhookPayload();

        service.triage(bot, empty, PARAMS);

        verifyNoInteractions(aiClient, repoClient);
    }

    private static ChatTurn toolTurn(String toolName, String argsJson) {
        return new ChatTurn("", List.of(toolCall(toolName, argsJson)), StopReason.END_TURN, 0, 0);
    }

    private static ToolCall toolCall(String toolName, String argsJson) {
        JsonNode args;
        try {
            args = JSON.readTree(argsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ToolCall("call-1", toolName, args, Map.of());
    }
}
