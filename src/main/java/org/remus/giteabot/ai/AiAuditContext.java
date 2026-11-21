package org.remus.giteabot.ai;

/**
 * Thread-local context that correlates AI interactions with what caused them: the open
 * {@link AuditRun workflow run} of the current thread. It receives the AI interactions and tool
 * calls, and its {@link AuditTrigger trigger} provides the session identifier (e.g.
 * {@code owner/repo#42}).
 *
 * <p>The PR and issue workflow orchestrators open a run per workflow with try-with-resources.
 * Runs do not nest: workflows run one after another, and every AI call of a webhook task is made
 * inside one of them. Webhook handlers clear the context in a {@code finally} block, so a run can
 * never outlive its task.</p>
 */
public final class AiAuditContext {

    private static final ThreadLocal<AuditRun> CURRENT = new ThreadLocal<>();

    private AiAuditContext() {
    }

    /**
     * The session id ({@code owner/repo#n}) of the open run's trigger, or {@code null} outside a
     * run.
     */
    public static String getSessionId() {
        AuditRun run = CURRENT.get();
        return run != null ? run.getTrigger().sessionId() : null;
    }

    /**
     * Opens a workflow run on this thread. It receives the AI interactions until it is closed.
     *
     * @param workflowType the workflow key (e.g. {@code agentic-review})
     * @param trigger      the bot and the pull request / issue it works on
     */
    public static AuditRun openRun(String workflowType, AuditTrigger trigger) {
        AuditRun run = new AuditRun(workflowType, trigger);
        CURRENT.set(run);
        return run;
    }

    /** The open run, or {@code null}. */
    public static AuditRun currentRun() {
        return CURRENT.get();
    }

    /** Called by {@link AuditRun#close()}: forgets the run if it is still the open one. */
    static void removeRun(AuditRun run) {
        if (CURRENT.get() == run) {
            CURRENT.remove();
        }
    }

    /** Starts a logical AI call in the open run (see {@link AuditRun#beginCall}). */
    public static void beginCall() {
        AuditRun run = CURRENT.get();
        if (run != null) {
            run.beginCall();
        }
    }

    /** The next AI call repeats the previous one and keeps its round number. */
    public static void markRetry() {
        AuditRun run = CURRENT.get();
        if (run != null) {
            run.markRetry();
        }
    }

    /** The agent loop of the open run ran out of rounds. */
    public static void markRoundLimitReached() {
        AuditRun run = CURRENT.get();
        if (run != null) {
            run.markRoundLimitReached();
        }
    }

    /** Closes the open run, if any (persisting its end), and clears the context. */
    public static void clear() {
        AuditRun run = CURRENT.get();
        if (run != null) {
            run.close();
        }
        CURRENT.remove();
    }
}
