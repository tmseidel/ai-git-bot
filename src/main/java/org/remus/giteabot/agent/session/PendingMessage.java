package org.remus.giteabot.agent.session;

/**
 * An in-flight conversation message that has not yet been persisted.
 *
 * <p>{@link org.remus.giteabot.agent.loop.AgentLoop} accumulates these during a
 * single round and hands the batch to
 * {@link AgentSessionService#flushMessages} so the round's messages are written
 * in one transaction instead of one transaction per message.</p>
 *
 * @param role    the message role (e.g. {@code "user"}, {@code "assistant"}, {@code "tool"})
 * @param content the message content
 */
public record PendingMessage(String role, String content) {
}
