package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.PullRequestWorkspaceMode;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.learning.context.LearningContextProvider;
import org.remus.giteabot.learning.context.LessonBundle;
import org.remus.giteabot.learning.context.LessonSelectionRequest;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.review.structured.ReviewDraftHandle;
import org.remus.giteabot.review.structured.ReviewDraftRequest;
import org.remus.giteabot.review.structured.ReviewLookupInvocation;
import org.remus.giteabot.review.structured.ReviewLookupToolExecutor;
import org.remus.giteabot.review.structured.ReviewInvocation;
import org.remus.giteabot.review.structured.ReviewSnapshotRequest;
import org.remus.giteabot.review.structured.ReviewSnapshotService;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.ReviewSubmissionToolExecutor;
import org.remus.giteabot.review.structured.StructuredReviewService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Generation half of the agentic review: opens a review draft, runs the read-only agent until it
 * completes that draft through the submission tools, and returns its identity. Nothing here
 * publishes anything — {@link ReviewPublisher} renders and posts the stored record.
 *
 * <p>Every exit path leaves an explicit draft state. A run that failed, was cancelled or was
 * superseded closes its draft, so a later attempt can never append to it, and an incomplete draft
 * can never be mistaken for an empty review.</p>
 *
 * <p>Curated lessons selected for the change are delivered in the kickoff (or clarification) user
 * message — never in the operator system prompt, which together with the tool protocol and the
 * permission settings stays authoritative. A run whose guidance is revoked while it is in flight
 * ends as cancelled instead of publishing.</p>
 */
@Slf4j
public class AgentReviewExecution {

    public enum Outcome { FINALIZED, NO_DIFF, FAILED }

    /**
     * @param outcome   what happened
     * @param reviewId  the draft this run opened (present for every outcome but {@link Outcome#NO_DIFF})
     * @param reference the finalized {@code O<n>} reference when {@code outcome == FINALIZED}
     * @param reviewedHeadSha the webhook's head, resolved when abbreviated by Bitbucket, if known
     * @param guidance  learned guidance the run received, or {@code null}; publication rechecks it
     */
    public record Result(Outcome outcome, Long reviewId, String reference, String reviewedHeadSha,
                         LearnedGuidance guidance) {

        public Result(Outcome outcome, Long reviewId, String reference, String reviewedHeadSha) {
            this(outcome, reviewId, reference, reviewedHeadSha, null);
        }

        public boolean finalized() {
            return outcome == Outcome.FINALIZED;
        }
    }

    /** Outcome of a clarification run: an answer to post, no diff to discuss, or a failure. */
    public enum AnswerOutcome { ANSWERED, NO_DIFF, FAILED }

    public record Answer(AnswerOutcome outcome, String text) {
    }

    /** Operator-tunable inputs of one generation run. */
    public record Options(int maxToolRounds, boolean enableFormalDecision,
                          boolean enableProgressComments, Long runId,
                          Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                          AgentReviewLearningSettings learningSettings) {
    }

    private final AgentReviewContext context;
    private final AgentSessionService sessionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;
    private final Bot bot;
    private final StructuredReviewService reviewService;
    private final ReviewSubmissionToolExecutor submissionExecutor;
    private final ReviewSnapshotService snapshotService;
    private final ReviewLookupToolExecutor reviewLookup;
    private final AgentReviewLessons lessons;

    private final RepositoryPlatform repositoryPlatform;
    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final AgentToolRouter toolRouter;
    private final AiResponseParser responseParser = new AiResponseParser();
    private final BranchSwitcher branchSwitcher;
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler();

    public AgentReviewExecution(AgentReviewContext context,
                                AgentSessionService sessionService,
                                ToolExecutionService toolExecutionService,
                                ToolCatalog toolCatalog,
                                WorkspaceService workspaceService,
                                AgentConfigProperties agentConfig,
                                McpOrchestrationService mcpOrchestrationService,
                                Bot bot,
                                StructuredReviewService reviewService,
                                ReviewSubmissionToolExecutor submissionExecutor,
                                ReviewSnapshotService snapshotService,
                                ReviewLookupToolExecutor reviewLookup,
                                PythonExecutionService pythonExecution,
                                AgentReviewLessons lessons) {
        this.context = context;
        this.sessionService = sessionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = workspaceService;
        this.agentConfig = agentConfig;
        this.bot = bot;
        this.reviewService = reviewService;
        this.submissionExecutor = submissionExecutor;
        this.snapshotService = snapshotService;
        this.reviewLookup = reviewLookup;
        this.lessons = lessons == null ? AgentReviewLessons.NONE : lessons;
        this.repositoryPlatform = context.repositoryPlatform();
        this.repositoryClient = repositoryPlatform.getClient();
        this.aiClient = context.aiClient();
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog,
                mcpOrchestrationService, context.mcpConfiguration(),
                context.mcpToolCatalog(), this.repositoryClient, context.allowedBuiltinTools(),
                pythonExecution);
    }

    /** Runs one review generation for {@code payload}, leaving a finalized draft behind on success. */
    public Result review(WebhookPayload payload, Options options) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting agentic review for PR #{} '{}' in {}/{} (formalDecision={})",
                prNumber, prTitle, owner, repo, options.enableFormalDecision());

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{} — skipping agentic review", prNumber, owner, repo);
            return new Result(Outcome.NO_DIFF, null, null, null);
        }
        DiffSummary diffSummary = DiffSummary.parse(diff);
        log.info("Parsed diff summary for PR #{}: {}", prNumber, diffSummary.statLine());

        String headBranch = PrPayloadHydrator.resolveHeadBranch(repositoryClient, payload, owner, repo,
                prNumber);
        if (headBranch == null) {
            log.warn("Could not resolve PR head branch for PR #{} in {}/{} — skipping agentic review "
                    + "(refusing to review the default branch)", prNumber, owner, repo);
            return new Result(Outcome.FAILED, null, null, null);
        }

        ReviewDraftRequest request = draftRequest(payload, owner, repo, prNumber, options.runId());
        ReviewDraftHandle draft = reviewService.createDraft(request);
        ReviewProgress progress = options.enableProgressComments()
                ? new ReviewProgress(repositoryClient, owner, repo, prNumber, Clock.systemUTC()) : null;
        boolean finalized = false;
        try {
            if (progress != null) {
                progress.start();
            }
            WorkspaceResult wsResult = workspaceService.preparePullRequestWorkspace(
                    repositoryPlatform, owner, repo, headBranch, prNumber,
                    PullRequestWorkspaceMode.READ_ONLY);
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for agentic review of PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent (Review)**: Could not check out this pull request, so the "
                                + "review was skipped. The bot log holds the Git output.");
                reviewService.closeDraft(draft.reviewId(), ReviewState.FAILED);
                return new Result(Outcome.FAILED, draft.reviewId(), null, null);
            }
            LessonSelectionRequest selection = selectionRequest(owner, repo, diffSummary, prTitle,
                    prBody, options.learningSettings());
            LessonBundle bundle = selectLessons(selection);
            LearnedGuidance guidance = guidanceOf(selection, bundle);
            recordUsage(bundle, draft.outputReference(), request.invocationId());
            String systemPrompt = resolveReviewPrompt();
            LoopOutcome outcome;
            try (Workspace workspace = wsResult.workspace()) {
                AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);
                outcome = runReviewLoop(session, workspace, owner, repo, prNumber, headBranch,
                        diffSummary, prTitle, prBody, systemPrompt, draft, options, progress,
                        guidance);
            }
            cancelIfRevoked(guidance, prNumber);
            if (!outcome.success() || !(outcome.payload() instanceof String reference)
                    || !reference.equals(draft.outputReference())) {
                log.warn("Agentic review for PR #{} ended without a finalized review; nothing will be "
                        + "published", prNumber);
                reviewService.closeDraft(draft.reviewId(), ReviewState.FAILED);
                return new Result(Outcome.FAILED, draft.reviewId(), null, null);
            }
            finalized = true;
            captureSnapshot(payload, request, reference, systemPrompt, diff, options, bundle);
            return new Result(Outcome.FINALIZED, draft.reviewId(), reference, request.headSha(),
                    guidance);
        } catch (WorkflowCancelledException e) {
            reviewService.closeDraft(draft.reviewId(), ReviewState.CANCELLED);
            log.warn("Agentic review for PR #{} was cancelled; its draft stays incomplete", prNumber);
            throw e;
        } catch (RuntimeException e) {
            reviewService.closeDraft(draft.reviewId(), ReviewState.FAILED);
            throw e;
        } finally {
            if (progress != null) {
                progress.finish(finalized);
            }
        }
    }

    /**
     * Answers a clarification question about a previously-reviewed PR with a plain answer. No
     * submission tools are advertised, so nothing here can alter a published review.
     *
     * <p>Learned guidance is selected afresh from the lessons valid now — a follow-up never inherits
     * the guidance an earlier review received. It is not counted as lesson usage: usage counts
     * reviews.</p>
     */
    public Answer answerClarification(WebhookPayload payload, String userQuestion, int maxToolRounds,
                                      boolean enableProgressComments,
                                      AgentReviewLearningSettings learningSettings) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{} — cannot answer clarification",
                    prNumber, owner, repo);
            return new Answer(AnswerOutcome.NO_DIFF, null);
        }
        DiffSummary diffSummary = DiffSummary.parse(diff);
        String headBranch = PrPayloadHydrator.resolveHeadBranch(repositoryClient, payload, owner, repo,
                prNumber);
        if (headBranch == null) {
            log.warn("Could not resolve PR head branch for PR #{} in {}/{} — cannot answer "
                    + "clarification (refusing to review the default branch)", prNumber, owner, repo);
            return new Answer(AnswerOutcome.FAILED, null);
        }

        ReviewProgress progress = enableProgressComments
                ? new ReviewProgress(repositoryClient, owner, repo, prNumber, Clock.systemUTC()) : null;
        boolean completed = false;
        try {
            if (progress != null) {
                progress.start();
            }
            WorkspaceResult wsResult = workspaceService.preparePullRequestWorkspace(
                    repositoryPlatform, owner, repo, headBranch, prNumber,
                    PullRequestWorkspaceMode.READ_ONLY);
            if (!wsResult.success()) {
                log.warn("Failed to prepare workspace for clarification on PR #{}: {}",
                        prNumber, wsResult.error());
                repositoryClient.postPullRequestComment(owner, repo, prNumber,
                        "⚠️ **AI Agent**: Could not check out this pull request, so the question "
                                + "could not be answered. The bot log holds the Git output.");
                return new Answer(AnswerOutcome.FAILED, null);
            }
            LessonSelectionRequest selection = selectionRequest(owner, repo, diffSummary, prTitle,
                    prBody, learningSettings);
            LearnedGuidance guidance = guidanceOf(selection, selectLessons(selection));
            LoopOutcome outcome;
            try (Workspace workspace = wsResult.workspace()) {
                String systemPrompt = resolveClarificationPrompt();
                String userMessage = withGuidance(buildClarificationMessage(prTitle, prBody,
                        diffSummary, userQuestion), guidance);
                AgentSession session = new AgentSession(owner, repo, prNumber, prTitle);
                ReviewAgentStrategy strategy = new ReviewAgentStrategy(systemPrompt, toolRouter,
                        toolCatalog, context.mcpToolCatalog(), context.allowedBuiltinTools(),
                        responseParser, branchSwitcher, this::fetchFiles,
                        agentConfig.getBudget().getMaxContextRounds(), Math.max(1, maxToolRounds),
                        Math.max(1, maxToolRounds), lookup(owner, repo, prNumber), null, guidance);
                AgentBudget budget = budget(maxToolRounds, maxToolRounds);
                AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
                AgentRunContext ctx = AgentRunContext.forPullRequest(session, owner, repo, prNumber,
                        workspace, headBranch);
                ctx.setDiffSummary(diffSummary);
                ctx.setProgressConsumer(progress);
                outcome = loop.run(ctx, userMessage, strategy);
            }
            cancelIfRevoked(guidance, prNumber);
            if (!outcome.success() || !(outcome.payload() instanceof String answer) || answer.isBlank()) {
                log.warn("Clarification for PR #{} produced no answer", prNumber);
                return new Answer(AnswerOutcome.FAILED, null);
            }
            completed = true;
            return new Answer(AnswerOutcome.ANSWERED, answer);
        } finally {
            if (progress != null) {
                progress.finish(completed);
            }
        }
    }

    /**
     * Supplemental evidence for later feedback analysis. Captured only when the configuration
     * enables collection, and never allowed to affect the review that was already finalized: a
     * failure here leaves the review usable and the evidence explicitly incomplete.
     */
    private void captureSnapshot(WebhookPayload payload, ReviewDraftRequest request, String reference,
                                 String systemPrompt, String diff, Options options,
                                 LessonBundle lessonBundle) {
        if (!options.learningSettings().feedbackEnabled()) {
            return;
        }
        String reviewedHead = request.headSha();
        List<String> reasons = new ArrayList<>();
        String currentHead = PrPayloadHydrator.resolveHeadSha(repositoryClient, request.repoOwner(), request.repoName(),
                request.prNumber() == null ? 0 : request.prNumber());
        if (reviewedHead != null && currentHead != null && !reviewedHead.equals(currentHead)) {
            reasons.add("the pull request head moved from " + reviewedHead + " to " + currentHead
                    + " while the review ran, so the diff and the checkout may describe different revisions");
        }
        try {
            snapshotService.capture(new ReviewSnapshotRequest(reference,
                    payload.getPullRequest().getTitle(), payload.getPullRequest().getBody(), diff,
                    systemPrompt, providerName(), modelName(),
                    bot.getWorkflowConfiguration() != null
                            ? bot.getWorkflowConfiguration().getId() : null,
                    options.learningSettings().fingerprint(),
                    request.baseSha(), reviewedHead,
                    refOf(payload.getPullRequest().getBase()), refOf(payload.getPullRequest().getHead()),
                    request.providerRepositoryId(),
                    reasons,
                    lessonBundle.status().name(),
                    lessonBundle.hasGuidance() ? lessonBundle.revisionReferences() : null,
                    lessonBundle.contentHash()));
        } catch (RuntimeException e) {
            log.warn("Could not capture the learning snapshot for review {}: {}", reference,
                    e.getMessage());
        }
    }

    private String providerName() {
        return bot.getGitIntegration() != null
                ? bot.getGitIntegration().getProviderType().name() : null;
    }

    private String modelName() {
        return bot.getAiIntegration() != null ? bot.getAiIntegration().getModel() : null;
    }

    private static String refOf(WebhookPayload.Head head) {
        return head != null ? head.getRef() : null;
    }

    // ------------------------------------------------------------------ loop wiring

    private LoopOutcome runReviewLoop(AgentSession session, Workspace workspace, String owner,
                                      String repo, Long prNumber, String headBranch,
                                      DiffSummary diffSummary, String prTitle, String prBody,
                                      String systemPrompt, ReviewDraftHandle draft, Options options,
                                      ReviewProgress progress, LearnedGuidance guidance) {
        String userMessage = withGuidance(buildKickoffMessage(prTitle, prBody, diffSummary), guidance);

        int explorationRounds = Math.max(1, options.maxToolRounds());
        // Submissions are one AI round each (finding, correction, assessment), so the budget has to
        // fit a full review: half the exploration budget could not hold five findings plus the
        // assessment, and completion is charged separately (never) on top.
        int submissionRounds = Math.max(8, explorationRounds);
        ReviewInvocation invocation = invocation(draft, owner, repo, prNumber, options.runId());
        ReviewAgentStrategy strategy = new ReviewAgentStrategy(systemPrompt, toolRouter, toolCatalog,
                context.mcpToolCatalog(), context.allowedBuiltinTools(), responseParser,
                branchSwitcher, this::fetchFiles, agentConfig.getBudget().getMaxContextRounds(),
                explorationRounds, submissionRounds,
                lookup(owner, repo, prNumber),
                new ReviewAgentStrategy.Submission(submissionExecutor, invocation, reviewService),
                guidance);

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                budget(explorationRounds, submissionRounds));
        AgentRunContext ctx = AgentRunContext.forPullRequest(session, owner, repo, prNumber,
                workspace, headBranch);
        ctx.setDiffSummary(diffSummary);
        ctx.setAuditToolCallConsumer(options.toolCallConsumer());
        ctx.setProgressConsumer(progress);
        return loop.run(ctx, userMessage, strategy);
    }

    private AgentBudget budget(int explorationRounds, int submissionRounds) {
        AgentConfigProperties.BudgetConfig budgetCfg = agentConfig.getBudget();
        int reserve = Math.addExact(explorationRounds, submissionRounds);
        int hardCap = Math.max(budgetCfg.getMaxRounds(), reserve + 2);
        return new AgentBudget(hardCap, budgetCfg.getMaxContextRounds(),
                budgetCfg.getMaxValidationRetries(), budgetCfg.getMaxTokensPerCall(),
                budgetCfg.getMaxToolResultChars(), budgetCfg.getMaxHistoryChars(),
                context.contextWindowTokens(), budgetCfg.getProactiveCompactionThreshold());
    }

    private ReviewAgentStrategy.Lookup lookup(String owner, String repo, Long prNumber) {
        return new ReviewAgentStrategy.Lookup(reviewLookup, new ReviewLookupInvocation(
                bot.getGitIntegration() == null ? null : bot.getGitIntegration().getId(),
                bot.getId(), owner, repo, prNumber, () -> Thread.currentThread().isInterrupted()));
    }

    private ReviewInvocation invocation(ReviewDraftHandle draft, String owner, String repo,
                                        Long prNumber, Long runId) {
        return new ReviewInvocation(draft.reviewId(), bot.getId(),
                bot.getGitIntegration() != null ? bot.getGitIntegration().getId() : null,
                owner, repo, prNumber, runId,
                () -> Thread.currentThread().isInterrupted());
    }

    // ------------------------------------------------------------------ learned guidance

    private LessonSelectionRequest selectionRequest(String owner, String repo, DiffSummary diffSummary,
                                                    String prTitle, String prBody,
                                                    AgentReviewLearningSettings settings) {
        return new LessonSelectionRequest(
                bot.getGitIntegration() == null ? null : bot.getGitIntegration().getId(),
                bot.getId(), owner, repo, LearningContextProvider.WORKFLOW_KEY,
                diffSummary.changedFiles(),
                (prTitle == null ? "" : prTitle) + "\n" + (prBody == null ? "" : prBody),
                settings, context.contextWindowTokens());
    }

    /**
     * The lessons for this run. Retrieval that is not wired or fails leaves the review without
     * learned guidance — it never switches workflows and never fails the review.
     */
    private LessonBundle selectLessons(LessonSelectionRequest selection) {
        if (!lessons.available()) {
            return selection.settings().curatedLearning()
                    ? LessonBundle.empty(LessonBundle.Status.UNAVAILABLE)
                    : LessonBundle.empty(LessonBundle.Status.LEARNING_OFF);
        }
        try {
            LessonBundle bundle = lessons.provider().select(selection);
            if (bundle.hasGuidance()) {
                log.info("Agentic review of {}/{} receives lessons {} ({} tokens){}",
                        selection.repoOwner(), selection.repoName(), bundle.revisionReferences(),
                        bundle.estimatedTokens(), bundle.excluded().isEmpty() ? ""
                                : "; left out: " + describe(bundle.excluded()));
            } else if (!bundle.excluded().isEmpty()) {
                log.info("Agentic review of {}/{} receives no lessons; left out: {}",
                        selection.repoOwner(), selection.repoName(), describe(bundle.excluded()));
            }
            return bundle;
        } catch (RuntimeException e) {
            log.warn("Lesson retrieval failed for {}/{}; the review runs without learned guidance: {}",
                    selection.repoOwner(), selection.repoName(), e.getMessage());
            return LessonBundle.empty(LessonBundle.Status.UNAVAILABLE);
        }
    }

    private static String describe(List<LessonBundle.Exclusion> excluded) {
        return String.join(", ", excluded.stream()
                .map(exclusion -> exclusion.reference() + " (" + exclusion.reason() + ")").toList());
    }

    private LearnedGuidance guidanceOf(LessonSelectionRequest selection, LessonBundle bundle) {
        if (!bundle.hasGuidance()) {
            return null;
        }
        return new LearnedGuidance(bundle,
                () -> lessons.provider().revocation(selection, bundle, lessons.current()));
    }

    /** Usage is evidence about the lesson, not part of the review: a failure is logged, never fatal. */
    private void recordUsage(LessonBundle bundle, String outputReference, String invocationId) {
        if (!bundle.hasGuidance()) {
            return;
        }
        try {
            lessons.provider().recordUsage(bundle, outputReference, invocationId);
        } catch (RuntimeException e) {
            log.warn("Could not record usage of lessons {} for review {}: {}",
                    bundle.revisionReferences(), outputReference, e.getMessage());
        }
    }

    /** A run whose guidance was revoked in flight is cancelled; nothing it produced is published. */
    private static void cancelIfRevoked(LearnedGuidance guidance, Long prNumber) {
        Optional<String> revoked = guidance == null ? Optional.empty() : guidance.revokedReason();
        if (revoked.isPresent()) {
            throw new WorkflowCancelledException("Agentic review of PR #" + prNumber
                    + " cancelled before publication: " + revoked.get());
        }
    }

    /** The learned guidance block goes last, as reference data after the task itself. */
    static String withGuidance(String message, LearnedGuidance guidance) {
        return guidance == null ? message : message + "\n\n" + guidance.text();
    }

    // ------------------------------------------------------------------ prompts

    /** The review prompt. Severity meaning lives in the tool schema; the decision is never asked. */
    private String resolveReviewPrompt() {
        return systemPromptAssembler.assemble(context.reviewAgentSystemPrompt(), toolCatalog,
                toolCatalog.reviewToolNames(context.allowedBuiltinTools()), context.mcpToolCatalog(),
                toolingMode(), SystemPromptAssembler.PromptKind.AGENT_REVIEW_AGENT);
    }

    private String resolveClarificationPrompt() {
        return systemPromptAssembler.assemble(context.reviewAgentSystemPrompt(), toolCatalog,
                toolCatalog.reviewToolNames(context.allowedBuiltinTools()), context.mcpToolCatalog(),
                toolingMode(), SystemPromptAssembler.PromptKind.AGENT_REVIEW_QA);
    }

    private ToolingMode toolingMode() {
        return aiClient != null && aiClient.supportsNativeTools() ? ToolingMode.NATIVE : ToolingMode.LEGACY;
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
                Record your review with the review tools described in your instructions: one \
                finding per distinct problem, then the overall assessment, then completion. The \
                application renders the published comment from those records — a reply written as \
                text is not published.""");
        return sb.toString();
    }

    private String buildClarificationMessage(String prTitle, String prBody, DiffSummary diffSummary,
                                             String userQuestion) {
        return """
                The user has a follow-up question about the pull request you reviewed earlier.

                PR Title: %s
                PR Description:
                %s

                You already reviewed this pull request, but this follow-up starts without that
                conversation. Call the `review-lookup` tool to read what you published: with no
                arguments it lists the reviews and their finding references, with `reference`
                (for example `O12-F1`) it reads that target, and with `query` it searches the
                findings. Resolve any reference the user cites before answering; do not guess what
                it said.

                The question is:

                %s

                The repository is checked out in your read-only workspace. Use your available \
                read-only tools to inspect the relevant code and answer the question \
                concisely. You cannot modify the repository.

                Changed files (%s):

                %s

                To see the diff hunks of changed files, call the `pr-diff` tool with up to 8 \
                file paths. To read the full current content of a file, use `cat`. To understand \
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

    // ------------------------------------------------------------------ scope

    private String resolveReviewedSha(WebhookPayload.Head ref) {
        return ref == null ? null : repositoryClient.resolveCommitSha(ref.getRepositoryFullName(), ref.getSha());
    }

    private ReviewDraftRequest draftRequest(WebhookPayload payload, String owner, String repo,
                                            Long prNumber, Long runId) {
        Long integrationId = bot.getGitIntegration() != null ? bot.getGitIntegration().getId() : null;
        Long providerRepositoryId = payload.getRepository().getId();
        return new ReviewDraftRequest(integrationId, bot.getId(), owner, repo,
                providerRepositoryId == null ? null : String.valueOf(providerRepositoryId),
                prNumber, runId, UUID.randomUUID().toString(),
                resolveReviewedSha(payload.getPullRequest().getBase()), resolveReviewedSha(payload.getPullRequest().getHead()));
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
}
