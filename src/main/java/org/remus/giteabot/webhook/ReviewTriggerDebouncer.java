package org.remus.giteabot.webhook;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.PullRequestStateMap;
import org.remus.giteabot.event.model.WebhookPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Collapses the burst of webhooks a provider fires for one user action into a single
 * automatic review.
 * <p>
 * Opening a pull request with the bot as reviewer makes GitHub and Gitea send
 * {@code opened} together with {@code review_requested}; assigning the bot and requesting
 * it as reviewer in one go fires {@code assigned} and {@code review_requested}. Each asks
 * for a review of the same commit, and they arrive within seconds, in no guaranteed order
 * and processed concurrently. Without this, each of them would start its own review and
 * post its own comments.
 * <p>
 * An automatic review is therefore claimed per bot, pull request and head commit: the first
 * trigger wins, and further triggers for the same commit within {@link #WINDOW} are
 * dropped. The window is short on purpose — a reviewer re-requested minutes later is a
 * deliberate ask and is honoured — and closing the pull request ends it ({@link #forget}),
 * so a pull request reopened at the same commit is reviewed again. A review that was
 * skipped or failed gives its claim back ({@link #release}), so retrying right away
 * works. A payload without a head sha is never debounced, and an explicit "review again"
 * comment does not go through here.
 * <p>
 * The state is in-memory and per node, like
 * {@link org.remus.giteabot.event.PullRequestStateTracker}: events of one burst spread
 * over several instances are not collapsed.
 */
@Component
public class ReviewTriggerDebouncer {

    /** How long a review of one commit suppresses further automatic triggers for it. */
    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Upper bound on remembered triggers; the oldest entry is dropped. */
    static final int MAX_TRACKED_TRIGGERS = 5000;

    private final Clock clock;

    /** The last automatic review triggered for one pull request. */
    private record Trigger(String headSha, Instant at) {
    }

    private final PullRequestStateMap<Trigger> lastTriggers = new PullRequestStateMap<>(MAX_TRACKED_TRIGGERS);

    @Autowired
    public ReviewTriggerDebouncer() {
        this(Clock.systemUTC());
    }

    ReviewTriggerDebouncer(Clock clock) {
        this.clock = clock;
    }

    /**
     * Claims the automatic review of the payload's head commit.
     *
     * @return {@code true} when the review should run; {@code false} when one was already
     *         triggered for this commit within {@link #WINDOW}
     */
    public boolean tryClaim(Bot bot, WebhookPayload payload) {
        String sha = PullRequestStateMap.headSha(payload);
        if (sha == null) {
            return true;
        }
        Instant now = clock.instant();
        synchronized (lastTriggers) {
            Trigger previous = lastTriggers.get(bot, payload);
            if (previous != null && previous.headSha().equals(sha) && previous.at().plus(WINDOW).isAfter(now)) {
                return false;
            }
            lastTriggers.put(bot, payload, new Trigger(sha, now));
            return true;
        }
    }

    /**
     * Gives up the claim on the payload's head commit, because the review it guarded was
     * skipped or failed: a re-request within {@link #WINDOW} must then run instead of being
     * dropped. A claim since taken for another commit is left alone.
     */
    public void release(Bot bot, WebhookPayload payload) {
        String sha = PullRequestStateMap.headSha(payload);
        if (sha == null) {
            return;
        }
        synchronized (lastTriggers) {
            Trigger current = lastTriggers.get(bot, payload);
            if (current != null && current.headSha().equals(sha)) {
                lastTriggers.remove(bot, payload);
            }
        }
    }

    /** Ends the pull request's window: it was closed, so a reopen is a new request. */
    public void forget(Bot bot, WebhookPayload payload) {
        lastTriggers.remove(bot, payload);
    }
}
