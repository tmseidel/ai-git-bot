package org.remus.giteabot.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryApiClientFactory;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.model.CommentId;
import org.springframework.stereotype.Component;

/**
 * Best-effort 👀 reaction on the issue comment that triggered an issue
 * workflow — the same acknowledgement the slash-command handlers use.
 *
 * <p>Failures (missing permission, provider quirks) are logged and never
 * affect the workflow. Kept out of the orchestrator so it only ever handles
 * repository coordinates, not the repository API surface.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueCommentAcknowledgement {

    private static final String ACKNOWLEDGEMENT_EMOJI = "eyes";

    private final RepositoryApiClientFactory repositoryApiClientFactory;

    public void acknowledge(Bot bot, WebhookPayload payload) {
        if (payload.getRepository() == null || payload.getComment() == null
                || payload.getComment().getId() == null) {
            return;
        }
        CommentId commentId = payload.getComment().getId();
        String owner = payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository().getName();
        try {
            repositoryApiClientFactory.getApiClient(bot.getGitIntegration())
                    .addReaction(owner, repo, commentId, ACKNOWLEDGEMENT_EMOJI);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to add 👀 reaction to comment #{}: {}",
                    bot.getName(), commentId, e.getMessage());
        }
    }
}
