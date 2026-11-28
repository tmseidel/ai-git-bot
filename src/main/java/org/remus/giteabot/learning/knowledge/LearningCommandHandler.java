package org.remus.giteabot.learning.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;
import org.remus.giteabot.learning.feedback.FeedbackAuthorizationService;
import org.remus.giteabot.learning.feedback.FeedbackLinks;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.repository.DeliveryFailures;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Consumes {@code @bot learning …} commands from Git comments and submitted reviews.
 *
 * <p>Authority comes from the bot's <em>current</em> agentic-review configuration, resolved per
 * command: only accounts on its learning-maintainer list may propose, revise, activate, reject or
 * disable lessons — an empty list grants nobody, and permission to give feedback is not authority
 * over repository policy. Reading ({@code list}, {@code show}) is open to everyone the bot accepts
 * feedback from, and keeps working with collection or learning switched off. Proposing, revising and
 * activating additionally require learning mode {@code CURATED}; disabling never does, so switching
 * learning off can never trap an active lesson.</p>
 *
 * <p>Nothing here calls a model and nothing is forwarded to an AI conversation. Confirmations follow
 * the committed write and are tracked per event like feedback confirmations: a redelivered webhook
 * neither acts twice nor confirms twice.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningCommandHandler {

    private final KnowledgeLessonService lessonService;
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
     * @param commentKind    which kind of provider comment carried the command (see
     *                       {@link org.remus.giteabot.learning.feedback.FeedbackCommandHandler})
     * @param effectiveAlias the mention the provider left in the comment body, or {@code null}
     * @return {@code true} when the comment was a lesson command and has been consumed
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload, PullRequestCommentKind commentKind,
                             String effectiveAlias) {
        Source source = sourceOf(payload, commentKind);
        if (bot == null || source == null) {
            return false;
        }
        String configured = aliasOf(bot, payload);
        String alias = effectiveAlias == null || effectiveAlias.isBlank() ? configured : effectiveAlias;
        Optional<LearningCommand> parsed = LearningCommandParser.parse(source.body(), alias, configured);
        if (parsed.isEmpty()) {
            return false;
        }
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            log.debug("[Bot '{}'] Lesson command without repository context ignored", bot.getName());
            return false;
        }

        AgentReviewLearningSettings settings = learningSettingsResolver.resolve(bot);
        boolean managed = settings.curatedLearning() || !settings.learningMaintainers().isEmpty();
        // A bot without lesson management only consumes text that is unmistakably a lesson command;
        // "@bot learning from this, could you …" keeps the routing it had before.
        if (!managed && !LearningCommandParser.addressesLearning(source.body(), alias, configured)) {
            return false;
        }

        String actor = source.author();
        boolean maintainer = settings.isLearningMaintainer(actor);
        if (!maintainer && !authorizationService.canContribute(bot, actor)) {
            // Same rule as feedback: a public refusal would let anyone excluded by the whitelist
            // make the bot comment on demand. Consumed and logged.
            log.info("[Bot '{}'] Lesson command from '{}' refused: not permitted to use the bot",
                    bot.getName(), actor);
            return true;
        }

        Reply reply;
        try {
            reply = execute(bot, payload, commentKind, source, parsed.get(), alias, settings,
                    maintainer);
        } catch (LessonRejectedException rejected) {
            reply = Reply.untracked(LessonReplyRenderer.rejected(rejected, alias));
        } catch (RuntimeException e) {
            // Details stay in the log: an exception message can carry SQL or a provider error body,
            // and this reply is published on the pull request (CWE-209).
            log.error("[Bot '{}'] Lesson command failed: {}", bot.getName(), e.getMessage(), e);
            reply = Reply.untracked("The lesson command could not be completed. "
                    + "The details were written to the bot log.");
        }
        deliver(bot, payload, reply);
        return true;
    }

    private Reply execute(Bot bot, WebhookPayload payload, PullRequestCommentKind commentKind,
                          Source source, LearningCommand command, String mention,
                          AgentReviewLearningSettings settings, boolean maintainer) {
        if (command instanceof LearningCommand.Help(String message)) {
            return Reply.untracked(LessonReplyRenderer.help(message, mention));
        }
        FeedbackScope scope = scopeOf(bot, payload);
        if (command instanceof LearningCommand.Management management) {
            String refusal = refusal(management, settings, maintainer);
            if (refusal != null) {
                log.info("[Bot '{}'] Lesson command from '{}' refused: {}", bot.getName(),
                        source.author(), refusal);
                return Reply.untracked(LessonReplyRenderer.help(refusal, mention));
            }
        }
        FeedbackLinks links = linksOf(bot, scope);
        FeedbackSource commentSource = feedbackSourceOf(payload, commentKind, source, scope);
        String actor = source.author();
        return switch (command) {
            case LearningCommand.Propose propose -> confirmation(scope,
                    lessonService.propose(scope, commentSource, propose.feedbackReference(),
                            propose.paths(), propose.text(), actor),
                    (detail, recorded) -> LessonReplyRenderer.candidate(detail, recorded.revision(),
                            bot.getName(), mention, links));
            case LearningCommand.Revise revise -> confirmation(scope,
                    lessonService.revise(scope, commentSource, revise.lessonReference(),
                            revise.paths(), revise.text(), actor),
                    (detail, recorded) -> LessonReplyRenderer.candidate(detail, recorded.revision(),
                            bot.getName(), mention, links));
            case LearningCommand.Activate activate -> confirmation(scope,
                    lessonService.activate(scope, commentSource, activate.lessonReference(),
                            activate.revision(), actor),
                    (detail, recorded) -> LessonReplyRenderer.activated(detail, mention));
            case LearningCommand.Reject reject -> confirmation(scope,
                    lessonService.reject(scope, commentSource, reject.lessonReference(),
                            reject.revision(), actor),
                    (detail, recorded) -> LessonReplyRenderer.rejectedCandidate(detail,
                            recorded.revision(), mention));
            case LearningCommand.Disable disable -> confirmation(scope,
                    lessonService.disable(scope, commentSource, disable.lessonReference(),
                            disable.reason(), actor),
                    (detail, recorded) -> LessonReplyRenderer.disabled(detail, recorded.revision(),
                            mention));
            case LearningCommand.Show show -> Reply.untracked(lessonService
                    .show(scope, show.lessonReference())
                    .map(detail -> LessonReplyRenderer.show(detail, bot.getName(), mention, links))
                    .orElse("No lesson " + show.lessonReference() + " is available here."));
            case LearningCommand.ListLessons list -> Reply.untracked(LessonReplyRenderer.list(
                    list.state(), lessonService.list(scope, list.state(),
                            KnowledgeLessonService.MAX_PAGE_SIZE, list.cursor()),
                    bot.getName(), scope.repoOwner() + "/" + scope.repoName(), mention, links));
            default -> throw new IllegalStateException("Unexpected value: " + command);
        };
    }

    /** Why the actor may not run this management command right now, or {@code null}. */
    private static String refusal(LearningCommand.Management command,
                                  AgentReviewLearningSettings settings, boolean maintainer) {
        if (!maintainer) {
            return "Only learning maintainers can change lessons. They are configured per workflow "
                    + "configuration under Agentic PR Review → Learning maintainers; permission to "
                    + "give feedback does not include it.";
        }
        boolean needsCuration = command instanceof LearningCommand.Propose
                || command instanceof LearningCommand.Revise
                || command instanceof LearningCommand.Activate;
        if (needsCuration && !settings.curatedLearning()) {
            return "Curated learning is off for this bot (learning mode "
                    + settings.learningMode().name() + "), so lessons cannot be proposed, revised "
                    + "or activated. Inspecting, rejecting and disabling still work.";
        }
        return null;
    }

    private interface Render {
        String apply(KnowledgeLessonService.Detail detail, KnowledgeLessonService.Recorded recorded);
    }

    /**
     * A new transition is confirmed. A redelivery is confirmed again only when the earlier
     * confirmation was refused by the provider — and only by the one delivery that claims the retry.
     */
    private Reply confirmation(FeedbackScope scope, KnowledgeLessonService.Recorded recorded,
                               Render render) {
        if (recorded.duplicate()
                && !(recorded.acknowledgement() == FeedbackAcknowledgement.FAILED
                && lessonService.claimAcknowledgementRetry(recorded.acknowledgementEventId()))) {
            return null;
        }
        String body = lessonService.show(scope, recorded.lessonReference())
                .map(detail -> render.apply(detail, recorded))
                .orElse("Done: " + recorded.revisionReference() + ".");
        return new Reply(body, recorded.acknowledgementEventId());
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
            lessonService.markAcknowledgement(reply.acknowledgementEventId(), outcome);
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
            log.warn("[Bot '{}'] Cannot answer the lesson command: no pull request number",
                    bot.getName());
            return FeedbackAcknowledgement.FAILED;
        }
        try {
            repositoryPlatformFactory.getApiClient(bot.getGitIntegration())
                    .postPullRequestComment(owner, repo, number, body);
            return FeedbackAcknowledgement.POSTED;
        } catch (RuntimeException e) {
            FeedbackAcknowledgement outcome = DeliveryFailures.isDefiniteRefusal(e)
                    ? FeedbackAcknowledgement.FAILED : FeedbackAcknowledgement.UNCERTAIN;
            log.warn("[Bot '{}'] Lesson command was handled but the reply could not be posted "
                    + "({}): {}", bot.getName(), outcome, e.getMessage());
            return outcome;
        }
    }

    private FeedbackLinks linksOf(Bot bot, FeedbackScope scope) {
        if (bot.getGitIntegration() == null) {
            return FeedbackLinks.NONE;
        }
        try {
            return FeedbackLinks.of(repositoryPlatformFactory.getPlatform(bot.getGitIntegration()),
                    scope.repoOwner(), scope.repoName());
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] No links for lesson overviews: {}", bot.getName(), e.getMessage());
            return FeedbackLinks.NONE;
        }
    }

    private String aliasOf(Bot bot, WebhookPayload payload) {
        if (bot.getGitIntegration() == null) {
            return null;
        }
        try {
            return new BotIdentity(repositoryPlatformFactory.getPlatform(bot.getGitIntegration())
                    .getUser(payload.ownerLogin())).alias();
        } catch (IllegalStateException e) {
            log.debug("[Bot '{}'] Bot account unavailable for lesson commands: {}",
                    bot.getName(), e.getMessage());
            return null;
        }
    }

    private static Source sourceOf(WebhookPayload payload, PullRequestCommentKind commentKind) {
        if (payload == null || commentKind == null) {
            return null;
        }
        if (commentKind == PullRequestCommentKind.REVIEW) {
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

    private static FeedbackScope scopeOf(Bot bot, WebhookPayload payload) {
        Long integrationId = bot.getGitIntegration() == null ? null : bot.getGitIntegration().getId();
        return new FeedbackScope(integrationId, bot.getId(),
                payload.getRepository().getOwner().getLogin(), payload.getRepository().getName(),
                PrPayloadHydrator.resolvePrNumber(payload));
    }

    /** The identity of the commanding comment; its source key makes redelivery harmless. */
    private static FeedbackSource feedbackSourceOf(WebhookPayload payload,
                                                   PullRequestCommentKind commentKind,
                                                   Source source, FeedbackScope scope) {
        Long repositoryId = payload.getRepository().getId();
        return new FeedbackSource(scope.gitIntegrationId(), scope.botId(), scope.repoOwner(),
                scope.repoName(), repositoryId == null ? null : String.valueOf(repositoryId),
                FeedbackSource.SubjectKind.PR, scope.prNumber(), commentKind.name(), source.id());
    }
}
