package org.remus.giteabot.agent.writerimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.AgentErrorNotificationService;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolPromptRenderer;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class WriterAgentService {

    private static final String WRITER_PROMPT_NAME = "writer";
    /**
     * Default values, overridable via {@code AgentConfigProperties.writer.*}. They
     * are kept as constants so unit tests that construct the service without a
     * fully-populated config still get the historical behaviour.
     */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    private static final int DEFAULT_MAX_INITIAL_TREE_FILES = 100;

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final WorkspaceService workspaceService;
    private final String writerAgentSystemPrompt;
    private final String botUsername;
    private final McpToolCatalog mcpToolCatalog;
    private final AgentErrorNotificationService errorNotificationService;
    private final BranchSwitcher branchSwitcher;
    private final AgentToolRouter toolRouter;
    private final WriterPromptBuilder promptBuilder = new WriterPromptBuilder();
    private final WriterResponseParser responseParser = new WriterResponseParser();
    private final McpToolPromptRenderer mcpToolPromptRenderer = new McpToolPromptRenderer();
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler(mcpToolPromptRenderer);

    public WriterAgentService(RepositoryApiClient repositoryClient,
                              AiClient aiClient,
                              PromptService promptService,
                              AgentConfigProperties agentConfig,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              WorkspaceService workspaceService,
                              String writerAgentSystemPrompt,
                              String botUsername) {
        this(repositoryClient, aiClient, promptService, agentConfig, sessionService,
                toolExecutionService, workspaceService, writerAgentSystemPrompt, botUsername,
                null, null, McpToolCatalog.empty());
    }

    public WriterAgentService(RepositoryApiClient repositoryClient,
                              AiClient aiClient,
                              PromptService promptService,
                              AgentConfigProperties agentConfig,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              WorkspaceService workspaceService,
                              String writerAgentSystemPrompt,
                              String botUsername,
                              McpOrchestrationService mcpOrchestrationService,
                              McpConfiguration mcpConfiguration,
                              McpToolCatalog mcpToolCatalog) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.workspaceService = workspaceService;
        this.writerAgentSystemPrompt = writerAgentSystemPrompt;
        this.botUsername = botUsername;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.errorNotificationService = new AgentErrorNotificationService(repositoryClient);
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, mcpOrchestrationService,
                mcpConfiguration, this.mcpToolCatalog, repositoryClient);
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();
        String issueRef = normalizeBranchRef(payload.getIssue().getRef());

        if (!isAssignedToThisBot(payload)) {
            log.debug("Ignoring writer assignment for issue #{} because assignee does not match bot '{}'",
                    issueNumber, botUsername);
            return;
        }

        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            if (existingSession.get().getSessionType() != AgentSession.AgentSessionType.WRITER) {
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Technical Writer**: A coding-agent session already exists for this issue. "
                                + "Please clone the issue if you want the writer agent to draft a separate improved issue.");
            }
            log.info("Session already exists for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }

        String issueAuthor = findIssueAuthor(owner, repo, issueNumber);
        AgentSession session = null;
        String baseBranch = issueRef != null && !issueRef.isBlank()
                ? issueRef : repositoryClient.getDefaultBranch(owner, repo);
        Path workspaceDir = null;
        try {
            session = sessionService.createSession(owner, repo, issueNumber, issueTitle,
                    AgentSession.AgentSessionType.WRITER, issueAuthor);
            sessionService.setBranchName(session, baseBranch);
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: I've been assigned and will review this issue for completeness.");

            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch, repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();
            String treeContext = promptBuilder.buildTreeContext(
                    repositoryClient.getRepositoryTree(owner, repo, baseBranch), maxInitialTreeFiles());
            runWriterLoop(session, owner, repo, issueNumber, workspaceDir,
                    promptBuilder.buildInitialPrompt(issueNumber, issueTitle, issueBody, treeContext));
        } catch (DataIntegrityViolationException e) {
            log.info("Writer session was created concurrently for issue #{} in {}/{}", issueNumber, owner, repo);
        } catch (Exception e) {
            log.error("Writer failed while handling assignment for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.FAILED, e);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    public void handleIssueComment(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            log.debug("No writer session found for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }
        AgentSession session = sessionOpt.get();
        if (session.getSessionType() != AgentSession.AgentSessionType.WRITER) {
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: A coding-agent session already exists for this issue. "
                            + "Please clone the issue if you want the writer agent to draft a separate improved issue.");
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.ISSUE_CREATED) {
            log.debug("Writer session already created an issue for #{}", issueNumber);
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
            log.debug("Writer session already running for #{}", issueNumber);
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.FAILED) {
            log.debug("Writer session already failed for #{}", issueNumber);
            return;
        }
        if (!isIssueAuthor(owner, repo, issueNumber, payload)) {
            log.debug("Ignoring non-author writer follow-up on issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }
        Optional<AgentSession> claimedSession = sessionService.claimSessionForUpdate(
                owner, repo, issueNumber, AgentSession.AgentSessionType.WRITER);
        if (claimedSession.isEmpty()) {
            log.debug("Writer session for issue #{} in {}/{} is already claimed or complete",
                    issueNumber, owner, repo);
            return;
        }
        session = claimedSession.get();
        Path workspaceDir = null;
        try {
            String baseBranch = resolveBaseBranch(owner, repo, payload, session);
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch, repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();
            runWriterLoop(session, owner, repo, issueNumber, workspaceDir,
                    promptBuilder.buildContinuationPrompt(payload.getComment().getBody()));
        } catch (Exception e) {
            log.error("Writer failed while handling follow-up for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.IN_PROGRESS, e);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    private void runWriterLoop(AgentSession session, String owner, String repo,
                               Long issueNumber, Path workspaceDir, String userMessage) {
        WriterAgentStrategy strategy = new WriterAgentStrategy(
                resolveWriterSystemPrompt(),
                promptBuilder,
                responseParser,
                sessionService,
                repositoryClient,
                branchSwitcher,
                toolRouter,
                mcpToolCatalog,
                maxToolRounds());
        // The historic loop ran for-each `round in 0..maxToolRounds` (inclusive), i.e. one extra
        // iteration beyond the context-round limit so the AI gets a chance to produce a
        // terminal answer after exhausting context. Mirror that by setting the loop's hard
        // cap to maxToolRounds + 1.
        AgentBudget budget = new AgentBudget(
                maxToolRounds() + 1, maxToolRounds(), 0, agentConfig.getBudget().getMaxTokensPerCall());
        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(
                session, owner, repo, issueNumber, workspaceDir, session.getBranchName());
        loop.run(ctx, userMessage + "\n\n" + outputContract(), strategy);
    }

    private String normalizeBranchRef(String ref) {
        return BranchRefs.normalize(ref);
    }



    private boolean isIssueAuthor(String owner, String repo, Long issueNumber, WebhookPayload payload) {
        String commenter = payload.getComment() != null && payload.getComment().getUser() != null
                ? payload.getComment().getUser().getLogin() : null;
        if (commenter == null || commenter.isBlank()) {
            return false;
        }
        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isPresent() && sessionOpt.get().getIssueAuthorUsername() != null) {
            return commenter.equalsIgnoreCase(sessionOpt.get().getIssueAuthorUsername());
        }
        String issueAuthor = findIssueAuthor(owner, repo, issueNumber);
        return commenter.equalsIgnoreCase(issueAuthor);
    }

    private boolean isAssignedToThisBot(WebhookPayload payload) {
        if (botUsername == null || botUsername.isBlank() || payload.getIssue() == null) {
            return false;
        }
        if (payload.getIssue().getAssignee() != null
                && botUsername.equalsIgnoreCase(payload.getIssue().getAssignee().getLogin())) {
            return true;
        }
        if (payload.getIssue().getAssignees() != null) {
            return payload.getIssue().getAssignees().stream()
                    .anyMatch(assignee -> assignee != null
                            && botUsername.equalsIgnoreCase(assignee.getLogin()));
        }
        return false;
    }

    private String findIssueAuthor(String owner, String repo, Long issueNumber) {
        Map<String, Object> details = repositoryClient.getIssueDetails(owner, repo, issueNumber);
        for (String key : List.of("user", "author")) {
            Object value = details.get(key);
            if (value instanceof Map<?, ?> userMap) {
                String identity = extractUserIdentity(userMap);
                if (identity != null) {
                    return identity;
                }
            }
        }
        return null;
    }

    private String extractUserIdentity(Map<?, ?> userMap) {
        for (String key : List.of("login", "username", "name")) {
            Object value = userMap.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }


    private String resolveBaseBranch(String owner, String repo, WebhookPayload payload, AgentSession session) {
        if (session.getBranchName() != null && !session.getBranchName().isBlank()) {
            return session.getBranchName();
        }
        String issueRef = payload.getIssue() != null ? normalizeBranchRef(payload.getIssue().getRef()) : null;
        return issueRef != null && !issueRef.isBlank() ? issueRef : repositoryClient.getDefaultBranch(owner, repo);
    }

    private String resolveWriterSystemPrompt() {
        String basePrompt;
        if (writerAgentSystemPrompt != null && !writerAgentSystemPrompt.isBlank()) {
            basePrompt = writerAgentSystemPrompt;
        } else {
            basePrompt = promptService.getSystemPrompt(WRITER_PROMPT_NAME);
        }
        // Drive the prompt mode purely off the configured client (i.e. the operator's
        // `use_legacy_tool_calling` toggle). The writer strategy advertises native
        // function-calling whenever the client supports it, so the prompt and the
        // transport stay in sync.
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        return systemPromptAssembler.assemble(basePrompt, mcpToolCatalog, mode,
                org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind.WRITER_AGENT);
    }

    private String outputContract() {
        return """
                
                Return only JSON in this shape:
                {
                  "qualityAssessment": "short assessment",
                  "requestFiles": ["path/to/file"],
                  "requestTools": [{"id": "uuid", "tool": "get-issue", "args": ["123"]}],
                  "clarifyingQuestions": ["question for the issue author"],
                  "revisedIssueDraft": "final markdown issue draft",
                  "assumptions": ["assumption"],
                  "openQuestions": ["remaining non-critical question"],
                  "readyToCreate": true
                }
                Available writer tools: get-issue, search-issues, branch-switcher, rg, ripgrep, grep, find, cat, git-log, git-blame, tree.
                %s
                Search-tool args use the shape [pattern, path?, flags?]. Common flags like -i, -n, -l and --include=*.java are supported.
                `find` supports both [glob, path?] and shell-like forms such as ["src/main/java", "-name", "*.java"].
                For alternation in rg/ripgrep/grep patterns, use `|` (not `\\|`).
                You may use requestFiles or read-only repository requestTools when existing issue or repository context is needed before asking or finalizing.
                If you need another base branch, request `branch-switcher` first and wait for its result before requesting files or search results from that branch.
                Do not request repository write tools, file mutation tools, or build/validation tools.
                If critical information is missing, set readyToCreate=false and include clarifyingQuestions.
                If no critical questions remain, set readyToCreate=true and include revisedIssueDraft.
                """.formatted(mcpToolPromptRenderer.render(mcpToolCatalog));
    }

    private void handleWriterFailure(AgentSession session, String owner, String repo,
                                     Long issueNumber, AgentSession.AgentSessionStatus targetStatus,
                                     Exception e) {
        if (session != null) {
            sessionService.setStatus(session, targetStatus);
        }
        errorNotificationService.postInternalErrorComment(owner, repo, issueNumber,
                "AI Technical Writer",
                "Please try again or mention me again with any additional context.", e);
    }

    private int maxToolRounds() {
        if (agentConfig == null || agentConfig.getWriter() == null) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
        int configured = agentConfig.getWriter().getMaxToolRounds();
        return configured > 0 ? configured : DEFAULT_MAX_TOOL_ROUNDS;
    }

    private int maxInitialTreeFiles() {
        if (agentConfig == null || agentConfig.getWriter() == null) {
            return DEFAULT_MAX_INITIAL_TREE_FILES;
        }
        int configured = agentConfig.getWriter().getMaxInitialTreeFiles();
        return configured > 0 ? configured : DEFAULT_MAX_INITIAL_TREE_FILES;
    }
}
