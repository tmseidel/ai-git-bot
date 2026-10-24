package org.remus.giteabot.eventhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned outgoing-webhook envelope (schema v1). Serialized with Jackson;
 * additive fields stay within a version, breaking changes bump
 * {@code schemaVersion}.
 */
public record EventHookPayload(
        int schemaVersion,
        String id,
        String eventType,
        Instant timestamp,
        Actor actor,
        Integration integration,
        RepositoryRef repository,
        Map<String, Object> data,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eventKey) {

    public record Actor(String type, String id) {
    }

    public record Integration(Long botId, String botName, String platform) {
    }

    public record RepositoryRef(String owner, String name, Long pullRequest, Long issue) {
    }

    public static EventHookPayload of(String deliveryUuid, EventHookEventType type,
                                      Bot bot, String owner, String repo,
                                      Long prNumber, Long issueNumber,
                                      Map<String, Object> data) {
        return new EventHookPayload(1, deliveryUuid, type.wireValue(), Instant.now(),
                new Actor("BOT", bot.getName()),
                new Integration(bot.getId(), bot.getName(), platformOf(bot)),
                new RepositoryRef(owner, repo, prNumber, issueNumber),
                data == null ? Map.of() : data, null);
    }

    /** Required envelope; event identity survives retries and process restarts. */
    public static EventHookPayload required(String eventKey, String deliveryUuid, Instant timestamp,
                                            EventHookEventType type, Bot bot, String owner, String repo,
                                            Long prNumber, Map<String, Object> data) {
        return new EventHookPayload(1, deliveryUuid, type.wireValue(), timestamp,
                new Actor("BOT", bot.getName()),
                new Integration(bot.getId(), bot.getName(), platformOf(bot)),
                new RepositoryRef(owner, repo, prNumber, null),
                data == null ? Map.of() : data, eventKey);
    }

    /** Lowercase provider type (e.g. "gitea"); null when the bot has no git integration (tests). */
    private static String platformOf(Bot bot) {
        GitIntegration git = bot.getGitIntegration();
        return git != null && git.getProviderType() != null
                ? git.getProviderType().name().toLowerCase(Locale.ROOT)
                : null;
    }
}
