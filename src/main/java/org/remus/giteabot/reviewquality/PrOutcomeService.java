package org.remus.giteabot.reviewquality;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Records whether a pull request was merged or closed, for the "blockers at merge" and
 * time-to-merge figures of the Review quality page. Only facts from the webhook are stored; a
 * payload without bot, integration, repository or number is ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrOutcomeService {

    private final PrOutcomeRepository repository;
    private final Clock clock = Clock.systemUTC();

    /** The pull request of {@code payload} was closed; merged when the payload says so. */
    @Transactional
    public void closed(Bot bot, WebhookPayload payload) {
        boolean merged = payload.getPullRequest() != null
                && Boolean.TRUE.equals(payload.getPullRequest().getMerged());
        record(bot, payload, merged ? PrOutcome.State.MERGED : PrOutcome.State.CLOSED);
    }

    /** The pull request of {@code payload} was reopened: it is open again. */
    @Transactional
    public void reopened(Bot bot, WebhookPayload payload) {
        record(bot, payload, PrOutcome.State.OPEN);
    }

    private void record(Bot bot, WebhookPayload payload, PrOutcome.State state) {
        Key key = keyOf(bot, payload);
        if (key == null) {
            return;
        }
        Instant now = clock.instant();
        PrOutcome outcome = repository.findByGitIntegrationIdAndBotIdAndRepoOwnerAndRepoNameAndPrNumber(
                key.integration(), key.bot(), key.owner(), key.repo(), key.number()).orElse(null);
        if (outcome == null) {
            if (state == PrOutcome.State.OPEN) {
                return;
            }
            outcome = new PrOutcome();
            outcome.setGitIntegrationId(key.integration());
            outcome.setBotId(key.bot());
            outcome.setRepoOwner(key.owner());
            outcome.setRepoName(key.repo());
            outcome.setPrNumber(key.number());
        }
        outcome.setState(state);
        outcome.setClosedAt(state == PrOutcome.State.OPEN ? null : now);
        outcome.setUpdatedAt(now);
        try {
            repository.saveAndFlush(outcome);
        } catch (DataIntegrityViolationException e) {
            // A concurrent delivery of the same event recorded it first.
            log.debug("Outcome of {}/{}#{} was recorded concurrently", key.owner(), key.repo(), key.number());
        }
    }

    private static Key keyOf(Bot bot, WebhookPayload payload) {
        if (bot == null || bot.getId() == null || bot.getGitIntegration() == null
                || bot.getGitIntegration().getId() == null || payload == null
                || payload.getPullRequest() == null || payload.getPullRequest().getNumber() == null
                || payload.getRepository() == null || payload.getRepository().getName() == null
                || payload.ownerLogin() == null) {
            return null;
        }
        return new Key(bot.getGitIntegration().getId(), bot.getId(),
                payload.ownerLogin().toLowerCase(Locale.ROOT),
                payload.getRepository().getName().toLowerCase(Locale.ROOT),
                payload.getPullRequest().getNumber());
    }

    private record Key(Long integration, Long bot, String owner, String repo, Long number) {
    }
}
