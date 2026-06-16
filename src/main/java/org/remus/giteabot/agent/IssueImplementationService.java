package org.remus.giteabot.agent;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.critic.CriticAgent;
import org.remus.giteabot.agent.critic.ReflectionResult;
import org.remus.giteabot.agent.issueimpl.AgentPromptBuilder;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.issueimpl.CodingAgentStrategy;
import org.remus.giteabot.agent.issueimpl.IssueNotificationService;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.shared.ToolFailures;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
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
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core issue-implementation (agent) business logic.  Not a Spring-managed
 * singleton — instances are created per-bot by
 * {@link org.remus.giteabot.admin.BotWebhookService} with the bot's own
 * {@link AiClient} and {@link RepositoryApiClient}.
 * <p>
 * All file changes (create, patch, delete) are now performed via tool requests
 * inside the cloned workspace.  After successful validation the workspace is
 * committed and pushed — no separate {@code createOrUpdateFile} API calls are
 * needed.
 */
@Slf4j
public class IssueImplementationService {

    private static final String AGENT_PROMPT_NAME = "agent";
    /** Git author identity used for automated commits. */
    private static final String GIT_AUTHOR_NAME  = "AI Agent";
    private static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final String issueAgentSystemPrompt;
    private final String botUsername;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpConfiguration mcpConfiguration;
    private final McpToolCatalog mcpToolCatalog;
    private final SystemPromptAssembler systemPromptAssembler;
    private final java.util.Set<String> allowedBuiltinTools;

    // Extracted helpers
    private final AiResponseParser responseParser;
    private final AgentPromptBuilder promptBuilder;
    private final IssueNotificationService notificationService;
    private final AgentErrorNotificationService errorNotificationService;
    private final BranchSwitcher branchSwitcher;
    private final AgentToolRouter toolRouter;
    private final CriticAgent criticAgent;
    private final int contextWindowTokens;

    public IssueImplementationService(IssueImplementationContext context,
                                      AgentCollaborators collaborators) {
        AgentConfigProperties agentConfig = collaborators.agentConfig();
        ToolExecutionService toolExecutionService = collaborators.toolExecutionService();
        ToolCatalog toolCatalog = collaborators.toolCatalog();
        this.repositoryClient = context.repositoryClient();
        this.aiClient = context.aiClient();
        this.promptService = collaborators.promptService();
        this.agentConfig = agentConfig;
        this.sessionService = collaborators.sessionService();
        this.toolExecutionService = toolExecutionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = collaborators.workspaceService();
        this.issueAgentSystemPrompt = context.issueAgentSystemPrompt();
        this.botUsername = context.botUsername();
        this.mcpOrchestrationService = context.mcpOrchestrationService();
        this.mcpConfiguration = context.mcpConfiguration();
        this.mcpToolCatalog = context.mcpToolCatalog();
        this.systemPromptAssembler = new SystemPromptAssembler();
        this.allowedBuiltinTools = context.allowedBuiltinTools();
        this.contextWindowTokens = context.contextWindowTokens();

        this.responseParser = new AiResponseParser();
        this.promptBuilder = new AgentPromptBuilder(agentConfig != null ? agentConfig.getContext() : null);
        this.notificationService = new IssueNotificationService(this.repositoryClient, responseParser, toolCatalog);
        this.errorNotificationService = new AgentErrorNotificationService(this.repositoryClient);
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog, this.mcpOrchestrationService,
                this.mcpConfiguration, this.mcpToolCatalog, this.repositoryClient, this.allowedBuiltinTools);
        this.criticAgent = new CriticAgent(
                agentConfig != null ? agentConfig.getCritic() : null,
                agentConfig != null ? agentConfig.getBudget() : null,
                null);
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner     = payload.getRepository().getOwner().getLogin();
        String repo      = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long   issueNumber  = payload.getIssue().getNumber();
        String issueTitle   = payload.getIssue().getTitle();
        String issueBody    = payload.getIssue().getBody();
        String issueRef     = normalizeBranchRef(payload.getIssue().getRef());

        log.info("Starting implementation for issue #{} '{}' in {}", issueNumber, issueTitle, repoFullName);

        // Check if there's already a session for this issue
        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            if (existingSession.get().getSessionType() != AgentSession.AgentSessionType.CODING) {
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: A technical-writer session already exists for this issue. "
                                + "Please clone the issue if you want the coding agent to implement it separately.");
            }
            log.info("Session already exists for issue #{}, skipping initial implementation", issueNumber);
            return;
        }

        AgentSession session = sessionService.createSession(owner, repo, issueNumber, issueTitle);
        Path workspaceDir = null;

        try {
            String issueCommentsContext = fetchIssueCommentsContext(owner, repo, issueNumber);

            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: I've been assigned to this issue. Analyzing repository structure...");

            // Determine base branch
            String baseBranch;
            if (issueRef != null && !issueRef.isBlank()) {
                baseBranch = issueRef;
                log.info("Using issue branch '{}' as base for issue #{}", baseBranch, issueNumber);
            } else {
                baseBranch = repositoryClient.getDefaultBranch(owner, repo);
                log.info("No issue branch set, using default branch '{}' for issue #{}", baseBranch, issueNumber);
            }

            // Clone repository once — all operations happen in this workspace
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();

            // Fetch repository tree for context
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, baseBranch);
            String treeContext  = promptBuilder.buildTreeContext(tree);
            String systemPrompt = resolveAgentSystemPrompt();

            // Single implementation loop — symmetric to WriterAgentService#handleIssueAssigned.
            // The previous "Step 1" (separate one-shot AI call to ask which files were needed)
            // has been folded into the AgentLoop: the CodingAgentStrategy already supports
            // context-only rounds (`requestFiles`/`requestTools` in legacy mode, or `cat`/`rg`
            // tool calls in native mode), so a dedicated pre-loop turn was redundant and
            // diverged from the writer flow.
            String implementationPrompt = promptBuilder.buildImplementationPromptWithContext(
                    issueTitle, issueBody, issueCommentsContext, treeContext,
                    "(no files preloaded — request more via `requestFiles`/`requestTools` or "
                            + "use context tools like `cat`, `rg`, `find`, `tree` if you need to inspect specific files)");

            // Tool listings are now part of the system prompt (rendered dynamically
            // by SystemPromptAssembler in LEGACY mode / advertised via the function-calling
            // API in NATIVE mode), so the implementation prompt no longer duplicates them.
            log.info("Starting implementation loop for issue #{}", issueNumber);
            ToolImplementationLoopResult implementationResult = runToolImplementationLoop(
                    session, implementationPrompt, systemPrompt, workspaceDir, owner, repo, issueNumber, baseBranch);
            boolean implementationSucceeded = implementationResult.success();
            baseBranch = implementationResult.selectedBranch();

            if (!implementationSucceeded) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        """
                        🤖 **AI Agent**: I was unable to produce a valid implementation for this issue. \
                        The issue may be too complex or ambiguous for automated implementation.

                        You can mention me in a comment to provide more details or clarification.""");
                return;
            }

            // Commit and push to new feature branch
            String branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
            sessionService.setBranchName(session, branchName);

            // Step 7.3 — optional Critic / Reflection step. With critic.enabled=false
            // (default) this short-circuits without an LLM call.
            ImplementationPlan plannedPlan = getLastPlanFromSession(session);
            ReflectionResult reflection = criticAgent.review(
                    issueTitle, issueBody,
                    plannedPlan != null ? plannedPlan.getSummary() : null,
                    workspaceService.diffStat(workspaceDir),
                    aiClient);
            if (reflection.outcome() == ReflectionResult.Outcome.ABORT) {
                log.warn("Critic aborted implementation for issue #{}: {}",
                        issueNumber, reflection.feedback());
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent (Critic)**: I reviewed my own implementation and decided not to "
                                + "open a PR. Reason: " + reflection.feedback());
                return;
            }
            if (reflection.outcome() == ReflectionResult.Outcome.ITERATE) {
                log.info("Critic requested another iteration for issue #{}: {}",
                        issueNumber, reflection.feedback());
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent (Critic)**: My implementation needs another iteration. "
                                + "Mention me to retry. Feedback: " + reflection.feedback());
                return;
            }

            String commitMessage = String.format("agent: implement #%d - %s", issueNumber, issueTitle);
            boolean pushed = workspaceService.commitAndPush(workspaceDir, branchName, commitMessage,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, true);

            if (!pushed) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Implementation succeeded but pushing the branch failed. Please check the logs.");
                return;
            }

            // Create pull request
            ImplementationPlan lastPlan = getLastPlanFromSession(session);
            String prTitle = String.format("AI Agent: %s (fixes #%d)", issueTitle, issueNumber);
            String prBody  = promptBuilder.buildPrBody(issueNumber, lastPlan != null ? lastPlan
                    : ImplementationPlan.builder().summary("Automated implementation").build());
            Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                    branchName, baseBranch);

            sessionService.setPrNumber(session, prNumber);
            notificationService.postSuccessComment(owner, repo, issueNumber, lastPlan, prNumber);
            log.info("Successfully created PR #{} for issue #{} in {}", prNumber, issueNumber, repoFullName);

        } catch (Exception e) {
            log.error("Failed to implement issue #{} in {}: {}", issueNumber, repoFullName, e.getMessage(), e);
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
            errorNotificationService.postInternalErrorComment(owner, repo, issueNumber,
                    "AI Agent",
                    "Please try again or mention me again with more details.", e);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Unified tool-based implementation loop — used by both {@link #handleIssueAssigned} and
     * {@link #handleIssueComment}. The actual decision logic lives in
     * {@link CodingAgentStrategy}; this method just wires up the {@link AgentLoop}
     * with that strategy and the per-run context.
     *
     * @param userMessage the next user turn to kick off this loop (will be appended to the session)
     * @return success flag plus the branch finally selected for context lookups
     */
    private ToolImplementationLoopResult runToolImplementationLoop(
            AgentSession session, String userMessage, String systemPrompt,
            Path workspaceDir, String owner, String repo, Long issueNumber,
            String initialContextBranch) {

        CodingAgentStrategy strategy = new CodingAgentStrategy(
                systemPrompt,
                promptBuilder,
                responseParser,
                notificationService,
                sessionService,
                branchSwitcher,
                toolRouter,
                toolCatalog,
                workspaceService,
                agentConfig,
                mcpOrchestrationService,
                mcpToolCatalog,
                allowedBuiltinTools,
                this::fetchRequestedContext);

        // Step 7.2 — budgets are now sourced from BudgetConfig. Validation
        // sub-budget is still a hint (the strategy enforces it internally),
        // but the loop's hard cap derives from validation+tool+context rounds.
        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int maxRetries = budgetCfg.getMaxValidationRetries();
        int maxToolRounds = agentConfig.getValidation().getMaxToolExecutions();
        int hardCap = Math.max(budgetCfg.getMaxRounds(),
                maxRetries + maxToolRounds + budgetCfg.getMaxContextRounds() + 2 /* slack */);
        AgentBudget budget = new AgentBudget(hardCap, budgetCfg.getMaxContextRounds(),
                maxRetries, budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                contextWindowTokens, budgetCfg.getProactiveCompactionThreshold());
        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(
                session, owner, repo, issueNumber, workspaceDir, initialContextBranch);
        LoopOutcome outcome = loop.run(ctx, userMessage, strategy);
        return new ToolImplementationLoopResult(outcome.success(), outcome.selectedBranch());
    }

    /**
     * Handles a comment on an issue that mentions the bot.
     */
    public void handleIssueComment(WebhookPayload payload) {
        String owner       = payload.getRepository().getOwner().getLogin();
        String repo        = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long   issueNumber  = payload.getIssue().getNumber();
        Long   commentId    = payload.getComment().getId();
        String commentBody  = payload.getComment().getBody();

        log.info("Handling agent comment #{} on issue #{} in {}", commentId, issueNumber, repoFullName);

        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            log.debug("No agent session found for issue #{}, trying PR number lookup", issueNumber);
            sessionOpt = sessionService.getSessionByPr(owner, repo, issueNumber);
        }
        if (sessionOpt.isEmpty()) {
            log.info("No agent session found for issue/PR #{}, ignoring comment", issueNumber);
            return;
        }

        AgentSession session = sessionOpt.get();
        if (session.getSessionType() != AgentSession.AgentSessionType.CODING) {
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: This issue is currently owned by a technical-writer session. "
                            + "Please clone the issue if you want the coding agent to implement it separately.");
            return;
        }
        Path workspaceDir = null;

        try {
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);

            // Compact persisted history before starting a new agent run to prevent
            // unbounded growth across follow-up sessions.
            sessionService.compactContextWindow(session);

            String branchName    = session.getBranchName();
            String defaultBranch = repositoryClient.getDefaultBranch(owner, repo);
            String workingBranch = branchName != null ? branchName : defaultBranch;

            // Clone working branch into fresh workspace
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, workingBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = resolveAgentSystemPrompt();
            String issueCommentsContext = fetchIssueCommentsContext(owner, repo, issueNumber);
            String userMessage  = promptBuilder.buildContinuationPrompt(commentBody, issueCommentsContext);

            log.info("Requesting AI to continue implementation for issue #{}", issueNumber);
            // runToolImplementationLoop handles: AI call, context rounds, tool execution, retries
            ToolImplementationLoopResult implementationResult = runToolImplementationLoop(
                    session, userMessage, systemPrompt, workspaceDir, owner, repo, issueNumber, workingBranch);
            boolean success = implementationResult.success();
            String selectedContextBranch = implementationResult.selectedBranch();
            if (!success) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Validation failed and I couldn't fix the issues. " +
                        "Please check the tool output above and provide more guidance.");
                return;
            }

            // Commit and push
            boolean createNew = (branchName == null);
            if (createNew) {
                branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
                sessionService.setBranchName(session, branchName);
            }
            String commitMessage = String.format("agent: follow-up for #%d", issueNumber);
            boolean pushed = workspaceService.commitAndPush(workspaceDir, branchName, commitMessage,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, createNew);
            if (!pushed) {
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Tool execution succeeded but pushing changes failed.");
                return;
            }

            // Create PR if not yet existing
            if (session.getPrNumber() == null) {
                String prTitle = String.format("AI Agent: %s (fixes #%d)", session.getIssueTitle(), issueNumber);
                ImplementationPlan latestPlan = getLastPlanFromSession(session);
                String prBody = promptBuilder.buildPrBody(issueNumber, latestPlan != null ? latestPlan
                        : ImplementationPlan.builder().summary("Automated implementation").build());
                Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                        branchName, createNew ? selectedContextBranch : defaultBranch);
                sessionService.setPrNumber(session, prNumber);
            }

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
            notificationService.postFollowUpSuccessComment(owner, repo, issueNumber,
                    getLastPlanFromSession(session), session.getPrNumber());
            log.info("Successfully applied follow-up changes for issue #{}", issueNumber);

        } catch (Exception e) {
            log.error("Failed to handle comment on issue #{}: {}", issueNumber, e.getMessage(), e);
            errorNotificationService.postInternalErrorComment(owner, repo, issueNumber,
                    "AI Agent",
                    "Please try again or mention me again with more details.", e);
            if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
                sessionService.setStatus(session,
                        session.getPrNumber() != null ? AgentSession.AgentSessionStatus.PR_CREATED
                                                      : AgentSession.AgentSessionStatus.FAILED);
            }
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private String fetchIssueCommentsContext(String owner, String repo, Long issueNumber) {
        try {
            List<Map<String, Object>> issueComments = repositoryClient.getIssueComments(owner, repo, issueNumber);
            List<Map<String, Object>> humanIssueComments = filterBotAuthoredComments(issueComments);
            log.info("Fetched {} issue comments for issue #{} in {}/{}",
                    issueComments != null ? issueComments.size() : 0, issueNumber, owner, repo);
            if (issueComments != null && humanIssueComments.size() != issueComments.size()) {
                log.info("Excluded {} bot-authored issue comments from context for issue #{} in {}/{}",
                        issueComments.size() - humanIssueComments.size(), issueNumber, owner, repo);
            }
            return promptBuilder.buildIssueCommentsContext(humanIssueComments);
        } catch (Exception e) {
            log.warn("Failed to fetch issue comments for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage());
            return "Issue comments could not be loaded: " + e.getMessage();
        }
    }

    private List<Map<String, Object>> filterBotAuthoredComments(List<Map<String, Object>> issueComments) {
        if (issueComments == null || issueComments.isEmpty()) {
            return List.of();
        }
        if (botUsername == null || botUsername.isBlank()) {
            return issueComments;
        }
        return issueComments.stream()
                .filter(comment -> !botUsername.equalsIgnoreCase(extractCommentAuthor(comment)))
                .toList();
    }

    private String extractCommentAuthor(Map<String, Object> comment) {
        if (comment == null) {
            return "";
        }
        String user = extractActorName(comment.get("user"));
        if (!user.isBlank()) {
            return user;
        }
        return extractActorName(comment.get("author"));
    }

    private String extractActorName(Object actor) {
        if (actor instanceof String text) {
            return text;
        }
        if (actor instanceof Map<?, ?> actorMap) {
            for (String key : List.of("login", "username", "name", "display_name", "nickname")) {
                Object value = actorMap.get(key);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String resolveAgentSystemPrompt() {
        // Drive the prompt mode purely off the configured client (i.e. the operator's
        // `use_legacy_tool_calling` toggle). The coding strategy advertises native
        // function-calling whenever the client supports it, so the prompt and the
        // transport stay in sync without a separate static hint.
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        return resolveAgentSystemPrompt(mode);
    }

    /**
     * Variant used by Step 1 (one-shot file-context request) which always wants the
     * JSON-protocol guidance regardless of whether the implementation loop later runs
     * in native mode.
     */
    private String resolveAgentSystemPrompt(ToolingMode mode) {
        String basePrompt;
        if (issueAgentSystemPrompt != null && !issueAgentSystemPrompt.isBlank()) {
            basePrompt = issueAgentSystemPrompt;
        } else {
            basePrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);
        }
        return systemPromptAssembler.assemble(basePrompt, toolCatalog, allowedBuiltinTools,
                mcpToolCatalog, mode,
                org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind.ISSUE_AGENT);
    }

    /**
     * Fetches any additional repository context requested by the AI.
     * Context tools are run against the already-cloned {@code workspaceDir}.
     */
    private String fetchRequestedContext(String owner, String repo, String ref,
                                         List<String> filePaths,
                                         List<ImplementationPlan.ToolRequest> toolRequests,
                                         Path workspaceDir) {
        StringBuilder sb = new StringBuilder();

        if (filePaths != null && !filePaths.isEmpty()) {
            sb.append("## Requested Files\n");
            sb.append(fetchSpecificFiles(owner, repo, ref, filePaths));
        }

        String toolOutput = executeRequestedContextTools(workspaceDir, toolRequests);
        if (!toolOutput.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("## Repository Tool Results\n").append(toolOutput);
        }

        return sb.isEmpty() ? "No additional repository context could be retrieved." : sb.toString();
    }


    private String executeRequestedContextTools(Path workspaceDir,
                                                List<ImplementationPlan.ToolRequest> toolRequests) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int toolCount = 0;
        for (ImplementationPlan.ToolRequest toolRequest : toolRequests) {
            if (toolRequest == null || toolRequest.getTool() == null || toolRequest.getTool().isBlank()) {
                continue;
            }
            if (toolCount >= agentConfig.getBudget().getMaxContextToolRequestsPerRound()) {
                sb.append("\nAdditional tool requests were skipped after reaching the per-round limit of ")
                        .append(agentConfig.getBudget().getMaxContextToolRequestsPerRound()).append(".\n");
                break;
            }
            toolCount++;
            ToolResult result = isMcpTool(toolRequest.getTool())
                    ? mcpOrchestrationService.executeTool(mcpConfiguration, mcpToolCatalog,
                    toolRequest.getTool(), toolRequest.getArgs())
                    : toolExecutionService.executeContextTool(workspaceDir, toolRequest.getTool(), toolRequest.getArgs());
            sb.append("### `").append(toolRequest.getTool());
            if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
                sb.append(" ").append(String.join(" ", toolRequest.getArgs()));
            }
            sb.append("`\n");
            if (result.success()) {
                String output = result.output();
                sb.append(output == null || output.isBlank() ? "(no output)" : output).append("\n\n");
            } else {
                sb.append("Failed: ").append(ToolFailures.describe(result))
                        .append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * Fetches specific file contents from the repository.
     */
    private String fetchSpecificFiles(String owner, String repo, String ref, List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;

        for (String path : filePaths) {
            if (totalChars > agentConfig.getMaxFileContentChars()) {
                sb.append("\n(File context truncated due to size limits)\n");
                break;
            }
            try {
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
                if (content != null && !content.isEmpty()) {
                    sb.append("\n--- File: ").append(path).append(" ---\n");
                    sb.append(content).append("\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Normalizes a branch reference by removing the "refs/heads/" prefix if present.
     */
    private String normalizeBranchRef(String ref) {
        return BranchRefs.normalize(ref);
    }

    private boolean isMcpTool(String toolName) {
        return McpTools.isMcpTool(mcpOrchestrationService, mcpToolCatalog, toolName);
    }


    /** Result of the implementation loop including the final branch used for context lookups. */
    private record ToolImplementationLoopResult(boolean success, String selectedBranch) {
    }

    /**
     * Step 7.1 — retrieves the most recently parsed implementation plan from
     * the session row in O(1). Falls back to the legacy history-walk for old
     * sessions persisted before V11 (no {@code last_plan_json} populated) or
     * when the session itself is {@code null} (test scaffolding).
     */
    private ImplementationPlan getLastPlanFromSession(AgentSession session) {
        if (session == null) {
            return null;
        }
        String rawJson = session.getLastPlanJson();
        if (rawJson != null && !rawJson.isBlank()) {
            ImplementationPlan p = responseParser.parseAiResponse(rawJson);
            if (p != null) {
                return p;
            }
        }
        // Fallback for legacy sessions without persisted plan.
        List<AiMessage> messages = sessionService.toAiMessages(session);
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiMessage msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) {
                continue;
            }
            String content = msg.getContent();
            // Skip messages that obviously cannot contain a JSON plan. In native
            // tool-calling mode most assistant turns are plain reasoning text or
            // pure tool_call envelopes — running the parser on them would only
            // log "Could not extract JSON" warnings without ever returning a plan.
            if (content == null || content.isBlank()) {
                continue;
            }
            if (!content.contains("{")) {
                continue;
            }
            ImplementationPlan p = responseParser.parseAiResponse(content);
            if (p != null) {
                return p;
            }
        }
        return null;
    }
}
