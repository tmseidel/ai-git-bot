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
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core business logic of the agentic PR-review workflow. Not a Spring
 * singleton — instances are created per-bot by {@link AgentReviewServiceFactory}
 * with the bot's own {@link AiClient} and {@link RepositoryApiClient}.
 *
 * <p>The flow mirrors {@link org.remus.giteabot.agent.IssueImplementationService}
 * but is strictly read-only: it clones a workspace, lets the LLM explore the
 * repository through {@link ToolCatalog.Role#WRITER} (read-only) tools and MCP,
 * then posts a single review comment. When the operator enables the optional
 * formal review decision, the bot may additionally approve or request changes.</p>
 */
@Slf4j
public class AgentReviewService {

    /**
     * Fixed instruction appended to the system prompt when formal review decisions
     * are enabled. Defines the exact structured return format.
     */
    static final String DECISION_FORMAT_INSTRUCTION = """


            ## Formal Review Decision Output Format

            The last line of your response MUST be exactly one JSON object and nothing else:

            {"decision": "APPROVE"}

            - "APPROVE" — approve the PR.
            - "REQUEST_CHANGES" — request changes before merging.
            - "NONE" — leave the review state unchanged.

            Do not wrap it in a code fence and do not write anything after it.""";

    /**
     * Matches a trailing decision JSON block, bare or fenced, regardless of
     * whether its value is a valid enum — an invalid value is still detected and
     * stripped so it never leaks into the posted review.
     */
    private static final Pattern DECISION_JSON_PATTERN = Pattern.compile(
            "```json\\s*\\n?\\s*(\\{[^}]*\"decision\"[^}]*})\\s*\\n?\\s*```\\s*\\z",
            Pattern.DOTALL);

    private static final Pattern DECISION_BARE_PATTERN = Pattern.compile(
            "\\{[^}]*\"decision\"\\s*:\\s*\"([^\"]*)\"[^}]*}\\s*\\z",
            Pattern.MULTILINE);

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
     * the resulting review as a PR comment. When {@code enableFormalDecision}
     * is {@code true}, the system prompt is extended with the operator-
     * configured criteria and a fixed format instruction; the model's output
     * is parsed for a formal decision (APPROVE / REQUEST_CHANGES / NONE), which
     * is submitted together with the review body via
     * {@link RepositoryApiClient#postReview}.
     *
     * @param maxToolRounds       operator-tunable cap on the number of
     *                            explore/answer rounds (clamped to a sane range)
     * @param enableFormalDecision when true, the model may return a formal review decision
     * @param decisionPrompt       operator-provided criteria for the decision
     * @return {@code true} when a non-empty review was produced and posted;
     *         {@code false} when there was nothing to review or the agent failed
     */
    public boolean reviewPullRequest(WebhookPayload payload, int maxToolRounds,
                                     boolean enableFormalDecision, String decisionPrompt) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting agentic review for PR #{} '{}' in {}/{} (formalDecision={})",
                prNumber, prTitle, owner, repo, enableFormalDecision);

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{} — skipping agentic review", prNumber, owner, repo);
            return false;
        }

        DiffSummary diffSummary = DiffSummary.parse(diff);
        log.info("Parsed diff summary for PR #{}: {}", prNumber, diffSummary.statLine());

        String headBranch = resolveHeadBranch(payload, owner, repo, prNumber);
        if (headBranch == null) {
            log.warn("Could not resolve PR head branch for PR #{} in {}/{} — skipping agentic review "
                    + "(refusing to review the default branch)", prNumber, owner, repo);
            return false;
        }

        Path workspaceDir = null;
        try {
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken(), prNumber);
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for agentic review of PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent (Review)**: Failed to prepare workspace: " + wsResult.error());
                return false;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = resolveSystemPrompt(enableFormalDecision, decisionPrompt);
            String userMessage = buildKickoffMessage(prTitle, prBody, diffSummary);

            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);

            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber,
                    workspaceDir, headBranch, systemPrompt, userMessage, maxToolRounds, diffSummary);

            String review = outcome.payload() instanceof String s ? s : null;
            if (review == null || review.isBlank()) {
                log.warn("Agentic review for PR #{} produced no review text", prNumber);
                return false;
            }

            ParseResult parsed = enableFormalDecision
                    ? parseDecision(review) : ParseResult.noDecision(review);

            PostReviewAction action = parsed.action() != null ? parsed.action() : PostReviewAction.NONE;
            String reviewBody = formatReview(parsed.reviewText());
            try {
                repositoryClient.postReview(owner, repo, prNumber, reviewBody, action);
                if (action != PostReviewAction.NONE) {
                    log.info("Agentic review posted formal decision {} for PR #{} in {}/{}",
                            action, prNumber, owner, repo);
                }
            } catch (Exception e) {
                if (action == PostReviewAction.NONE) {
                    throw e;
                }
                // Fall back to a plain comment so the findings survive a failed formal submission.
                log.warn("Failed to post formal review {} for PR #{} in {}/{}: {} — "
                        + "falling back to a plain review comment",
                        action, prNumber, owner, repo, e.getMessage());
                repositoryClient.postReviewComment(owner, repo, prNumber, reviewBody);
            }

            log.info("Agentic review completed for PR #{} in {}/{} (decision={})",
                    prNumber, owner, repo, action);
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
     * a conversational agent loop. Formal review decisions are not applicable here.
     */
    public boolean answerClarification(WebhookPayload payload, String userQuestion, int maxToolRounds) {
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

        DiffSummary diffSummary = DiffSummary.parse(diff);
        log.info("Parsed diff summary for clarification on PR #{}: {}", prNumber, diffSummary.statLine());

        String headBranch = resolveHeadBranch(payload, owner, repo, prNumber);
        if (headBranch == null) {
            log.warn("Could not resolve PR head branch for PR #{} in {}/{} — cannot answer clarification "
                    + "(refusing to review the default branch)", prNumber, owner, repo);
            return false;
        }

        Path workspaceDir = null;
        try {
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken(),prNumber);
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for clarification on PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return false;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = resolveSystemPrompt(false, null);
            String userMessage = buildClarificationMessage(prTitle, prBody, diffSummary, userQuestion);


            AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);

            LoopOutcome outcome = runReviewLoop(session, owner, repo, prNumber,
                    workspaceDir, headBranch, systemPrompt, userMessage,
                    maxToolRounds, diffSummary);

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

    /**
     * Parses a formal review decision from the model output and strips the
     * trailing decision block so the review comment is clean. A detected block
     * is stripped regardless of validity; an unparseable value yields a
     * {@code null} action (fail-open) but still-cleaned text.
     */
    static ParseResult parseDecision(String review) {
        if (review == null || review.isBlank()) {
            return ParseResult.noDecision(review);
        }

        // Canonical form: a bare JSON object on the last line.
        Matcher bare = DECISION_BARE_PATTERN.matcher(review);
        if (bare.find()) {
            String cleaned = review.substring(0, bare.start()).stripTrailing();
            return new ParseResult(cleaned, fromString(bare.group(1)));
        }

        // Tolerant fallback: a fenced ```json block at the end.
        Matcher fenced = DECISION_JSON_PATTERN.matcher(review);
        if (fenced.find()) {
            String cleaned = review.substring(0, fenced.start()).stripTrailing();
            return new ParseResult(cleaned, extractAction(fenced.group(1)));
        }

        return ParseResult.noDecision(review);
    }

    private static PostReviewAction extractAction(String json) {
        // Minimal JSON extraction — avoids a full parser dependency for this one field.
        Matcher m = Pattern.compile("\"decision\"\\s*:\\s*\"(APPROVE|REQUEST_CHANGES|NONE)\"")
                .matcher(json);
        return m.find() ? fromString(m.group(1)) : null;
    }

    private static PostReviewAction fromString(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase()) {
            case "APPROVE" -> PostReviewAction.APPROVE;
            case "REQUEST_CHANGES" -> PostReviewAction.REQUEST_CHANGES;
            case "NONE" -> PostReviewAction.NONE;
            default -> null;
        };
    }

    /**
     * Parsed result of extracting a formal review decision from model output.
     *
     * @param reviewText the review text with the decision JSON stripped
     * @param action     the parsed decision, or {@code null} when unparseable
     */
    public record ParseResult(String reviewText, PostReviewAction action) {
        static ParseResult noDecision(String reviewText) {
            return new ParseResult(reviewText, null);
        }
    }

    private String buildClarificationMessage(String prTitle, String prBody, DiffSummary diffSummary, String userQuestion) {
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

                Changed files (%s):

                %s

                To see the diff hunks for a specific file, call the `pr-diff` tool with the \
                file path. To read the full current content of a file, use `cat`. To understand \
                a file's structure before reading it, use `ctags-signatures`.

                When you have gathered enough context, reply with your answer as plain \
                Markdown (no tool calls). Focus on answering the specific question asked.
                """.formatted(
                        prTitle == null ? "(none)" : prTitle,
                        prBody == null || prBody.isBlank() ? "(none)" : prBody,
                        userQuestion,
                        diffSummary.statLine(),
                        diffSummary.fileTable());
    }

    private String formatClarification(String answer) {
        return "## 🤖 Follow-up\n\n" + answer
                + "\n\n---\n*Read-only agentic review follow-up by AI Git Bot*";
    }

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
                                      String systemPrompt, String userMessage, int maxToolRounds,
                                      DiffSummary diffSummary) {
        ReviewAgentStrategy strategy = new ReviewAgentStrategy(
                systemPrompt, toolRouter, toolCatalog,
                context.mcpToolCatalog(), context.allowedBuiltinTools(),
                responseParser, branchSwitcher, this::fetchFiles,
                agentConfig.getBudget().getMaxContextRounds());

        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int rounds = clamp(maxToolRounds, 1, 30);
        int hardCap = Math.max(budgetCfg.getMaxRounds(), rounds + 2);
        AgentBudget budget = new AgentBudget(hardCap, budgetCfg.getMaxContextRounds(),
                budgetCfg.getMaxValidationRetries(), budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                context.contextWindowTokens(), budgetCfg.getProactiveCompactionThreshold());

        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = new AgentRunContext(session, owner, repo, prNumber, workspaceDir, headBranch);
        ctx.setDiffSummary(diffSummary);
        return loop.run(ctx, userMessage, strategy);
    }

    private String resolveSystemPrompt(boolean enableFormalDecision, String decisionPrompt) {
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        String base = systemPromptAssembler.assemble(context.reviewAgentSystemPrompt(), toolCatalog,
                context.allowedBuiltinTools(), context.mcpToolCatalog(), mode,
                SystemPromptAssembler.PromptKind.WRITER_AGENT);

        if (!enableFormalDecision) {
            return base;
        }

        String prompt = decisionPrompt != null && !decisionPrompt.isBlank()
                ? decisionPrompt : AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT;
        return base + "\n\n" + prompt + DECISION_FORMAT_INSTRUCTION;
    }

    private String buildKickoffMessage(String prTitle, String prBody, DiffSummary diffSummary) {
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
        sb.append("Changed files (").append(diffSummary.statLine()).append("):\n\n");
        sb.append(diffSummary.fileTable()).append("\n");
        sb.append("""
                To see the diff hunks for a specific file, call the `pr-diff` tool with the \
                file path. To read the full current content of a file, use `cat`. To understand \
                a file's structure before reading it, use `ctags-signatures`.
                
                When you have gathered enough context, reply with your final review as plain \
                Markdown (no tool calls). Summarise correctness, risks, and concrete suggestions.""");
        return sb.toString();
    }

    private String formatReview(String review) {
        return "## 🤖 Agentic Code Review\n\n" + review
                + "\n\n---\n*Read-only agentic review by AI Git Bot*";
    }

    /**
     * Resolves the PR head branch to clone for review. Prefers the webhook
     * payload; when the head ref is missing (notably {@code issue_comment}-style
     * events with no {@code pull_request.head} block) it authoritatively
     * re-fetches it from the provider API. Returns {@code null} when it cannot be
     * determined — callers MUST skip rather than substitute the repository default
     * branch, so the bot never reviews the wrong branch.
     */
    private String resolveHeadBranch(WebhookPayload payload, String owner, String repo, long prNumber) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank()) {
            return BranchRefs.normalize(payload.getPullRequest().getHead().getRef());
        }
        return fetchHeadBranchFromApi(owner, repo, prNumber);
    }

    @SuppressWarnings("unchecked")
    private String fetchHeadBranchFromApi(String owner, String repo, long prNumber) {
        if (prNumber <= 0) {
            return null;
        }
        try {
            java.util.Map<String, Object> pr = repositoryClient.getPullRequestDetails(owner, repo, prNumber);
            if (pr != null && pr.get("head") instanceof java.util.Map<?, ?> head
                    && ((java.util.Map<String, Object>) head).get("ref") instanceof String ref
                    && !ref.isBlank()) {
                return BranchRefs.normalize(ref);
            }
        } catch (Exception e) {
            log.debug("agentic-review: getPullRequestDetails failed for {}/{}#{}: {}",
                    owner, repo, prNumber, e.getMessage());
        }
        return null;
    }

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
