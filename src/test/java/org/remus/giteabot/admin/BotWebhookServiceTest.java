package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.session.SessionService;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotWebhookServiceTest {

    @Mock private AiClientFactory aiClientFactory;
    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private PromptService promptService;
    @Mock private SessionService sessionService;
    @Mock private AgentConfigProperties agentConfig;
    @Mock private AgentSessionService agentSessionService;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private WorkspaceService workspaceService;
    @Mock private BotService botService;
    @Mock private McpOrchestrationService mcpOrchestrationService;
    @Mock private McpToolSelectionService mcpToolSelectionService;
    @Mock private org.remus.giteabot.systemsettings.BotToolSelectionService botToolSelectionService;
    @Mock private RepositoryApiClient repositoryApiClient;
    @Mock private AiClient aiClient;
    @Mock private org.remus.giteabot.prworkflow.PrWorkflowOrchestrator prWorkflowOrchestrator;
    @Mock private org.remus.giteabot.prworkflow.review.CodeReviewServiceFactory codeReviewServiceFactory;
    @Mock private org.remus.giteabot.prworkflow.e2e.E2eTestPrCloseHandler e2eTestPrCloseHandler;
    @Mock private org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler e2eTestSlashCommandHandler;
    @Mock private org.remus.giteabot.prworkflow.config.WorkflowSelectionService workflowSelectionService;

    private BotWebhookService botWebhookService;

    @BeforeEach
    void setUp() {
        // Real catalog – classification taxonomy is no longer mocked through TES.
        org.remus.giteabot.agent.tools.ToolCatalog toolCatalog =
                new org.remus.giteabot.agent.tools.ToolCatalog(new AgentConfigProperties());
        botWebhookService = new BotWebhookService(aiClientFactory, giteaClientFactory,
                promptService, agentConfig,
                agentSessionService, toolExecutionService, toolCatalog, workspaceService, botService,
                mcpOrchestrationService, mcpToolSelectionService, botToolSelectionService,
                prWorkflowOrchestrator, codeReviewServiceFactory, e2eTestPrCloseHandler,
                e2eTestSlashCommandHandler, workflowSelectionService);
        lenient().when(mcpOrchestrationService.discoverTools(any())).thenReturn(McpToolCatalog.empty());
        lenient().when(mcpToolSelectionService.filterCatalogForPrompt(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // Built-in tool whitelist: tests don't exercise the gating layer, so return
        // null (= unrestricted) to keep the historic test surface.
        lenient().when(botToolSelectionService.allowedBuiltinTools(any())).thenReturn(null);
        // M1: the CodeReviewService construction was extracted into
        // CodeReviewServiceFactory. Reproduce the legacy behaviour (real
        // CodeReviewService built from mocked AI/Git/session deps) here so
        // the existing handlePrComment / handleBotCommand routing tests
        // keep observing the same downstream side-effects on `sessionService`.
        lenient().when(codeReviewServiceFactory.create(any(Bot.class)))
                .thenAnswer(invocation -> {
                    Bot b = invocation.getArgument(0);
                    return new org.remus.giteabot.review.CodeReviewService(
                            repositoryApiClient, aiClient, sessionService,
                            b.getUsername(), new ReviewConfigProperties(),
                            "system-prompt:" + b.getSystemPrompt().getId(),
                            b.getSystemPrompt().getReviewSystemPrompt());
                });
        // Step 7.2 — provide a real BudgetConfig so production code that reads
        // agentConfig.getBudget().getMaxTokensPerCall() does not NPE on the mock.
        AgentConfigProperties.BudgetConfig budget = new AgentConfigProperties.BudgetConfig();
        budget.setMaxTokensPerCall(4096);
        lenient().when(agentConfig.getBudget()).thenReturn(budget);
        lenient().when(agentConfig.getCritic()).thenReturn(new AgentConfigProperties.CriticConfig());
    }

    // ---- isBotUser tests ----

    @Test
    void isBotUser_senderMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("ai_bot");
        payload.setSender(sender);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_senderDoesNotMatch_returnsFalse() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_nullUsername_returnsFalse() {
        Bot bot = createBot("test-bot", null, false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_commentUserMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin("ai_bot");
        comment.setUser(user);
        payload.setComment(comment);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    // ---- getBotAlias tests ----

    @Test
    void getBotAlias_returnsMentionFormat() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        assertEquals("@ai_bot", botWebhookService.getBotAlias(bot));
    }

    @Test
    void getBotAlias_nullUsername_returnsEmpty() {
        Bot bot = createBot("test-bot", null, false);
        assertEquals("", botWebhookService.getBotAlias(bot));
    }

    @Test
    void isPullRequestAuthor_commentUserMatchesPrAuthor_returnsTrue() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setUser(owner("tom"));
        payload.setPullRequest(pr);
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setUser(owner("tom"));
        payload.setComment(comment);

        assertTrue(botWebhookService.isPullRequestAuthor(payload));
    }

    @Test
    void isPullRequestAuthor_commentUserDiffersFromPrAuthor_returnsFalse() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setUser(owner("tom"));
        payload.setPullRequest(pr);
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setUser(owner("sara"));
        payload.setComment(comment);

        assertFalse(botWebhookService.isPullRequestAuthor(payload));
    }

    @Test
    void isReviewAgainRequest_acceptsRepeatCodeReviewIntentFromAuthor() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setUser(owner("tom"));
        payload.setPullRequest(pr);
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setUser(owner("tom"));
        comment.setBody("@ai_bot repeat the code-review");
        payload.setComment(comment);

        assertTrue(botWebhookService.isReviewAgainRequest(payload, "@ai_bot"));
        assertTrue(botWebhookService.isReviewAgainRequestFromPullRequestAuthor(payload, "@ai_bot"));
    }

    // ---- handlePrComment routing tests ----

    @Test
    void writerBot_ignoresPullRequestReviewEvent() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);

        botWebhookService.reviewPullRequest(bot, new WebhookPayload());

        verify(aiClientFactory, never()).getClient(any());
        verify(giteaClientFactory, never()).getApiClient(any());
    }

    @Test
    void writerBot_ignoresPullRequestClosedEvent() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);

        botWebhookService.handlePrClosed(bot, new WebhookPayload());

        verify(aiClientFactory, never()).getClient(any());
        verify(giteaClientFactory, never()).getApiClient(any());
    }

    @Test
    void writerBot_assignedToIssueCreatesImprovedIssueWhenReady() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096))).thenReturn("""
                {"qualityAssessment":"Missing acceptance criteria","revisedIssueDraft":"## Goal\\nDo something testable","assumptions":[],"openQuestions":[],"readyToCreate":true}
                """);
        when(repositoryApiClient.createIssue(eq("Test"), eq("my-repo"), eq("AI Created Issue: Vague issue"), any()))
                .thenReturn(99L);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(repositoryApiClient).createIssue(eq("Test"), eq("my-repo"),
                eq("AI Created Issue: Vague issue"), org.mockito.ArgumentMatchers.contains("Originates from #12"));
        verify(agentSessionService).setGeneratedIssueNumber(session, 99L);
    }

    @Test
    void writerBot_assignedToIssueCreatesImprovedIssueWhenAiAddsIntroTextBeforeJson() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096))).thenReturn("""
                Now I have enough context. Let me look at the exact filtering logic in the webhook handlers.

                {"qualityAssessment":"Missing acceptance criteria","revisedIssueDraft":"## Goal\\nDo something testable","assumptions":[],"openQuestions":[],"readyToCreate":true}
                """);
        when(repositoryApiClient.createIssue(eq("Test"), eq("my-repo"), eq("AI Created Issue: Vague issue"), any()))
                .thenReturn(99L);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(repositoryApiClient).createIssue(eq("Test"), eq("my-repo"),
                eq("AI Created Issue: Vague issue"), org.mockito.ArgumentMatchers.contains("Originates from #12"));
        verify(repositoryApiClient, never()).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("I need the issue author to answer these questions"));
        verify(agentSessionService).setGeneratedIssueNumber(session, 99L);
    }

    @Test
    void writerBot_concurrentAssignmentDuplicateSessionDoesNotStartSecondAgent() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom"))
                .thenThrow(new DataIntegrityViolationException("duplicate session"));

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(workspaceService, never()).prepareWorkspace(any(), any(), any(), any(), any());
        verify(repositoryApiClient, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void writerBot_assignmentKickoffFailureResetsSessionFromUpdating() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        doThrow(new RuntimeException("kickoff comment failed"))
                .doNothing()
                .when(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L), any());

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.UPDATING);
        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.FAILED);
        verify(workspaceService, never()).prepareWorkspace(any(), any(), any(), any(), any());
    }

    @Test
    void writerBot_commentWhenSessionCannotBeClaimedDoesNotStartSecondAgent() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssueCommentPayload("Test", "my-repo", 12L,
                "Vague issue", "Do something", "tom", "More details");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");
        session.setSessionType(AgentSession.AgentSessionType.WRITER);
        session.setIssueAuthorUsername("tom");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.of(session));
        when(agentSessionService.claimSessionForUpdate("Test", "my-repo", 12L,
                AgentSession.AgentSessionType.WRITER)).thenReturn(Optional.empty());

        botWebhookService.handleIssueComment(bot, payload);

        verify(workspaceService, never()).prepareWorkspace(any(), any(), any(), any(), any());
        verify(repositoryApiClient, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void writerBot_branchSwitcherRequestSwitchesWorkspaceBeforeContextTools() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");
        Path workspace = Path.of("/tmp/writer-test-workspace");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(workspace));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096)))
                .thenReturn("""
                        {"qualityAssessment":"Needs repo context","requestTools":[{"id":"1","tool":"branch-switcher","args":["develop"]},{"id":"2","tool":"cat","args":["README.md"]}],"readyToCreate":false}
                        """)
                .thenReturn("""
                        {"qualityAssessment":"Ready","revisedIssueDraft":"## Goal\\nDo something testable","assumptions":[],"openQuestions":[],"readyToCreate":true}
                        """);
        when(toolExecutionService.executeContextTool(workspace, "branch-switcher", java.util.List.of("develop")))
                .thenReturn(new ToolResult(true, 0, "Switched workspace branch to: develop", ""));
        when(toolExecutionService.executeContextTool(workspace, "cat", java.util.List.of("README.md")))
                .thenReturn(new ToolResult(true, 0, "README contents", ""));
        when(repositoryApiClient.createIssue(eq("Test"), eq("my-repo"), eq("AI Created Issue: Vague issue"), any()))
                .thenReturn(99L);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(toolExecutionService).executeContextTool(workspace, "branch-switcher", java.util.List.of("develop"));
        verify(toolExecutionService).executeContextTool(workspace, "cat", java.util.List.of("README.md"));
        verify(agentSessionService).setBranchName(session, "develop");
        verify(agentSessionService).setGeneratedIssueNumber(session, 99L);
    }

    @Test
    void writerBot_existingCodingSessionPostsCloneNotice() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession codingSession = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L))
                .thenReturn(Optional.of(codingSession));

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("Please clone the issue"));
        verify(agentSessionService, never()).createSession(any(), any(), any(), any(), any(), any());
        verify(repositoryApiClient, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void writerBot_createIssueReturnsNullMarksSessionFailed() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096))).thenReturn("""
                {"qualityAssessment":"Missing acceptance criteria","revisedIssueDraft":"## Goal\\nDo something testable","assumptions":[],"openQuestions":[],"readyToCreate":true}
                """);
        when(repositoryApiClient.createIssue(eq("Test"), eq("my-repo"), eq("AI Created Issue: Vague issue"), any()))
                .thenReturn(null);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.FAILED);
        verify(agentSessionService, never()).setGeneratedIssueNumber(any(), any());
        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("creating it failed"));
    }

    @Test
    void writerBot_assignmentFailurePostsVisibleErrorComment() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096)))
                .thenThrow(new RuntimeException("simulated loop failure"));

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.FAILED);
        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("simulated loop failure"));
    }

    @Test
    void writerBot_clarifyingQuestionsResetSessionToWaiting() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096))).thenReturn("""
                {"qualityAssessment":"Missing target behavior","clarifyingQuestions":["What should happen?"],"readyToCreate":false}
                """);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.IN_PROGRESS);
        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("What should happen?"));
        verify(repositoryApiClient, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void writerBot_contextRoundLimitResetsSessionAndPostsNotice() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");
        Path workspace = Path.of("/tmp/writer-test-workspace");
        String contextRequest = """
                {"qualityAssessment":"Needs context","requestTools":[{"id":"1","tool":"cat","args":["README.md"]}],"readyToCreate":true}
                """;

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(workspace));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096)))
                .thenReturn(contextRequest, contextRequest, contextRequest,
                        contextRequest, contextRequest, contextRequest);
        when(toolExecutionService.executeContextTool(workspace, "cat", java.util.List.of("README.md")))
                .thenReturn(new ToolResult(true, 0, "README contents", ""));

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.IN_PROGRESS);
        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("I need more context"));
        verify(repositoryApiClient, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void writerBot_canContinueThroughFourContextRoundsBeforeCreatingIssue() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssuePayload("Test", "my-repo", 12L, "Vague issue", "Do something");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");
        Path workspace = Path.of("/tmp/writer-test-workspace");
        String contextRequest = """
                {"qualityAssessment":"Needs context","requestTools":[{"id":"1","tool":"cat","args":["README.md"]}],"readyToCreate":false}
                """;
        String finalResponse = """
                {"qualityAssessment":"Ready","revisedIssueDraft":"## Goal\\nDo something testable","assumptions":[],"openQuestions":[],"readyToCreate":true}
                """;

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(repositoryApiClient.getIssueDetails("Test", "my-repo", 12L))
                .thenReturn(java.util.Map.of("user", java.util.Map.of("login", "tom")));
        when(agentSessionService.createSession("Test", "my-repo", 12L, "Vague issue",
                AgentSession.AgentSessionType.WRITER, "tom")).thenReturn(session);
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(workspace));
        when(repositoryApiClient.getRepositoryTree("Test", "my-repo", "main")).thenReturn(java.util.List.of());
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096)))
                .thenReturn(contextRequest, contextRequest, contextRequest, contextRequest, finalResponse);
        when(toolExecutionService.executeContextTool(workspace, "cat", java.util.List.of("README.md")))
                .thenReturn(new ToolResult(true, 0, "README contents", ""));
        when(repositoryApiClient.createIssue(eq("Test"), eq("my-repo"), eq("AI Created Issue: Vague issue"), any()))
                .thenReturn(99L);

        botWebhookService.handleIssueAssigned(bot, payload);

        verify(repositoryApiClient).createIssue(eq("Test"), eq("my-repo"),
                eq("AI Created Issue: Vague issue"), org.mockito.ArgumentMatchers.contains("Originates from #12"));
        verify(repositoryApiClient, never()).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("I need more context"));
    }

    @Test
    void writerBot_followUpFailurePostsVisibleErrorCommentAndResetsSession() {
        Bot bot = createBot("writer", "writer_bot", false);
        bot.setBotType(BotType.WRITER);
        WebhookPayload payload = buildIssueCommentPayload("Test", "my-repo", 12L,
                "Vague issue", "Do something", "tom", "More details");
        AgentSession session = new AgentSession("Test", "my-repo", 12L, "Vague issue");
        session.setSessionType(AgentSession.AgentSessionType.WRITER);
        session.setIssueAuthorUsername("tom");

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.of(session));
        when(agentSessionService.claimSessionForUpdate("Test", "my-repo", 12L,
                AgentSession.AgentSessionType.WRITER)).thenReturn(Optional.of(session));
        when(repositoryApiClient.getDefaultBranch("Test", "my-repo")).thenReturn("main");
        when(workspaceService.prepareWorkspace(eq("Test"), eq("my-repo"), eq("main"), any(), any()))
                .thenReturn(WorkspaceResult.success(Path.of("/tmp/writer-test-workspace")));
        when(agentSessionService.toAiMessages(session)).thenReturn(java.util.List.of());
        when(aiClient.chat(any(), any(), startsWith("Writer prompt"), any(), eq(4096)))
                .thenThrow(new RuntimeException("follow-up failure"));

        botWebhookService.handleIssueComment(bot, payload);

        verify(agentSessionService).setStatus(session, AgentSession.AgentSessionStatus.IN_PROGRESS);
        verify(repositoryApiClient).postIssueComment(eq("Test"), eq("my-repo"), eq(12L),
                org.mockito.ArgumentMatchers.contains("follow-up failure"));
    }

    @Test
    void codingBot_issueComment_appliesMcpToolWhitelistBeforeAgentHandling() {
        Bot bot = createBot("coder", "coder_bot", true);
        WebhookPayload payload = buildIssueCommentPayload("Test", "my-repo", 12L,
                "Implement feature", "Body", "tom", "Please continue");
        McpConfiguration mcpConfiguration = new McpConfiguration();
        mcpConfiguration.setId(77L);
        mcpConfiguration.setName("GitHub MCP");
        mcpConfiguration.setJsonContent("[{\"name\":\"github\",\"url\":\"https://example.test/mcp\"}]");
        bot.setMcpConfiguration(mcpConfiguration);
        McpToolCatalog discovered = new McpToolCatalog(java.util.List.of(
                new org.remus.giteabot.mcp.McpToolDefinition("github", "search", null, null,
                        java.util.Map.of(), "mcp:github:search")
        ));

        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        when(mcpOrchestrationService.discoverTools(mcpConfiguration)).thenReturn(discovered);
        when(agentSessionService.getSessionByIssue("Test", "my-repo", 12L)).thenReturn(Optional.empty());
        when(agentSessionService.getSessionByPr("Test", "my-repo", 12L)).thenReturn(Optional.empty());

        botWebhookService.handleIssueComment(bot, payload);

        verify(mcpToolSelectionService).filterCatalogForPrompt(mcpConfiguration, discovered);
    }

    @Nested
    class HandlePrCommentTests {

        private static final String OWNER = "Test";
        private static final String REPO = "my-repo";
        private static final long PR_NUMBER = 140L;
        private static final long COMMENT_ID = 1055L;

        private WebhookPayload prCommentPayload;

        @BeforeEach
        void setUpPayload() {
            prCommentPayload = buildPrCommentPayload(OWNER, REPO, PR_NUMBER, COMMENT_ID,
                    "@claude_bot please do something");
            // Both factories return the shared repository client mock
            lenient().when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
            lenient().when(aiClientFactory.getClient(any())).thenReturn(null); // not reached in these tests
        }

        /** For tests where the agent path is taken, stub workspace to fail quickly. */
        private void stubAgentPath(AgentSession session) {
            lenient().when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                    .thenReturn(org.remus.giteabot.agent.validation.WorkspaceResult.failure("routing test"));
        }

        @Test
        void agentSessionFoundByIssueNumber_agentEnabled_routesToAgent() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            stubAgentPath(session);

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Agent path: AgentSessionService.setStatus(UPDATING) must be called
            verify(agentSessionService).setStatus(any(AgentSession.class),
                    eq(AgentSession.AgentSessionStatus.UPDATING));
            // Review path's SessionService.getOrCreateSession must NOT be called
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
        }

        @Test
        void agentSessionFoundByPrNumber_fallback_agentEnabled_routesToAgent() {
            // First lookup (by issue/PR number) finds nothing – second lookup (by PR number) finds session
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            stubAgentPath(session);

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // getSessionByPr is called in handlePrComment AND again inside handleIssueComment
            verify(agentSessionService, atLeastOnce()).getSessionByPr(OWNER, REPO, PR_NUMBER);
            verify(agentSessionService).setStatus(any(AgentSession.class),
                    eq(AgentSession.AgentSessionStatus.UPDATING));
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
        }

        @Test
        void noAgentSession_routesToCodeReviewHandler() {
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Review path: SessionService.getOrCreateSession must be called
            verify(sessionService).getOrCreateSession(OWNER, REPO, PR_NUMBER, "system-prompt:1");
            // Agent path's setStatus must NOT be called
            verify(agentSessionService, never()).setStatus(any(), any());
        }

        @Test
        void agentSessionExists_butAgentDisabled_routesToCodeReviewHandler() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));

            // bot.isAgentEnabled() = false
            botWebhookService.handlePrComment(createBot("bot", "claude_bot", false), prCommentPayload);

            verify(sessionService).getOrCreateSession(OWNER, REPO, PR_NUMBER, "system-prompt:1");
            verify(agentSessionService, never()).setStatus(any(), any());
        }

        @Test
        void noAgentSession_issueNumberLookupCalledBeforePrNumberLookup() {
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Verify lookup order: issue first, PR second
            var inOrder = inOrder(agentSessionService);
            inOrder.verify(agentSessionService).getSessionByIssue(OWNER, REPO, PR_NUMBER);
            inOrder.verify(agentSessionService).getSessionByPr(OWNER, REPO, PR_NUMBER);
        }

        @Test
        void agentSessionFoundByIssueNumber_prLookupIsSkipped() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            stubAgentPath(session);

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Short-circuit: PR-number lookup must not be called
            verify(agentSessionService, never()).getSessionByPr(any(), any(), any());
        }

        @Test
        void agentSessionExists_humanCommentOnBotCreatedPr_routesToAgent() {
            // Bot created the PR (PR author is the bot username), human follows up with a comment.
            // The author check must NOT block this because the coding agent IS the PR author.
            WebhookPayload botAuthoredPrPayload = buildPrCommentPayload(OWNER, REPO, PR_NUMBER, COMMENT_ID,
                    "@claude_bot look at this");
            // Set PR author to the bot's username
            botAuthoredPrPayload.getPullRequest().setUser(owner("claude_bot"));
            // Comment is from a human
            botAuthoredPrPayload.getComment().getUser().setLogin("human_reviewer");
            botAuthoredPrPayload.getSender().setLogin("human_reviewer");

            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            stubAgentPath(session);

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), botAuthoredPrPayload);

            // Must route to the agent even though commenter != PR author
            verify(agentSessionService).setStatus(any(AgentSession.class),
                    eq(AgentSession.AgentSessionStatus.UPDATING));
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
        }

        @Test
        void noAgentSession_nonAuthorComment_isIgnored() {
            // No agent session and commenter is NOT the PR author → code-review path rejects it.
            WebhookPayload nonAuthorPayload = buildPrCommentPayload(OWNER, REPO, PR_NUMBER, COMMENT_ID,
                    "@claude_bot please review");
            nonAuthorPayload.getComment().getUser().setLogin("other_user");
            nonAuthorPayload.getSender().setLogin("other_user");

            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), nonAuthorPayload);

            // Non-author on code-review path → no command handled
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
            verify(agentSessionService, never()).setStatus(any(), any());
        }

        @Test
        void noAgentSession_botWithoutReviewWorkflow_doesNotFallIntoCodeReview() {
            // Bot only has e2e-test configured (no review). An unrecognised comment must
            // NOT trigger the code-review path — instead the bot replies that it does
            // not understand the command.
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            Bot bot = createBotWithWorkflows("e2e-bot", "claude_bot", true,
                    java.util.List.of("e2e-test"));

            botWebhookService.handlePrComment(bot, prCommentPayload);

            // Code-review path MUST NOT be entered
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
            // Unrecognised-command reply MUST be posted
            verify(repositoryApiClient).postIssueComment(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                    org.mockito.ArgumentMatchers.contains("did not understand"));
        }

        @Test
        void noAgentSession_botWithReviewWorkflow_stillRoutesToCodeReview() {
            // Sanity: an explicit configuration that DOES include review keeps the
            // existing code-review behaviour.
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            Bot bot = createBotWithWorkflows("review-bot", "claude_bot", true,
                    java.util.List.of("review", "e2e-test"));

            botWebhookService.handlePrComment(bot, prCommentPayload);

            verify(sessionService).getOrCreateSession(OWNER, REPO, PR_NUMBER, "system-prompt:1");
            verify(repositoryApiClient, never()).postIssueComment(any(), any(), any(),
                    org.mockito.ArgumentMatchers.contains("did not understand"));
        }
    }

    // ---- helpers ----

    private Bot createBot(String name, String username, boolean agentEnabled) {
        Bot bot = new Bot();
        bot.setName(name);
        bot.setUsername(username);
        bot.setAgentEnabled(agentEnabled);
        SystemPrompt systemPrompt = new SystemPrompt();
        systemPrompt.setId(1L);
        systemPrompt.setReviewSystemPrompt("Review prompt");
        systemPrompt.setIssueAgentSystemPrompt("Agent prompt");
        systemPrompt.setWriterAgentSystemPrompt("Writer prompt");
        bot.setSystemPrompt(systemPrompt);
        return bot;
    }

    /**
     * Builds a Bot with an explicit {@link org.remus.giteabot.prworkflow.config.WorkflowConfiguration}
     * whose enabled keys are stubbed on {@link #workflowSelectionService}. Used to verify
     * the workflow-guard logic in {@code BotWebhookService} which must refuse to fall
     * into the code-review path when the {@code review} workflow is not enabled.
     */
    private Bot createBotWithWorkflows(String name, String username, boolean agentEnabled,
                                       java.util.List<String> enabledWorkflowKeys) {
        Bot bot = createBot(name, username, agentEnabled);
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(42L);
        cfg.setName(name + "-cfg");
        bot.setWorkflowConfiguration(cfg);
        lenient().when(workflowSelectionService.enabledWorkflowKeys(42L))
                .thenReturn(enabledWorkflowKeys);
        return bot;
    }

    private WebhookPayload.Owner owner(String login) {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin(login);
        return owner;
    }


    private AgentSession agentSession(String owner, String repo, long issueNumber) {
        AgentSession s = new AgentSession(owner, repo, issueNumber, "test issue");
        s.setPrNumber(issueNumber); // PR created from this issue
        return s;
    }

    /**
     * Builds a {@link WebhookPayload} that simulates a comment on a PR discussion thread,
     * matching the Gitea webhook structure observed in production.
     */
    private WebhookPayload buildPrCommentPayload(String owner, String repo,
                                                  long prNumber, long commentId,
                                                  String commentBody) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        // Sender
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("tom");
        payload.setSender(sender);

        // Repository
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        repository.setFullName(owner + "/" + repo);
        WebhookPayload.Owner repoOwner = new WebhookPayload.Owner();
        repoOwner.setLogin(owner);
        repository.setOwner(repoOwner);
        payload.setRepository(repository);

        // Issue (the PR as seen through Gitea's issue model)
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(prNumber);
        issue.setTitle("Some PR");
        issue.setBody("");
        WebhookPayload.IssuePullRequest issuePr = new WebhookPayload.IssuePullRequest();
        issuePr.setMerged(false);
        issue.setPullRequest(issuePr);
        payload.setIssue(issue);

        // Top-level pull_request (distinguishes PR comment from plain issue comment)
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        pr.setId(80L);
        pr.setState("open");
        pr.setUser(owner("tom"));
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/branch");
        pr.setHead(head);
        WebhookPayload.Head base = new WebhookPayload.Head();
        base.setRef("main");
        pr.setBase(base);
        payload.setPullRequest(pr);

        // Comment
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(commentId);
        comment.setBody(commentBody);
        WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
        commentUser.setLogin("tom");
        comment.setUser(commentUser);
        payload.setComment(comment);

        return payload;
    }

    private WebhookPayload buildIssuePayload(String owner, String repo,
                                             long issueNumber, String title, String body) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("assigned");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        repository.setFullName(owner + "/" + repo);
        WebhookPayload.Owner repoOwner = new WebhookPayload.Owner();
        repoOwner.setLogin(owner);
        repository.setOwner(repoOwner);
        payload.setRepository(repository);

        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(issueNumber);
        issue.setTitle(title);
        issue.setBody(body);
        WebhookPayload.Owner assignee = new WebhookPayload.Owner();
        assignee.setLogin("writer_bot");
        issue.setAssignee(assignee);
        payload.setIssue(issue);

        return payload;
    }

    private WebhookPayload buildIssueCommentPayload(String owner, String repo,
                                                    long issueNumber, String title, String body,
                                                    String commenter, String commentBody) {
        WebhookPayload payload = buildIssuePayload(owner, repo, issueNumber, title, body);
        payload.setAction("created");
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(42L);
        comment.setBody(commentBody);
        WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
        commentUser.setLogin(commenter);
        comment.setUser(commentUser);
        payload.setComment(comment);
        return payload;
    }
}
