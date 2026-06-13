package org.remus.giteabot.prworkflow.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Component;

/**
 * First (and so far only) {@link PrWorkflow} implementation. Encapsulates the
 * pre-M1 PR-review behaviour previously inlined in
 * {@link org.remus.giteabot.admin.BotWebhookService#reviewPullRequest(Bot, WebhookPayload)}.
 *
 * <p>Behaviour, by design, is byte-for-byte identical to the legacy path:</p>
 * <ol>
 *     <li>Resolve the bot's {@link RepositoryApiClient}.</li>
 *     <li>Delegate to
 *     {@link org.remus.giteabot.review.CodeReviewService#reviewPullRequest(WebhookPayload, String)}.</li>
 *     <li>If a review was actually posted and the bot has a Git integration,
 *     forward the configured post-review action (e.g. approve / request
 *     changes / no-op).</li>
 * </ol>
 *
 * <p>Always registered as the {@code "review"} workflow and enabled by
 * default for every bot in M1 — the M2 workflow-configuration UI may later
 * disable it explicitly, but for now the orchestrator unconditionally runs
 * it on every pull-request webhook.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWorkflow implements PrWorkflow {

    /** Public, stable identifier referenced by config rows and metrics tags. */
    public static final String KEY = "review";

    private final CodeReviewServiceFactory codeReviewServiceFactory;
    private final GiteaClientFactory giteaClientFactory;

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
                + "or updated, then optionally approves or requests changes based on the bot's "
                + "configured post-review action.";
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.REVIEW;
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();

        RepositoryApiClient repositoryClient = giteaClientFactory.getApiClient(bot.getGitIntegration());

        // Before invoking the (potentially expensive) LLM review, make sure this run
        // has not already been superseded by a newer PR-synchronize event.
        context.requireActive("before invoking CodeReviewService.reviewPullRequest");

        boolean reviewed = codeReviewServiceFactory.create(bot, repositoryClient)
                .reviewPullRequest(payload, null);

        context.appendStep("review",
                reviewed ? "Posted review comment for PR" : "Skipped — no diff or no eligible content");

        if (reviewed && bot.getGitIntegration() != null) {
            // Final cooperative cancellation check before the externally-visible
            // post-review action (approve / request changes / …). Without this a
            // superseded run could still race an approve against an outdated diff.
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
}

