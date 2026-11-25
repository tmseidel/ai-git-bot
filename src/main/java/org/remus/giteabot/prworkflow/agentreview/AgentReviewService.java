package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.config.ReviewCommentProperties;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.review.structured.ReviewLinkStyle;
import org.remus.giteabot.review.structured.ReviewLinkStyles;
import org.remus.giteabot.review.structured.ReviewLookupToolExecutor;
import org.remus.giteabot.review.structured.ReviewSnapshotService;
import org.remus.giteabot.review.structured.ReviewSubmissionToolExecutor;
import org.remus.giteabot.review.structured.StructuredReviewService;

import java.util.function.Consumer;

/**
 * Entry point of the agentic PR-review workflow. Not a Spring singleton — instances are created
 * per-bot by {@link AgentReviewServiceFactory} with the bot's own
 * {@link org.remus.giteabot.ai.AiClient} and {@link RepositoryApiClient}.
 *
 * <p>Generation and publication are separate: {@link AgentReviewExecution} runs the read-only
 * agent until it finalizes a structured review, and {@link ReviewPublisher} renders and posts that
 * stored record. Nothing is published from model prose, so the comment is reproducible from the
 * database and the formal decision is computed from persisted findings.</p>
 *
 * <p>Curated lessons reach the run only through the explicit {@link AgentReviewLessons} argument;
 * a run whose lessons are revoked before publication is cancelled, not published.</p>
 */
@Slf4j
public class AgentReviewService {

    /**
     * Operator-configured severity thresholds for the deterministic formal review decision. A
     * {@code null} value means that severity is ignored.
     */
    public record SeverityThresholds(Integer blocker, Integer medium, Integer low) {
    }

    /** Distinguishes a posted review or clarification from a no-op and a failed attempt. */
    public enum ReviewResult {
        POSTED,
        NO_DIFF,
        /**
         * The review was generated and stored but deliberately not posted — the run was cancelled, the
         * head moved, or the review had already been published. Distinguishing this from {@link #FAILED}
         * keeps a correct suppression from looking like a broken workflow run.
         */
        SUPPRESSED,
        FAILED
    }

    private final Bot bot;
    private final RepositoryPlatform repositoryPlatform;
    private final RepositoryApiClient repositoryClient;
    private final ReviewPublisher publisher;
    private final ReviewCommentProperties commentProperties;
    private final AgentReviewExecution execution;

    public AgentReviewService(AgentReviewContext context,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              ToolCatalog toolCatalog,
                              WorkspaceService workspaceService,
                              AgentConfigProperties agentConfig,
                              McpOrchestrationService mcpOrchestrationService,
                              Bot bot,
                              EventHookPublisher eventHookPublisher,
                              PythonExecutionService pythonExecution,
                              StructuredReviewService reviewService,
                              ReviewSubmissionToolExecutor submissionExecutor,
                              ReviewSnapshotService snapshotService,
                              ReviewLookupToolExecutor reviewLookup,
                              ReviewCommentProperties commentProperties,
                              ReviewPublisher publisher,
                              AgentReviewLessons lessons) {
        this.bot = bot;
        this.repositoryPlatform = context.repositoryPlatform();
        this.repositoryClient = repositoryPlatform.getClient();
        this.publisher = publisher;
        this.commentProperties = commentProperties;
        this.execution = new AgentReviewExecution(context, sessionService, toolExecutionService,
                toolCatalog, workspaceService, agentConfig, mcpOrchestrationService, bot,
                reviewService, submissionExecutor, snapshotService, reviewLookup,
                pythonExecution, lessons);
    }

    /**
     * Runs an agentic review for the PR described by {@code payload} and publishes the review the
     * model recorded through the review tools.
     *
     * @param maxToolRounds         operator-tunable cap on read-only exploration rounds
     * @param enableFormalDecision  when true, the operator's criteria are added to the prompt and a
     *                              formal review action is computed from the persisted severity
     *                              totals
     * @param enableProgressComments whether to create and update a pull-request progress comment
     * @return whether a review was posted, no diff was available, or the review failed
     */
    public ReviewResult reviewPullRequest(WebhookPayload payload, int maxToolRounds,
                                         boolean enableFormalDecision,
                                         SeverityThresholds severityThresholds,
                                         Long runId,
                                         Consumer<AgentRunContext.ToolCallRecord> toolCallConsumer,
                                         boolean enableProgressComments,
                                         AgentReviewLearningSettings learningSettings) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        try {
            AgentReviewExecution.Result result = execution.review(payload,
                    new AgentReviewExecution.Options(maxToolRounds, enableFormalDecision,
                            enableProgressComments, runId, toolCallConsumer,
                            learningSettings == null
                                    ? AgentReviewLearningSettings.DISABLED : learningSettings));
            ReviewResult reviewResult = switch (result.outcome()) {
                case NO_DIFF -> ReviewResult.NO_DIFF;
                case FAILED -> ReviewResult.FAILED;
                case FINALIZED -> publish(owner, repo, prNumber, result,
                        enableFormalDecision, severityThresholds, learningSettings);
            };
            if (result.guidance() != null && result.guidance().revokedReason().isPresent()) {
                // Suppressed right before publication: the run ends cancelled, like one revoked
                // between rounds. The finalized review stays stored.
                throw new GuidanceRevokedException("Agentic review " + result.reference()
                        + " was not published: " + result.guidance().revokedReason().get(),
                        result.guidance().revokedReason().get());
            }
            return reviewResult;
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agentic review failed for PR #{} in {}/{}: {}", prNumber, owner, repo,
                    e.getMessage(), e);
            postErrorComment(owner, repo, prNumber, "Agentic Review",
                    "The review could not be completed because of an error. "
                            + "This is usually a transient issue with the AI provider. "
                            + "Please try again later.", e);
            return ReviewResult.FAILED;
        }
    }

    private ReviewResult publish(String owner, String repo, Long prNumber,
                                 AgentReviewExecution.Result result, boolean enableFormalDecision,
                                 SeverityThresholds thresholds,
                                 AgentReviewLearningSettings learningSettings) {
        try {
            ReviewPublisher.Publication publication = publisher.publish(
                    new ReviewPublisher.PublicationRequest(
                            repositoryClient, bot, owner, repo, prNumber, result.reference(),
                             enableFormalDecision, thresholds, linkStyle(owner, repo, result.reviewedHeadSha()),
                             providerName(), "pull_request_review", result.reviewedHeadSha(),
                            () -> currentHeadSha(owner, repo, prNumber),
                            () -> Thread.currentThread().isInterrupted()
                                    || (result.guidance() != null
                                    && result.guidance().recheck().isPresent()),
                            learningSettings.feedbackEnabled()
                                    ? org.remus.giteabot.learning.feedback.FeedbackHint.forReview(
                                            mention(owner), result.reference())
                                    : null));
            return switch (publication.outcome()) {
                case PUBLISHED -> ReviewResult.POSTED;
                case SUPPRESSED, UNRESOLVED -> {
                    log.warn("Agentic review {} was not published: {}", result.reference(),
                            publication.reason());
                    yield ReviewResult.SUPPRESSED;
                }
                case ALREADY_PUBLISHED -> {
                    log.info("Agentic review {} was not published again: {}", result.reference(),
                            publication.reason());
                    yield ReviewResult.SUPPRESSED;
                }
            };
        } catch (Exception e) {
            log.error("Publishing agentic review {} failed for PR #{} in {}/{}: {}",
                    result.reference(), prNumber, owner, repo, e.getMessage(), e);
            postErrorComment(owner, repo, prNumber, "Agentic Review",
                    "The review was prepared but could not be posted. This is usually a transient "
                            + "issue with the Git provider. Please try again later.", e);
            return ReviewResult.FAILED;
        }
    }

    /**
     * Source links for the published comment, when the provider addresses a line anchor. A missing
     * head sha or an unsupported provider degrades to plain paths, never to a guessed URL.
     */
    private ReviewLinkStyle linkStyle(String owner, String repo, String reviewedHeadSha) {
        return ReviewLinkStyles.forProvider(
                bot.getGitIntegration() != null ? bot.getGitIntegration().getProviderType() : null,
                repositoryPlatform, owner, repo, reviewedHeadSha);
    }

    /**
     * The alias of the account the bot's token authenticates as, or {@code null} when that account
     * cannot be resolved; the feedback hint then goes without a mention.
     */
    private String mention(String owner) {
        try {
            return new BotIdentity(repositoryPlatform.getUser(owner)).alias();
        } catch (IllegalStateException e) {
            log.debug("Bot account unavailable for the feedback hint: {}", e.getMessage());
            return null;
        }
    }

    private String providerName() {
        return bot.getGitIntegration() != null
                ? bot.getGitIntegration().getProviderType().name() : "UNKNOWN";
    }

    /**
     * The pull request's current head, asked of the provider right before publication. An
     * unreadable answer means "cannot verify", not "changed": the publication then proceeds, because
     * refusing every review on a flaky API call would be worse.
     */
    private String currentHeadSha(String owner, String repo, Long prNumber) {
        return PrPayloadHydrator.resolveHeadSha(repositoryClient, owner, repo,
                prNumber == null ? 0 : prNumber);
    }

    /** A clarification without learned guidance. */
    public ReviewResult answerClarification(WebhookPayload payload, String userQuestion, int maxToolRounds,
                                           boolean enableProgressComments) {
        return answerClarification(payload, userQuestion, maxToolRounds, enableProgressComments,
                AgentReviewLearningSettings.DISABLED);
    }

    /**
     * Answers a clarification question about a previously-reviewed PR. Formal review decisions are
     * not applicable, and the answer cannot modify the published review. Lessons valid right now are
     * selected afresh for the answer when the settings use them.
     */
    public ReviewResult answerClarification(WebhookPayload payload, String userQuestion, int maxToolRounds,
                                           boolean enableProgressComments,
                                           AgentReviewLearningSettings learningSettings) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        log.info("Answering clarification for PR #{} in {}/{}: {}", prNumber, owner, repo,
                userQuestion.length() > 120 ? userQuestion.substring(0, 117) + "..." : userQuestion);
        try {
            AgentReviewExecution.Answer answer = execution.answerClarification(payload, userQuestion,
                    maxToolRounds, enableProgressComments,
                    learningSettings == null ? AgentReviewLearningSettings.DISABLED : learningSettings);
            if (answer.outcome() == AgentReviewExecution.AnswerOutcome.NO_DIFF) {
                return ReviewResult.NO_DIFF;
            }
            if (answer.outcome() == AgentReviewExecution.AnswerOutcome.FAILED
                    || answer.text() == null || answer.text().isBlank()) {
                log.warn("Clarification for PR #{} produced no answer", prNumber);
                return ReviewResult.FAILED;
            }
            repositoryClient.postPullRequestComment(owner, repo, prNumber,
                    formatClarification(answer.text()));
            log.info("Clarification answered for PR #{} in {}/{}", prNumber, owner, repo);
            return ReviewResult.POSTED;
        } catch (GuidanceRevokedException e) {
            // The answer is withheld like a review would be, but a question is not review evidence:
            // the person who asked is told why nothing came and that asking again will work.
            log.warn("Clarification for PR #{} in {}/{} withheld: {}", prNumber, owner, repo,
                    e.reason());
            postNotice(owner, repo, prNumber, "Clarification",
                    "This question was not answered because the repository guidance given to the "
                            + "answer changed while it ran (" + abbreviate(e.reason(), 300) + "). "
                            + "Ask again to get an answer based on the current lessons.");
            throw e;
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Clarification failed for PR #{} in {}/{}: {}", prNumber, owner, repo,
                    e.getMessage(), e);
            postErrorComment(owner, repo, prNumber, "Clarification",
                    "The clarification could not be completed because of an error. "
                            + "This is usually a transient issue with the AI provider. "
                            + "Please try again later.", e);
            return ReviewResult.FAILED;
        }
    }

    private String formatClarification(String answer) {
        String footer = commentProperties.getClarificationFooter();
        if (footer == null || footer.isBlank()) {
            return "## 🤖 Follow-up\n\n" + answer;
        }
        return "## 🤖 Follow-up\n\n" + answer + "\n\n---\n\n" + footer.strip();
    }

    /** A plain one-line notice on the pull request; a failure to post it is logged only. */
    private void postNotice(String owner, String repo, Long prNumber, String stage, String message) {
        if (owner == null || repo == null || prNumber == null) {
            return;
        }
        try {
            repositoryClient.postPullRequestComment(owner, repo, prNumber,
                    "ℹ️ **AI Agent (" + stage + ")**: " + message);
        } catch (RuntimeException postError) {
            log.warn("Failed to post notice on PR #{} in {}/{}: {}",
                    prNumber, owner, repo, postError.getMessage());
        }
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
}
