package org.remus.giteabot.ai;

/**
 * Thread-local context carrying the logical session identifier (e.g.
 * {@code owner/repo#42}) of the work currently being processed, so that AI
 * usage and error records can be correlated with the originating session.
 *
 * <p>Callers that orchestrate AI interactions (webhook handlers, PR workflows)
 * set the session id at the start of processing and must clear it in a
 * {@code finally} block.</p>
 */
public final class AiAuditContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AiAuditContext() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
