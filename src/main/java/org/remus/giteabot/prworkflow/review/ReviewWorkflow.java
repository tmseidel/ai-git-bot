package org.remus.giteabot.prworkflow.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.config.ReviewChunkingProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Unified {@link PrWorkflow} for all PR review interactions — both the
 * automated diff review on PR open/update and the conversational follow-ups
 * (bot commands, inline review comments, review submissions, PR closed).
 *
 * <p>Dispatched by {@link org.remus.giteabot.prworkflow.PrWorkflowOrchestrator}
 * with a {@link PrWorkflowContext#hint(String) hint} keyed by
 * {@value #HINT_REVIEW_ACTION} that selects the handler:</p>
 *
 * <ul>
 *     <li>{@value #ACTION_REVIEW} (default) — automated PR diff review</li>
 *     <li>{@value #ACTION_BOT_COMMAND} — conversational @-mention response</li>
 *     <li>{@value #ACTION_INLINE_COMMENT} — inline review comment response</li>
 *     <li>{@value #ACTION_REVIEW_SUBMITTED} — process pending review comments</li>
 *     <li>{@value #ACTION_PR_CLOSED} — clean up review session</li>
 * </ul>
 *
 * <p>Always registered as the {@code "review"} workflow and enabled by
 * default for every bot.</p>
 */
@Slf4j
@Component
public class ReviewWorkflow implements PrWorkflow {

    /** Public, stable identifier referenced by config rows and metrics tags. */
    public static final String KEY = "review";

    /** Hint key for selecting the review action. */
    public static final String HINT_REVIEW_ACTION = "review.action";

    /** Action constants for {@link #HINT_REVIEW_ACTION}. */
    public static final String ACTION_REVIEW = "review";
    public static final String ACTION_BOT_COMMAND = "botCommand";
    public static final String ACTION_INLINE_COMMENT = "inlineComment";
    public static final String ACTION_REVIEW_SUBMITTED = "reviewSubmitted";
    public static final String ACTION_PR_CLOSED = "prClosed";

    private final CodeReviewServiceFactory codeReviewServiceFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final WorkflowSelectionService selectionService;
    private final ReviewChunkingProperties chunkingProperties;

    public ReviewWorkflow(CodeReviewServiceFactory codeReviewServiceFactory,
                          GiteaClientFactory giteaClientFactory,
                          @Lazy WorkflowSelectionService selectionService,
                          ReviewChunkingProperties chunkingProperties) {
        this.codeReviewServiceFactory = codeReviewServiceFactory;
        this.giteaClientFactory = giteaClientFactory;
        this.selectionService = selectionService;
        this.chunkingProperties = chunkingProperties;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "PR Review";
    }

    @Override
    public String description() {
        return "Posts an AI code review as a single PR comment whenever a pull request is opened "
                + "or updated, responds to @-mentions and inline review comments with conversational "
                + "follow-ups, and optionally approves or requests changes based on the bot's "
                + "configured post-review action.";
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(ReviewParam.MAX_DIFF_CHARS_PER_CHUNK,
                        "Max diff chars per chunk",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(chunkingProperties.getMaxDiffCharsPerChunk()),
                        "Maximum characters per diff chunk before splitting. The full PR diff is "
                                + "split into chunks of this size for review."),
                new WorkflowParamField(ReviewParam.MAX_DIFF_CHUNKS,
                        "Max diff chunks",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(chunkingProperties.getMaxDiffChunks()),
                        "Maximum number of diff chunks to review. Further chunks beyond this limit "
                                + "are skipped."),
                new WorkflowParamField(ReviewParam.RETRY_TRUNCATED_CHUNK_CHARS,
                        "Retry truncated chunk chars",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(chunkingProperties.getRetryTruncatedChunkChars()),
                        "When a chunk is too large for the model's context window (prompt-too-long "
                                + "error), the chunk is truncated to this many characters and retried "
                                + "once."));
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.REVIEW;
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        String action = context.hint(HINT_REVIEW_ACTION);
        if (action == null || action.isEmpty()) {
            action = ACTION_REVIEW;
        }

        // Resolve chunking params once — all handlers pass the same values
        // to the factory so every CodeReviewService has consistent configuration.
        Bot bot = context.bot();
        Map<String, Object> params = resolveWorkflowParams(bot);
        int maxDiffCharsPerChunk = intParam(params, ReviewParam.MAX_DIFF_CHARS_PER_CHUNK,
                chunkingProperties.getMaxDiffCharsPerChunk());
        int maxDiffChunks = intParam(params, ReviewParam.MAX_DIFF_CHUNKS,
                chunkingProperties.getMaxDiffChunks());
        int retryTruncatedChunkChars = intParam(params, ReviewParam.RETRY_TRUNCATED_CHUNK_CHARS,
                chunkingProperties.getRetryTruncatedChunkChars());

        return switch (action) {
            case ACTION_REVIEW -> doReview(context, maxDiffCharsPerChunk, maxDiffChunks,
                    retryTruncatedChunkChars);
            case ACTION_BOT_COMMAND -> doBotCommand(context, maxDiffCharsPerChunk, maxDiffChunks,
                    retryTruncatedChunkChars);
            case ACTION_INLINE_COMMENT -> doInlineComment(context, maxDiffCharsPerChunk, maxDiffChunks,
                    retryTruncatedChunkChars);
            case ACTION_REVIEW_SUBMITTED -> doReviewSubmitted(context, maxDiffCharsPerChunk, maxDiffChunks,
                    retryTruncatedChunkChars);
            case ACTION_PR_CLOSED -> doPrClosed(context, maxDiffCharsPerChunk, maxDiffChunks,
                    retryTruncatedChunkChars);
            default -> WorkflowResult.skipped("Unknown review action: " + action);
        };
    }

    private Map<String, Object> resolveWorkflowParams(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return Map.of();
        }
        return selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);
    }

    /** Automated PR diff review on open/update. */
    private WorkflowResult doReview(PrWorkflowContext context,
                                    int maxDiffCharsPerChunk, int maxDiffChunks,
                                    int retryTruncatedChunkChars) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();
        RepositoryApiClient repositoryClient = giteaClientFactory.getApiClient(bot.getGitIntegration());

        context.requireActive("before invoking CodeReviewService.reviewPullRequest");

        boolean reviewed = codeReviewServiceFactory.create(bot, repositoryClient,
                        maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars)
                .reviewPullRequest(payload, null);

        context.appendStep("review",
                reviewed ? "Posted review comment for PR" : "Skipped — no diff or no eligible content");

        if (reviewed && bot.getGitIntegration() != null) {
            context.requireActive("before posting post-review action");

            String owner = payload.getRepository().getOwner().getLogin();
            String repo = payload.getRepository().getName();
            Long prNumber = payload.getPullRequest().getNumber();
            repositoryClient.postReviewAction(owner, repo, prNumber,
                    bot.getGitIntegration().getPostReviewAction());
            context.appendStep("post-review-action",
                    "Forwarded post-review action: " + bot.getGitIntegration().getPostReviewAction());
        }

        return reviewed
                ? WorkflowResult.success("Review posted")
                : WorkflowResult.skipped("No diff or no eligible content");
    }

    /** Conversational bot command (@-mention in PR comment). */
    private WorkflowResult doBotCommand(PrWorkflowContext context,
                                        int maxDiffCharsPerChunk, int maxDiffChunks,
                                        int retryTruncatedChunkChars) {
        CodeReviewService service = codeReviewServiceFactory.create(context.bot(),
                giteaClientFactory.getApiClient(context.bot().getGitIntegration()),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        service.handleBotCommand(context.payload(), null);
        return WorkflowResult.success("Bot command handled");
    }

    /** Inline review comment response. */
    private WorkflowResult doInlineComment(PrWorkflowContext context,
                                           int maxDiffCharsPerChunk, int maxDiffChunks,
                                           int retryTruncatedChunkChars) {
        CodeReviewService service = codeReviewServiceFactory.create(context.bot(),
                giteaClientFactory.getApiClient(context.bot().getGitIntegration()),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        service.handleInlineComment(context.payload(), null);
        return WorkflowResult.success("Inline comment handled");
    }

    /** Review submitted event — processes pending review comments mentioning the bot. */
    private WorkflowResult doReviewSubmitted(PrWorkflowContext context,
                                             int maxDiffCharsPerChunk, int maxDiffChunks,
                                             int retryTruncatedChunkChars) {
        CodeReviewService service = codeReviewServiceFactory.create(context.bot(),
                giteaClientFactory.getApiClient(context.bot().getGitIntegration()),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        service.handleReviewSubmitted(context.payload(), null);
        return WorkflowResult.success("Review submitted handled");
    }

    /** PR closed — cleans up the review session. */
    private WorkflowResult doPrClosed(PrWorkflowContext context,
                                      int maxDiffCharsPerChunk, int maxDiffChunks,
                                      int retryTruncatedChunkChars) {
        CodeReviewService service = codeReviewServiceFactory.create(context.bot(),
                giteaClientFactory.getApiClient(context.bot().getGitIntegration()),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        service.handlePrClosed(context.payload());
        return WorkflowResult.success("PR closed handled");
    }

    private static int intParam(Map<String, Object> params, ReviewParam name, int fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
