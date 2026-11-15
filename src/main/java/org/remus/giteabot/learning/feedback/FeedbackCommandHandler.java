package org.remus.giteabot.learning.feedback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Consumes {@code @bot feedback …} commands from Git comments.
 *
 * <p>Every recognized command is consumed here: it either records/reads feedback or answers with a
 * short explanation. Nothing is forwarded to an AI conversation, so a malformed or unsupported
 * command can never start code review, change code, generate tests, or continue a coding session.
 * Comments that carry no feedback command return {@code false} and keep their ordinary routing.</p>
 *
 * <p>The answer is posted best-effort after the database write has committed: a failed
 * acknowledgement must not discard feedback, and a duplicate delivery must not confirm twice.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackCommandHandler {

    public static final String COMMENT_KIND_PR_COMMENT = "PR_COMMENT";
    public static final String COMMENT_KIND_INLINE = "INLINE_COMMENT";

    private final FeedbackService feedbackService;
    private final FeedbackAuthorizationService authorizationService;
    private final AgentReviewLearningSettingsResolver learningSettingsResolver;
    private final RepositoryPlatformFactory repositoryPlatformFactory;

    /**
     * @param commentKind   which kind of provider comment carried the command, part of the dedupe key
     * @param effectiveAlias the mention the provider left in the comment body, or {@code null} when it
     *                       did not rewrite it. Bitbucket replaces {@code @{account_id}} with a
     *                       readable name, so the configured username alone would never match
     * @return {@code true} when the comment was a feedback command and has been consumed
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload, String commentKind,
                             String effectiveAlias) {
        if (bot == null || payload == null || payload.getComment() == null) {
            return false;
        }
        String body = payload.getComment().getBody();
        String configured = aliasOf(bot);
        String alias = effectiveAlias == null || effectiveAlias.isBlank() ? configured : effectiveAlias;
        Optional<FeedbackCommand> parsed = FeedbackCommandParser.parse(body, alias, configured);
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
        // this approach OK?" keeps the routing it had before feedback commands existed — a coding
        // session, a clarification, an ordinary command.
        if (!collecting && !FeedbackCommandParser.addressesFeedback(body, alias, configured)) {
            return false;
        }

        String actor = actorOf(payload);
        String mention = alias;
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
        try {
            respond(bot, payload, execute(bot, payload, commentKind, parsed.get(), actor, mention));
        } catch (FeedbackRejectedException rejected) {
            // Written for the commenter: the suggestion to show it is the whole point of the type.
            respond(bot, payload, FeedbackReplyRenderer.rejected(rejected));
        } catch (RuntimeException e) {
            // Details stay in the log. An exception message can carry SQL, entity names or a provider
            // error body, and this reply is published on the pull request (CWE-209).
            log.error("[Bot '{}'] Feedback command failed: {}", bot.getName(), e.getMessage(), e);
            respond(bot, payload, "The feedback command could not be completed. "
                    + "The details were written to the bot log.");
        }
        return true;
    }

    private String execute(Bot bot, WebhookPayload payload, String commentKind,
                           FeedbackCommand command, String actor, String mention) {
        if (command instanceof FeedbackCommand.Help help) {
            return FeedbackReplyRenderer.help(help.message());
        }
        FeedbackScope scope = scopeOf(bot, payload);
        return switch (command) {
            // A redelivered webhook resolves to the existing record; answering again would post a
            // second confirmation for one comment, so the acknowledgement is skipped entirely.
            case FeedbackCommand.Submit submit -> confirmed(
                    feedbackService.submit(scope, sourceOf(payload, commentKind, scope),
                            submit.target(), submit.verdict(), submit.reason(), actor),
                    FeedbackReplyRenderer::acknowledged);
            case FeedbackCommand.Retract retract -> confirmed(
                    feedbackService.retract(scope, retract.reference(), actor),
                    FeedbackReplyRenderer::retracted);
            case FeedbackCommand.Show show -> feedbackService.show(scope, show.reference())
                    .map(summary -> FeedbackReplyRenderer.show(summary, mention))
                    .orElse("No feedback " + show.reference() + " is available here.");
            case FeedbackCommand.ListRecords list -> renderList(scope, list, mention);
            case FeedbackCommand.Help help -> FeedbackReplyRenderer.help(help.message());
        };
    }

    private static String confirmed(FeedbackService.Recorded recorded,
                                    java.util.function.Function<FeedbackService.Recorded, String> ack) {
        return recorded.duplicate() ? null : ack.apply(recorded);
    }

    private String renderList(FeedbackScope scope, FeedbackCommand.ListRecords list, String mention) {
        List<FeedbackService.Summary> rows = feedbackService.list(scope, list.repositoryWide(),
                FeedbackService.MAX_PAGE_SIZE, list.cursor());
        Long lastId = rows.size() < FeedbackService.MAX_PAGE_SIZE ? null : rows.getLast().id();
        return FeedbackReplyRenderer.list(list.repositoryWide(), rows, lastId, mention);
    }

    private boolean collectionEnabled(Bot bot) {
        AgentReviewLearningSettings settings = learningSettingsResolver.resolve(bot);
        return settings.feedbackEnabled();
    }

    private static String aliasOf(Bot bot) {
        return bot.getGitIntegration() == null || bot.getGitIntegration().getUsername() == null
                ? null : "@" + bot.getGitIntegration().getUsername();
    }

    private static String actorOf(WebhookPayload payload) {
        return payload.getComment() != null && payload.getComment().getUser() != null
                ? payload.getComment().getUser().getLogin() : null;
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
    private FeedbackSource sourceOf(WebhookPayload payload, String commentKind,
                                    FeedbackScope scope) {
        Long repositoryId = payload.getRepository().getId();
        return new FeedbackSource(scope.gitIntegrationId(), scope.botId(), scope.repoOwner(),
                scope.repoName(),
                repositoryId == null ? null : String.valueOf(repositoryId),
                FeedbackSource.SubjectKind.PR, scope.prNumber(), commentKind,
                payload.getComment().getId() == null ? null
                        : payload.getComment().getId().toString());
    }

    private void respond(Bot bot, WebhookPayload payload, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long number = PrPayloadHydrator.resolvePrNumber(payload);
        if (number == null) {
            log.warn("[Bot '{}'] Cannot acknowledge feedback: no pull request number", bot.getName());
            return;
        }
        try {
            RepositoryApiClient client =
                    repositoryPlatformFactory.getApiClient(bot.getGitIntegration());
            client.postPullRequestComment(owner, repo, number, body);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Feedback was handled but the reply could not be posted: {}",
                    bot.getName(), e.getMessage());
        }
    }
}
