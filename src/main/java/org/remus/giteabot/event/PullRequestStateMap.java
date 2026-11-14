package org.remus.giteabot.event;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory state per bot and pull request, for webhook handling that compares an event
 * with earlier ones.
 * <p>
 * Capped at a fixed number of pull requests, dropping the least recently touched one, so
 * it cannot grow without bound. Single calls are thread-safe; a read-modify-write must
 * synchronize on the map instance, which these methods lock as well.
 *
 * @param <V> the state kept per pull request
 */
public final class PullRequestStateMap<V> {

    private final Map<String, V> entries;

    public PullRequestStateMap(int maxEntries) {
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The state recorded for the payload's pull request, or {@code null}. */
    public synchronized V get(Bot bot, WebhookPayload payload) {
        return entries.get(key(bot, payload));
    }

    public synchronized void put(Bot bot, WebhookPayload payload, V state) {
        entries.put(key(bot, payload), state);
    }

    public synchronized void remove(Bot bot, WebhookPayload payload) {
        entries.remove(key(bot, payload));
    }

    /** The commit at the head of the payload's pull request, or {@code null} when it carries none. */
    public static String headSha(WebhookPayload payload) {
        WebhookPayload.PullRequest pullRequest = payload.getPullRequest();
        if (pullRequest == null || pullRequest.getHead() == null) {
            return null;
        }
        String sha = pullRequest.getHead().getSha();
        return sha == null || sha.isBlank() ? null : sha;
    }

    private static String key(Bot bot, WebhookPayload payload) {
        WebhookPayload.Repository repository = payload.getRepository();
        String owner = repository != null && repository.getOwner() != null ? repository.getOwner().getLogin() : null;
        String repo = repository != null ? repository.getName() : null;
        Long number = payload.getNumber() != null || payload.getPullRequest() == null
                ? payload.getNumber()
                : payload.getPullRequest().getNumber();
        return bot.getId() + ":" + owner + "/" + repo + "#" + number;
    }
}
