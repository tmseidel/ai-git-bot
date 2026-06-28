package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
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
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Core business logic of the agentic PR-review workflow. Not a Spring
 * singleton — instances are created per-bot by {@link AgentReviewServiceFactory}
 * with the bot's own {@link AiClient} and {@link RepositoryApiClient}.
 *
 * <p>The flow mirrors {@link org.remus.giteabot.agent.IssueImplementationService}
 * but is strictly read-only: it clones a workspace, lets the LLM explore the
 * repository through {@link ToolCatalog.Role#WRITER} (read-only) tools and MCP,
 * then posts a single review comment. No commit, push, branch creation or
 * formal review action (approve / request-changes) is ever performed.</p>
 */
@Slf4j
public class AgentReviewService {

    /** Hard ceiling on the diff size embedded in the kickoff prompt. */
    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60000;

    private final AgentReviewContext context;
    private final AgentSessionService sessionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final AgentToolRouter toolRouter;
    private final AiResponseParser responseParser = new AiResponseParser();
    private final BranchSwitcher branchSwitcher;
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler();

    public AgentReviewService(AgentReviewContext context,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              ToolCatalog toolCatalog,
                              WorkspaceService workspaceService,
                              AgentConfigProperties agentConfig) {
        this.context = context;
        this.sessionService = sessionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = workspaceService;
        this.agentConfig = agentConfig;
        this.repositoryClient = context.repositoryClient();
        this.aiClient = context.aiClient();
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog,
                context.mcpOrchestrationService(), context.mcpConfiguration(),
                context.mcpToolCatalog(), this.repositoryClient, context.allowedBuiltinTools());
    }

    /**
     * Runs an agentic review for the PR described by {@code payload} and posts
     * the resulting review as a PR comment.
     *
     * @param maxToolRounds operator-tunable cap on the number of explore/answer
     *                      rounds (clamped to a sane range)
     * @return {@code true} when a non-empty review was produced and posted;
     *         {@code false} when there was nothing to review or the agent
     *         failed to produce a review
     */
    public boolean reviewPullRequest(WebhookPayload payload, int maxToolRounds) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting agentic review for PR #{} '{}' in {}/{}", prNumber, prTitle, owner, repo);

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{} — skipping agentic review", prNumber, owner, repo);
            return false;
        }

        String headBranch = resolveHeadBranch(payload, owner, repo);

        Path workspaceDir = null;
        try {
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for agentic review of PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent (Review)**: Failed to prepare workspace: " + wsResult.error());
                return false;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = resolveSystemPrompt();
            String userMessage = buildKickoffMessage(prTitle, prBody, diff);

            // The review agent is strictly read-only: the session is only a
            // conversation-history carrier through the loop. Use a transient
            // (unpersisted, id == null) session so no agent_sessions row is
            // created and no transaction spans the clone + AI calls. The loop
            // skips persistence for id-less sessions (see AgentLoop#flushRound).
            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);

            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber,
                    workspaceDir, headBranch, systemPrompt, userMessage, maxToolRounds);

            String review = outcome.payload() instanceof String s ? s : null;
            if (review == null || review.isBlank()) {
                log.warn("Agentic review for PR #{} produced no review text", prNumber);
                return false;
            }

            repositoryClient.postReviewComment(owner, repo, prNumber, formatReview(review));
            log.info("Agentic review completed for PR #{} in {}/{}", prNumber, owner, repo);
            return outcome.success();
        } catch (Exception e) {
            log.error("Agentic review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
            postErrorComment(owner, repo, prNumber, "Agentic Review",
                    "The review could not be completed because of an error. "
                            + "This is usually a transient issue with the AI provider. "
                            + "Please try again later.", e);
            return false;
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Answers a clarification question about a previously-reviewed PR by running
     * a conversational agent loop.
     *
     * @param payload       the webhook payload (for PR identity)
     * @param userQuestion  the user's follow-up question
     * @return {@code true} when a non-empty answer was produced and posted
     */
    public boolean answerClarification(WebhookPayload payload, String userQuestion) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Answering clarification for PR #{} '{}' in {}/{}: {}", prNumber, prTitle, owner, repo,
                userQuestion.length() > 120 ? userQuestion.substring(0, 117) + "..." : userQuestion);

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{} — cannot answer clarification", prNumber, owner, repo);
            return false;
        }

        String headBranch = resolveHeadBranch(payload, owner, repo);

        Path workspaceDir = null;
        try {
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for clarification on PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return false;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = resolveSystemPrompt();
            String userMessage = buildClarificationMessage(prTitle, prBody, diff, userQuestion);

            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);

            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber,
                    workspaceDir, headBranch, systemPrompt, userMessage,
                    AgentReviewWorkflow.DEFAULT_MAX_TOOL_ROUNDS);

            String answer = outcome.payload() instanceof String s ? s : null;
            if (answer == null || answer.isBlank()) {
                log.warn("Clarification for PR #{} produced no answer", prNumber);
                return false;
            }

            repositoryClient.postPullRequestComment(owner, repo, prNumber, formatClarification(answer));
            log.info("Clarification answered for PR #{} in {}/{}", prNumber, owner, repo);
            return outcome.success();
        } catch (Exception e) {
            log.error("Clarification failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
            postErrorComment(owner, repo, prNumber, "Clarification",
                    "The clarification could not be completed because of an error. "
                            + "This is usually a transient issue with the AI provider. "
                            + "Please try again later.", e);
            return false;
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    private String buildClarificationMessage(String prTitle, String prBody, String diff, String userQuestion) {
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(diff truncated)"
                : diff;
        return """
                The user has a follow-up question about the pull request you reviewed earlier.

                PR Title: %s
                PR Description:
                %s

                The question is:

                %s

                The repository is checked out in your read-only workspace. Use your available \
                read-only tools to inspect the relevant code and answer the question \
                concisely. You cannot modify the repository.

                Unified diff:
                ```diff
                %s
                ```

                When you have gathered enough context, reply with your answer as plain \
                Markdown (no tool calls). Focus on answering the specific question asked.
                """.formatted(
                        prTitle == null ? "(none)" : prTitle,
                        prBody == null || prBody.isBlank() ? "(none)" : prBody,
                        userQuestion,
                        truncatedDiff);
    }

    private String formatClarification(String answer) {
        return "## 🤖 Follow-up\n\n" + answer
                + "\n\n---\n*Read-only agentic review follow-up by AI Git Bot*";
    }

    /**
     * Best-effort error comment posted to the PR when an LLM-tier exception
     * (e.g. 503, timeout) prevents the review or clarification from completing.
     * Failures posting the comment itself are swallowed — a missing notice
     * must never mask the original error.
     */
    private void postErrorComment(String owner, String repo, Long prNumber,
                                  String stage, String userMessage, Throwable error) {
        if (owner == null || repo == null || prNumber == null) {
            return;
        }
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String body = String.format("""
                ⚠️ **AI Agent (%s)**: %s

                Error details:
                ```
                %s
                ```

                _Check the bot logs for the full stack trace._
                """, stage, userMessage, abbreviate(reason, 1500));
        try {
            repositoryClient.postPullRequestComment(owner, repo, prNumber, body);
        } catch (RuntimeException postError) {
            log.warn("Failed to post error comment on PR #{} in {}/{}: {}",
                    prNumber, owner, repo, postError.getMessage());
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private LoopOutcome runReviewLoop(AgentSession session, String owner, String repo, Long prNumber,
                                      Path workspaceDir, String headBranch,
                                      String systemPrompt, String userMessage, int maxToolRounds) {
        ReviewAgentStrategy strategy = new ReviewAgentStrategy(
                systemPrompt, toolRouter, toolCatalog,
                context.mcpToolCatalog(), context.allowedBuiltinTools(),
                responseParser, branchSwitcher, this::fetchFiles,
                agentConfig.getBudget().getMaxContextRounds());

        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int rounds = clamp(maxToolRounds, 1, 30);
        // Each explore round is followed by an answer round; add slack so the
        // model can always produce a final review turn after its last tool call.
        int hardCap = Math.max(budgetCfg.getMaxRounds(), rounds + 2);
        AgentBudget budget = new AgentBudget(hardCap, budgetCfg.getMaxContextRounds(),
                budgetCfg.getMaxValidationRetries(), budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                context.contextWindowTokens(), budgetCfg.getProactiveCompactionThreshold());

        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(session, owner, repo, prNumber, workspaceDir, headBranch);
        return loop.run(ctx, userMessage, strategy);
    }

    private String resolveSystemPrompt() {
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        // The review agent uses the read-only WRITER tool surface, so the
        // WRITER_AGENT protocol guidance is the right fit.
        return systemPromptAssembler.assemble(context.reviewAgentSystemPrompt(), toolCatalog,
                context.allowedBuiltinTools(), context.mcpToolCatalog(), mode,
                SystemPromptAssembler.PromptKind.WRITER_AGENT);
    }

    private String buildKickoffMessage(String prTitle, String prBody, String diff) {
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(diff truncated)"
                : diff;
        StringBuilder sb = new StringBuilder();
        sb.append("Please review the following pull request.\n\n");
        sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
        if (prBody != null && !prBody.isBlank()) {
            sb.append("Description:\n").append(prBody).append('\n');
        }
        sb.append("""
                
                The repository is checked out in your read-only workspace. Use your available \
                read-only tools to inspect the surrounding code before judging the change. \
                You cannot modify the repository.
                
                """);
        sb.append("Unified diff:\n```diff\n").append(truncatedDiff).append("\n```\n\n");
        sb.append("When you have gathered enough context, reply with your final review as plain "
                + "Markdown (no tool calls). Summarise correctness, risks, and concrete suggestions.");
        return sb.toString();
    }

    private String formatReview(String review) {
        return "## 🤖 Agentic Code Review\n\n" + review
                + "\n\n---\n*Read-only agentic review by AI Git Bot*";
    }

    private String resolveHeadBranch(WebhookPayload payload, String owner, String repo) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null) {
            return BranchRefs.normalize(payload.getPullRequest().getHead().getRef());
        }
        try {
            return repositoryClient.getDefaultBranch(owner, repo);
        } catch (Exception e) {
            log.debug("Could not resolve PR head branch, falling back to 'main': {}", e.getMessage());
            return "main";
        }
    }

    /**
     * Fetches the contents of the files the model requested via the legacy
     * {@code requestFiles} protocol. Read-only — uses the repository API at the
     * currently selected ref. Mirrors
     * {@code IssueImplementationService.fetchSpecificFiles}.
     */
    private String fetchFiles(String owner, String repo, String ref, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return "";
        }
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
                    sb.append(content).append('\n');
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}






