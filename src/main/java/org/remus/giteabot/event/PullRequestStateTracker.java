package org.remus.giteabot.event;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
import org.springframework.stereotype.Component;

/**
 * Remembers what the previous webhook said about each pull request, for providers whose
 * "pull request updated" event does not say what changed.
 * <p>
 * Bitbucket Cloud's {@code pullrequest:updated} and Azure DevOps'
 * {@code git.pullrequest.updated} fire alike for pushes, title and description edits,
 * reviewer changes and votes, and Azure DevOps also for an abandoned pull request being
 * reactivated. Every distinction is therefore drawn by comparing an event with the one
 * before it; each method below owns one of the recorded facts:
 * <ul>
 *   <li>{@link #headMoved} — the source-branch commit, to tell a push from an edit;</li>
 *   <li>{@link #reviewerAdded} — whether the bot was a reviewer, to tell a fresh review
 *       request from a later event on a pull request the bot already reviews;</li>
 *   <li>{@link #markClosed} / {@link #consumeReopened} — whether the pull request was
 *       closed, to recognise a reactivation.</li>
 * </ul>
 * The state is in-memory and per node: after a restart, or when events for one pull
 * request are spread across several instances, the first event seen for a pull request
 * is taken at face value — it counts as a push, and a bot listed as reviewer counts as
 * newly requested. That costs at most one redundant review and never drops a real one.
 * The state lives in a capped {@link PullRequestStateMap}.
 */
@Slf4j
@Component
public class PullRequestStateTracker {

    /** Upper bound on tracked pull requests; the least recently touched entry is dropped. */
    static final int MAX_TRACKED_PULL_REQUESTS = 5000;

    /** What the previous event said about one pull request. */
    private record State(String headSha, boolean botIsReviewer, boolean closed) {

        /** The state of a pull request no event has been seen for yet. */
        static final State NOTHING = new State(null, false, false);
    }

    private final PullRequestStateMap<State> states = new PullRequestStateMap<>(MAX_TRACKED_PULL_REQUESTS);

    /**
     * Records the pull request's current head sha and reports whether it differs from the
     * previously recorded one.
     *
     * @return {@code true} when the head moved, when the pull request has not been seen
     *         before, or when the payload carries no head sha (silently dropping a push
     *         would be worse than an occasional redundant review)
     */
    public boolean headMoved(Bot bot, WebhookPayload payload) {
        String headSha = PullRequestStateMap.headSha(payload);
        if (headSha == null) {
            log.debug("No head sha in payload for PR #{}; treating the event as a push", payload.getNumber());
            return true;
        }
        synchronized (states) {
            State previous = previous(bot, payload);
            states.put(bot, payload, new State(headSha, previous.botIsReviewer(), previous.closed()));
            return !headSha.equals(previous.headSha());
        }
    }

    /**
     * Records whether the bot is currently among the pull request's reviewers and reports
     * whether it has just been added, i.e. is a reviewer now but was not at the previous
     * event. Callers whose payload may omit the reviewer list must not call this for such
     * a payload, since the omission would read as a removal and the next full payload as a
     * fresh request.
     */
    public boolean reviewerAdded(Bot bot, WebhookPayload payload, boolean botIsReviewer) {
        synchronized (states) {
            State previous = previous(bot, payload);
            states.put(bot, payload, new State(previous.headSha(), botIsReviewer, previous.closed()));
            return botIsReviewer && !previous.botIsReviewer();
        }
    }

    /**
     * Records that the pull request was closed: the head sha and the reviewer flag are
     * forgotten, so a reactivation at the same commit is not mistaken for a duplicate,
     * while the closure itself is remembered for {@link #consumeReopened}.
     */
    public void markClosed(Bot bot, WebhookPayload payload) {
        states.put(bot, payload, new State(null, false, true));
    }

    /**
     * Reports whether this event follows the pull request having been seen closed, and
     * clears the flag so only the first event after a reactivation counts.
     */
    public boolean consumeReopened(Bot bot, WebhookPayload payload) {
        synchronized (states) {
            State previous = previous(bot, payload);
            if (!previous.closed()) {
                return false;
            }
            states.put(bot, payload, new State(previous.headSha(), previous.botIsReviewer(), false));
            return true;
        }
    }

    private State previous(Bot bot, WebhookPayload payload) {
        State previous = states.get(bot, payload);
        return previous != null ? previous : State.NOTHING;
    }
}
