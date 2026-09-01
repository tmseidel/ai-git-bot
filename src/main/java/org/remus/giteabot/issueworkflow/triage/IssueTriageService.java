package org.remus.giteabot.issueworkflow.triage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.agent.writerimpl.WriterPromptBuilder;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Agentic issue triage/routing for {@link TriageIssueWorkflow}: runs an
 * {@link AgentLoop} with the read-only repository/issue tool surface so the
 * model can gather context (repository files, issue details, similar issues)
 * before committing to a routing decision, then posts the routing reason as
 * an issue comment and performs the assignment through the provider API.
 *
 * <p>The run is deliberately session-less: triage is a one-shot routing
 * decision, so the loop runs on a transient {@link AgentSession} (never
 * persisted) and the workspace is cleaned up afterwards. Context gathering is
 * read-only — the {@link TriageAgentStrategy} advertises the
 * {@link ToolCatalog.Role#WRITER} tool surface through
 * {@link AgentToolRouter.Mode#WRITER}, which never reaches a file-mutation,
 * build/validation or git-write tool. The only writes are the issue comment
 * and the final assignment.</p>
 *
 * <p>Two output protocols share one validation path
 * ({@link RoutingDecision#validate}): the terminal {@code assign_issue} tool
 * call when the bot's AI client supports native tools, a single
 * {@code {"assignment", "reason"}} JSON object otherwise. Model output is
 * treated as untrusted: the assignment must be one of the configured names
 * (or the reserved {@code none}), the reason must be a non-blank single
 * line, and the model may not route the issue back to the triage bot itself
 * (that would retrigger the workflow in a loop). Any violation that the
 * agent does not correct within its retry budget, and any provider-side
 * assignability failure, results in an error comment on the issue plus a
 * {@link TriageRoutingException} so the orchestrator records the failure
 * through the existing issue-workflow error handling.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTriageService {

    /**
     * Default values, overridable via {@code AgentConfigProperties.triage.*}.
     * Kept as constants so unit tests that construct the service without a
     * fully-populated config still get sensible behaviour.
     */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    private static final int DEFAULT_MAX_INITIAL_TREE_FILES = 100;
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000;

    /** Appended to the initial user message when native tool calling is available. */
    static final String TOOL_CALL_SUFFIX = """
            Use your read-only tools to gather any repository or issue context you need \
            before deciding (for example `tree`, `rg`, `ctags-signatures`, `cat`, \
            `get-issue`, `search-issues`).

            IMPORTANT: When you have gathered enough context, you MUST call the assign_issue tool \
            with your final routing decision. Do not output plain text or JSON for the final \
            answer — always use the tool call.

            The assign_issue tool has these parameters:
            - name: The assignment. Must be one of the known users
            - reason: A one-line justification for the assignment

            Call assign_issue exactly once, in its own turn. No explanation, no commentary.
            """;

    /** Appended to the initial user message when native tool calling is not available. */
    static final String JSON_SUFFIX = """
            Use requestFiles/requestTools to gather any repository or issue context you need \
            before deciding.

            Output format — when you have gathered enough context, respond with ONLY this JSON object:

            {
              "assignment": "<user>",
              "reason": "<one-line justification>"
            }
            """;

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final AgentSessionService sessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolSelectionService botToolSelectionService;
    private final AgentConfigProperties agentConfig;

    private final WriterPromptBuilder promptBuilder = new WriterPromptBuilder();
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler();

    /**
     * Runs one triage pass for an issue-created or issue-assigned event.
     * Failures are reported as an issue comment plus a
     * {@link TriageRoutingException}; unexpected runtime exceptions propagate
     * to the orchestrator unchanged.
     */
    public void triage(Bot bot, WebhookPayload payload, Map<String, Object> params) {
        WebhookPayload.Issue issue = payload.getIssue();
        String owner = payload.getRepository() != null && payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository() != null ? payload.getRepository().getName() : null;
        if (issue == null || issue.getNumber() == null || owner == null || repo == null) {
            log.warn("[Bot '{}'] Triage skipped — webhook payload lacks issue or repository", bot.getName());
            return;
        }
        if (issue.getPullRequest() != null) {
            log.debug("[Bot '{}'] Triage skipped — issue #{} is a pull request", bot.getName(), issue.getNumber());
            return;
        }

        Set<String> allowed = allowedAssignees(params);
        String configuredPrompt = configuredPrompt(params);
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());

        String issueRef = BranchRefs.normalize(issue.getRef());
        String baseBranch = issueRef != null && !issueRef.isBlank()
                ? issueRef : repoClient.getDefaultBranch(owner, repo);

        Path workspaceDir = null;
        try {
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch, repoClient.getCloneUrl(owner, repo), repoClient.getCredentials(), null);
            if (!wsResult.success()) {
                postErrorComment(repoClient, owner, repo, issue.getNumber(),
                        "Issue triage failed: could not prepare the read-only repository context ("
                                + wsResult.error() + "). No assignment was made.");
                throw new TriageRoutingException(
                        "Issue triage could not prepare the workspace: " + wsResult.error());
            }
            workspaceDir = wsResult.workspacePath();

            RoutingDecision decision = runTriageLoop(bot, issue, owner, repo, baseBranch,
                    workspaceDir, aiClient, repoClient, configuredPrompt, allowed);

            log.info("[Bot '{}'] Triage routed issue #{} to '{}': {}", bot.getName(), issue.getNumber(),
                    decision.assignee(), decision.reason());
            executeDecision(repoClient, owner, repo, issue.getNumber(), decision);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Runs the agent loop and returns the validated routing decision. A loop
     * that finishes without a valid decision is mapped to an error comment
     * plus {@link TriageRoutingException}.
     */
    private RoutingDecision runTriageLoop(Bot bot, WebhookPayload.Issue issue, String owner, String repo,
                                          String baseBranch, Path workspaceDir,
                                          AiClient aiClient, RepositoryApiClient repoClient,
                                          String configuredPrompt, Set<String> allowed) {
        Set<String> allowedBuiltinTools = botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration());
        McpToolCatalog mcpToolCatalog = mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));

        ToolingMode mode = aiClient.supportsNativeTools() ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        String systemPrompt = systemPromptAssembler.assemble(configuredPrompt, toolCatalog,
                allowedBuiltinTools, mcpToolCatalog, mode, SystemPromptAssembler.PromptKind.WRITER_AGENT);

        String treeContext = promptBuilder.buildTreeContext(
                repoClient.getRepositoryTree(owner, repo, baseBranch), maxInitialTreeFiles());
        String userMessage = buildInitialPrompt(issue, treeContext)
                + (mode == ToolingMode.NATIVE ? TOOL_CALL_SUFFIX : JSON_SUFFIX);

        AgentToolRouter toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog,
                mcpOrchestrationService, bot.getMcpConfiguration(), mcpToolCatalog,
                repoClient, allowedBuiltinTools);
        TriageAgentStrategy strategy = new TriageAgentStrategy(systemPrompt, toolRouter, toolCatalog,
                mcpToolCatalog, allowedBuiltinTools, allowed, bot.getUsername(),
                new AiResponseParser(), new BranchSwitcher(toolExecutionService), maxToolRounds());

        // Mirror the writer: one extra iteration beyond the context-round limit so the model
        // gets a chance to produce its terminal answer after exhausting context rounds.
        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        AgentBudget budget = new AgentBudget(
                maxToolRounds() + 1, maxToolRounds(), 0, budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                contextWindowTokens(bot), budgetCfg.getProactiveCompactionThreshold());

        AgentSession session = new AgentSession(owner, repo, issue.getNumber(), issue.getTitle());
        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(session, owner, repo, issue.getNumber(),
                workspaceDir, baseBranch);
        LoopOutcome outcome = loop.run(ctx, userMessage, strategy);

        if (outcome.success() && outcome.payload() instanceof RoutingDecision decision) {
            return decision;
        }
        String detail = outcome.payload() instanceof String s && !s.isBlank()
                ? s : "the model returned no usable routing decision";
        postErrorComment(repoClient, owner, repo, issue.getNumber(),
                "Issue triage failed: " + detail + " No assignment was made.");
        throw new TriageRoutingException("Issue triage routing failed: " + detail);
    }

    /**
     * Posts the reason comment first (so the rationale stays visible even
     * when the assignment itself fails), then assigns — or, for the reserved
     * {@code none} outcome, leaves the issue unassigned.
     */
    private void executeDecision(RepositoryApiClient repoClient, String owner, String repo,
                                 Long issueNumber, RoutingDecision decision) {
        if (RoutingDecision.NONE_ASSIGNEE.equals(decision.assignee())) {
            repoClient.postIssueComment(owner, repo, issueNumber,
                    "Issue triage: " + decision.reason() + " (no assignment)");
            return;
        }
        repoClient.postIssueComment(owner, repo, issueNumber,
                "Issue triage: " + decision.reason() + " (routing to `" + decision.assignee() + "`)");
        try {
            repoClient.assignIssue(owner, repo, issueNumber, decision.assignee());
        } catch (UnsupportedOperationException | IllegalArgumentException | RestClientResponseException e) {
            postErrorComment(repoClient, owner, repo, issueNumber,
                    "Issue triage: could not assign to `" + decision.assignee() + "` — the account does"
                            + " not exist, is not assignable on this repository, or the bot lacks the"
                            + " necessary permission. No assignment was made.");
            throw new TriageRoutingException(
                    "Issue triage could not assign to '" + decision.assignee() + "': " + e.getMessage(), e);
        }
    }

    private String buildInitialPrompt(WebhookPayload.Issue issue, String treeContext) {
        return """
                ## Issue to triage
                Number: #%d
                Title: %s

                Body:
                %s

                ## Repository files
                %s

                Decide who this issue should be routed to.
                """.formatted(issue.getNumber(),
                issue.getTitle() != null ? issue.getTitle() : "(no title)",
                issue.getBody() != null && !issue.getBody().isBlank() ? issue.getBody() : "(empty)",
                treeContext);
    }

    /**
     * The machine-readable allowed set: the configured comma-separated
     * account names plus the reserved {@code none}. Both output modes
     * validate against this one set.
     */
    private Set<String> allowedAssignees(Map<String, Object> params) {
        Object raw = params.get(TriageParam.ASSIGNEES.key());
        String text = raw != null && !String.valueOf(raw).isBlank()
                ? String.valueOf(raw) : TriageIssueWorkflow.DEFAULT_ASSIGNEES;
        Set<String> allowed = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !RoutingDecision.NONE_ASSIGNEE.equalsIgnoreCase(trimmed)) {
                allowed.add(trimmed);
            }
        }
        allowed.add(RoutingDecision.NONE_ASSIGNEE);
        return allowed;
    }

    private String configuredPrompt(Map<String, Object> params) {
        Object raw = params.get(TriageParam.SYSTEM_PROMPT.key());
        return raw != null && !String.valueOf(raw).isBlank()
                ? String.valueOf(raw) : TriageIssueWorkflow.DEFAULT_SYSTEM_PROMPT;
    }

    /** Best-effort error comment; a comment failure never masks the routing failure. */
    private void postErrorComment(RepositoryApiClient repoClient, String owner, String repo,
                                  Long issueNumber, String body) {
        try {
            repoClient.postIssueComment(owner, repo, issueNumber, body);
        } catch (Exception e) {
            log.warn("Failed to post triage error comment on issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage());
        }
    }

    private int maxToolRounds() {
        if (agentConfig == null || agentConfig.getTriage() == null) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
        int configured = agentConfig.getTriage().getMaxToolRounds();
        return configured > 0 ? configured : DEFAULT_MAX_TOOL_ROUNDS;
    }

    private int maxInitialTreeFiles() {
        if (agentConfig == null || agentConfig.getTriage() == null) {
            return DEFAULT_MAX_INITIAL_TREE_FILES;
        }
        int configured = agentConfig.getTriage().getMaxInitialTreeFiles();
        return configured > 0 ? configured : DEFAULT_MAX_INITIAL_TREE_FILES;
    }

    private int contextWindowTokens(Bot bot) {
        return bot.getAiIntegration() != null
                ? bot.getAiIntegration().getContextWindowTokens() : DEFAULT_CONTEXT_WINDOW_TOKENS;
    }
}
