package org.remus.giteabot.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local comment target for provider-overload retry notices: the
 * workflow (or issue workflow) that is currently running on this thread
 * installs a {@link Notice}, so {@link RetryAiClient} can tell the affected
 * pull request or issue when the next attempt happens.
 *
 * <p>The notices are <em>stacked</em>, not single-slot: a flow that starts
 * inside another one on the same thread installs its own level, so its
 * {@link #clear()} restores the enclosing notice instead of dropping it.
 * Callers that install a notice must still {@link #clear()} it in a
 * {@code finally} block — and must install it <em>inside</em> the guarded
 * region, so the level can never outlive the run. When no notice is installed
 * (e.g. an admin "test connection" call), retries still happen — only the
 * comment is skipped.</p>
 */
public final class AiRetryContext {

    /** Posts one retry notice; implementations must never throw. */
    @FunctionalInterface
    public interface NoticeSink {
        void post(String markdown);
    }

    /**
     * Where retry notices for the current workflow run are posted.
     *
     * @param label human-readable name of the workflow, e.g. {@code agentic-review}
     * @param sink  the comment target
     */
    public record Notice(String label, NoticeSink sink) {
    }

    /** Nesting levels of this thread; the head is the active notice. */
    private static final ThreadLocal<Deque<State>> LEVELS = new ThreadLocal<>();

    private AiRetryContext() {
    }

    /**
     * Installs {@code notice} as the active notice of this thread, in front of
     * any notice already installed there. Every level owns its own notice
     * bookkeeping (cooldown window, exhausted flag).
     */
    public static void install(Notice notice) {
        Deque<State> levels = LEVELS.get();
        if (levels == null) {
            levels = new ArrayDeque<>(2);
            LEVELS.set(levels);
        }
        levels.push(new State(notice));
    }

    /** The active notice, or {@code null} when this thread has none. */
    public static Notice notice() {
        State state = state();
        return state == null ? null : state.notice;
    }

    /**
     * Drops the active notice and restores the notice that was active when it
     * was installed, if any. A no-op when this thread has no notice installed.
     */
    public static void clear() {
        Deque<State> levels = LEVELS.get();
        if (levels == null || levels.isEmpty()) {
            return;
        }
        levels.pop();
        if (levels.isEmpty()) {
            LEVELS.remove();
        }
    }

    static State state() {
        Deque<State> levels = LEVELS.get();
        return levels == null ? null : levels.peek();
    }

    /**
     * Per-run notice bookkeeping: at most one "retry scheduled" comment per
     * cooldown window and at most one "retries exhausted" comment.
     */
    static final class State {

        final Notice notice;
        private Instant lastNoticeAt;
        private boolean exhaustedNotified;

        State(Notice notice) {
            this.notice = notice;
        }

        boolean claimScheduledNotice(Duration cooldown, Instant now) {
            if (lastNoticeAt != null && now.isBefore(lastNoticeAt.plus(cooldown))) {
                return false;
            }
            lastNoticeAt = now;
            return true;
        }

        boolean claimExhaustedNotice() {
            if (exhaustedNotified) {
                return false;
            }
            exhaustedNotified = true;
            return true;
        }
    }
}
