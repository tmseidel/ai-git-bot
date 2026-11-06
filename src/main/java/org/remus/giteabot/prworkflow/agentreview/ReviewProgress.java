package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.AgentRunContext.ProgressUpdate;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/** Owns one run's optional progress comment without changing the review outcome. */
@Slf4j
final class ReviewProgress implements Consumer<ProgressUpdate> {

    /** Activity updates are at least 15 seconds apart; the first activity and terminal state are immediate. */
    private static final Duration UPDATE_INTERVAL = Duration.ofSeconds(15);
    /** Uses an explicit UTC timezone and omits fractional seconds for readability. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final RepositoryApiClient repositoryClient;
    private final String owner;
    private final String repo;
    private final Long prNumber;
    private final Clock clock;
    private final Instant startedAt;
    private CommentId commentId;
    private Instant lastActivityAt;
    private String activity = "Preparing repository";
    private int round;
    private int completedToolCalls;

    ReviewProgress(RepositoryApiClient repositoryClient, String owner, String repo, Long prNumber, Clock clock) {
        this.repositoryClient = repositoryClient;
        this.owner = owner;
        this.repo = repo;
        this.prNumber = prNumber;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    void start() {
        try {
            commentId = repositoryClient.postPullRequestComment(owner, repo, prNumber,
                    render(clock.instant()));
            if (commentId == null) {
                log.warn("Progress comment for PR #{} in {}/{} has no usable ID; continuing without progress",
                        prNumber, owner, repo);
            }
        } catch (RuntimeException e) {
            log.warn("Could not create progress comment for PR #{} in {}/{}; continuing without progress",
                    prNumber, owner, repo, e);
        }
    }

    @Override
    public void accept(ProgressUpdate update) {
        if (commentId == null) {
            return;
        }
        activity = update.activity();
        round = update.round();
        completedToolCalls += update.completedToolCalls();
        Instant now = clock.instant();
        if (lastActivityAt != null && Duration.between(lastActivityAt, now).compareTo(UPDATE_INTERVAL) < 0) {
            return;
        }
        lastActivityAt = now;
        updateComment(now);
    }

    void finish(boolean success) {
        if (commentId != null) {
            activity = success ? "Completed" : "Failed";
            updateComment(clock.instant());
        }
    }

    private void updateComment(Instant now) {
        try {
            repositoryClient.updatePullRequestComment(owner, repo, prNumber, commentId, render(now));
        } catch (RuntimeException e) {
            log.warn("Could not update progress comment {} for PR #{} in {}/{}; review result is unaffected",
                    commentId, prNumber, owner, repo, e);
        }
    }

    private String render(Instant now) {
        StringBuilder body = new StringBuilder("## 🤖 Agentic Review Progress\n\n");
        body.append("Tracking the review progress for **PR #").append(prNumber).append("**.\n\n")
                .append("- Latest activity: ").append(activity)
                .append("\n- Rounds: ").append(round)
                .append(" · Inspection tool calls completed: ").append(completedToolCalls);
        Duration elapsed = Duration.between(startedAt, now);
        body.append("\n- Started: ").append(TIMESTAMP.format(startedAt))
                .append("\n- Elapsed: ");
        if (elapsed.toHours() > 0) {
            body.append(elapsed.toHours()).append("h ");
        }
        if (elapsed.toMinutes() > 0) {
            body.append(elapsed.toMinutesPart()).append("m ");
        }
        return body.append(elapsed.toSecondsPart()).append("s · Last update: ")
                .append(TIMESTAMP.format(now)).toString();
    }
}
