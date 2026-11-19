package org.remus.giteabot.learning.feedback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.repository.DeliveryFailures;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Function;

/**
 * Consumes {@code @bot feedback …} commands from Git comments and submitted reviews.
 *
 * <p>Every recognized command is consumed here: it either records/reads feedback or answers with a
 * short explanation. Nothing is forwarded to an AI conversation, so a malformed or unsupported
 * command can never start code review, change code, generate tests, or continue a coding session.
 * Comments that carry no feedback command return {@code false} and keep their ordinary routing.</p>
 *
 * <p>Confirmations are posted after the database write has committed and their delivery is tracked
 * per event: a failed confirmation never discards feedback, a redelivered webhook does not confirm
 * twice, and a confirmation whose delivery is uncertain is not posted again.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackCommandHandler {

    public static final String COMMENT_KIND_PR_COMMENT = "PR_COMMENT";
    public static final String COMMENT_KIND_INLINE = "INLINE_COMMENT";
    /** The body of a submitted pull request review (GitHub, Gitea). */
    public static final String COMMENT_KIND_REVIEW = "REVIEW";

    private final FeedbackService feedbackService;
    private final FeedbackAuthorizationService authorizationService;
    private final AgentReviewLearningSettingsResolver learningSettingsResolver;
    private final RepositoryPlatformFactory repositoryPlatformFactory;

    /** The text, identity and author of whatever carried the command. */
    private record Source(String body, String id, String author) {
    }

    /** A reply to post; {@code acknowledgementEventId} is set when its delivery is tracked. */
    private record Reply(String body, Long acknowledgementEventId) {

        static Reply untracked(String body) {
            return new Reply(body, null);
        }
    }

    /**
     * @param commentKind    which kind of provider comment carried the command, part of the dedupe
     *                       key; {@link #COMMENT_KIND_REVIEW} reads the submitted review's body
     * @param effectiveAlias the mention the provider left in the comment body, or {@code null} when it
     *                       did not rewrite it. Bitbucket replaces {@code @{account_id}} with a
     *                       readable name, so the account's own alias alone would never match
     * @return {@code true} when the comment was a feedback command and has been consumed
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload, String commentKind,
                             String effectiveAlias) {
        Source source = sourceOf(payload, commentKind);
        if (bot == null || source == null) {
            return false;
        }
        String configured = aliasOf(bot, payload);
        String alias = effectiveAlias == null || effectiveAlias.isBlank() ? configured : effectiveAlias;
        Optional<FeedbackCommand> parsed = FeedbackCommandParser.parse(source.body(), alias, configured);
        if (parsed.isEmpty()) {
            return false;
        }
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            log.debug("[Bot '{}'] Feedback command without repository context ignored", bot.getName());
            return false;
        }

        boolean collecting = collectionEnabled(bot);
        // A bot that collects no feedback only consumes text that is unmistakably addressed to it: a
        // target (O1234-F2) or a subcommand (retract/list/show). Prose like "@bot feedback please, is
        // this approach OK?" keeps the routing it had before feedback commands existed.
        if (!collecting && !FeedbackCommandParser.addressesFeedback(source.body(), alias, configured)) {
            return false;
        }

        String actor = source.author();
        if (!authorizationService.canContribute(bot, actor)) {
            // A public "not permitted" answer is an invitation: with a whitelist configured, anyone
            // could make the bot comment on demand. The command is consumed, the refusal is logged.
            log.info("[Bot '{}'] Feedback command from '{}' refused: not permitted to give feedback",
                    bot.getName(), actor);
            return true;
        }
        if (!collecting) {
            log.info("[Bot '{}'] Feedback command from '{}' ignored: feedback collection is disabled",
                    bot.getName(), actor);
            return true;
        }
        Reply reply;
        try {
            reply = execute(bot, payload, commentKind, source, parsed.get(), alias);
        } catch (FeedbackRejectedException rejected) {
            reply = Reply.untracked(FeedbackReplyRenderer.rejected(rejected));
        } catch (RuntimeException e) {
            // Details stay in the log. An exception message can carry SQL, entity names or a provider
            // error body, and this reply is published on the pull request (CWE-209).
            log.error("[Bot '{}'] Feedback command failed: {}", bot.getName(), e.getMessage(), e);
            reply = Reply.untracked("The feedback command could not be completed. "
                    + "The details were written to the bot log.");
        }
        deliver(bot, payload, reply);
        return true;
    }

    private Reply execute(Bot bot, WebhookPayload payload, String commentKind, Source source,
                          FeedbackCommand command, String mention) {
        if (command instanceof FeedbackCommand.Help(String message)) {
            return Reply.untracked(FeedbackReplyRenderer.help(message));
        }
        FeedbackScope scope = scopeOf(bot, payload);
        return switch (command) {
            case FeedbackCommand.Submit submit -> confirmation(
                    feedbackService.submit(scope, feedbackSourceOf(payload, commentKind, source, scope),
                            submit.target(), submit.verdict(), submit.reason(), source.author()),
                    FeedbackReplyRenderer::acknowledged);
            case FeedbackCommand.Retract retract -> confirmation(
                    feedbackService.retract(scope, retract.reference(), source.author()),
                    FeedbackReplyRenderer::retracted);
            case FeedbackCommand.Show show -> Reply.untracked(feedbackService
                    .show(scope, show.reference())
                    .map(detail -> FeedbackReplyRenderer.show(detail, bot.getName(), mention,
                            linksOf(bot, scope)))
                    .orElse("No feedback " + show.reference() + " is available here."));
            case FeedbackCommand.ListRecords list -> Reply.untracked(FeedbackReplyRenderer.list(
                    list.repositoryWide(),
                    feedbackService.list(scope, list.repositoryWide(), FeedbackService.MAX_PAGE_SIZE,
                            list.cursor()),
                    bot.getName(), mention, linksOf(bot, scope)));
            default -> throw new IllegalStateException("Unexpected value: " + command);
        };
    }

    /**
     * A new record is confirmed. A redelivery is confirmed again only when the earlier confirmation
     * was refused by the provider — and only by the one delivery that claims the retry. A pending or
     * uncertain confirmation may already be on the pull request, so it is left alone.
     */
    private Reply confirmation(FeedbackService.Recorded recorded,
                               Function<FeedbackService.Recorded, String> render) {
        if (!recorded.duplicate()) {
            return new Reply(render.apply(recorded), recorded.acknowledgementEventId());
        }
        if (recorded.acknowledgement() == FeedbackAcknowledgement.FAILED
                && feedbackService.claimAcknowledgementRetry(recorded.acknowledgementEventId())) {
            return new Reply(render.apply(recorded), recorded.acknowledgementEventId());
        }
        return null;
    }

    private void deliver(Bot bot, WebhookPayload payload, Reply reply) {
        if (reply == null || reply.body() == null || reply.body().isBlank()) {
            return;
        }
        FeedbackAcknowledgement outcome = post(bot, payload, reply.body());
        if (reply.acknowledgementEventId() == null) {
            return;
        }
        try {
            feedbackService.markAcknowledgement(reply.acknowledgementEventId(), outcome);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Could not record the confirmation outcome {}: {}", bot.getName(),
                    outcome, e.getMessage());
        }
    }

    private FeedbackAcknowledgement post(Bot bot, WebhookPayload payload, String body) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long number = PrPayloadHydrator.resolvePrNumber(payload);
        if (number == null) {
            log.warn("[Bot '{}'] Cannot answer the feedback command: no pull request number",
                    bot.getName());
            return FeedbackAcknowledgement.FAILED;
        }
        try {
            RepositoryApiClient client =
                    repositoryPlatformFactory.getApiClient(bot.getGitIntegration());
            client.postPullRequestComment(owner, repo, number, body);
            return FeedbackAcknowledgement.POSTED;
        } catch (RuntimeException e) {
            FeedbackAcknowledgement outcome = DeliveryFailures.isDefiniteRefusal(e)
                    ? FeedbackAcknowledgement.FAILED : FeedbackAcknowledgement.UNCERTAIN;
            log.warn("[Bot '{}'] Feedback was handled but the reply could not be posted ({}): {}",
                    bot.getName(), outcome, e.getMessage());
            return outcome;
        }
    }

    private boolean collectionEnabled(Bot bot) {
        AgentReviewLearningSettings settings = learningSettingsResolver.resolve(bot);
        return settings.feedbackEnabled();
    }

    private FeedbackLinks linksOf(Bot bot, FeedbackScope scope) {
        if (bot.getGitIntegration() == null) {
            return FeedbackLinks.NONE;
        }
        try {
            return FeedbackLinks.of(bot.getGitIntegration().getProviderType(),
                    repositoryPlatformFactory.getPlatform(bot.getGitIntegration()).getCloneUrl(),
                    scope.repoOwner(), scope.repoName());
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] No links for feedback overviews: {}", bot.getName(), e.getMessage());
            return FeedbackLinks.NONE;
        }
    }

    /**
     * The alias of the account the bot's token authenticates as, or {@code null} when that account
     * cannot be resolved; the effective alias from the event then has to match on its own.
     */
    private String aliasOf(Bot bot, WebhookPayload payload) {
        if (bot.getGitIntegration() == null) {
            return null;
        }
        String owner = payload.ownerLogin();
        try {
            return new BotIdentity(repositoryPlatformFactory.getPlatform(bot.getGitIntegration()).getUser(owner))
                    .alias();
        } catch (IllegalStateException e) {
            log.debug("[Bot '{}'] Bot account unavailable for feedback commands: {}",
                    bot.getName(), e.getMessage());
            return null;
        }
    }

    private static Source sourceOf(WebhookPayload payload, String commentKind) {
        if (payload == null) {
            return null;
        }
        if (COMMENT_KIND_REVIEW.equals(commentKind)) {
            WebhookPayload.Review review = payload.getReview();
            if (review == null) {
                return null;
            }
            return new Source(review.getContent(),
                    review.getId() == null ? null : review.getId().toString(),
                    payload.getSender() == null ? null : payload.getSender().getLogin());
        }
        WebhookPayload.Comment comment = payload.getComment();
        if (comment == null) {
            return null;
        }
        return new Source(comment.getBody(),
                comment.getId() == null ? null : comment.getId().toString(),
                comment.getUser() == null ? null : comment.getUser().getLogin());
    }

    private FeedbackScope scopeOf(Bot bot, WebhookPayload payload) {
        Long integrationId = bot.getGitIntegration() == null
                ? null : bot.getGitIntegration().getId();
        return new FeedbackScope(integrationId, bot.getId(),
                payload.getRepository().getOwner().getLogin(),
                payload.getRepository().getName(),
                PrPayloadHydrator.resolvePrNumber(payload));
    }

    /**
     * The submitting comment's identity. Feedback targets are reviews on pull requests, so the
     * subject kind is always {@code PR} here; issue-thread targets are not supported.
     */
    private static FeedbackSource feedbackSourceOf(WebhookPayload payload, String commentKind,
                                                   Source source, FeedbackScope scope) {
        Long repositoryId = payload.getRepository().getId();
        return new FeedbackSource(scope.gitIntegrationId(), scope.botId(), scope.repoOwner(),
                scope.repoName(),
                repositoryId == null ? null : String.valueOf(repositoryId),
                FeedbackSource.SubjectKind.PR, scope.prNumber(), commentKind, source.id());
    }
}
