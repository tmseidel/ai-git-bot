package org.remus.giteabot.admin.configexchange;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.eventhook.EventHookEndpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The bot an outgoing-webhook endpoint is scoped to, in document form.
 *
 * <p>{@link EventHookEndpoint#getBotId()} is an internal id, so it is stripped like any other
 * id — and a null scope column means <em>global</em> ({@code matchesScope}): an export that
 * simply dropped it turned a bot-scoped endpoint into one that receives every bot's events.
 * The document therefore carries {@code "bot": {"name": …}}, the same shape every
 * {@code @ManyToOne} reference uses, and an import resolves it back to the row that name
 * refers to.</p>
 *
 * <p>Handled here rather than by {@link AssociationResolver} because {@code botId} is a plain
 * column, not a {@code @ManyToOne}: making it an association would change the dispatch query
 * that matches an event against the endpoints scoped to its bot.</p>
 *
 * <p>Semantics follow the rest of the document: only a reference the document declares sets
 * the scope. An endpoint whose section has no {@code bot} key keeps whatever scope the row
 * already had, which is what makes a partial override possible.</p>
 */
@Slf4j
final class EventHookEndpointScope {

    static final String FIELD = "bot";

    private EventHookEndpointScope() {
    }

    /**
     * Replaces the internal id with the bot's name.
     *
     * @param botName id → name, so an export does not need the bot entity in hand
     */
    static void export(Object entity, ObjectNode node, Function<Long, Optional<String>> botName) {
        if (!(entity instanceof EventHookEndpoint endpoint) || endpoint.getBotId() == null) {
            return;
        }
        Optional<String> name = botName.apply(endpoint.getBotId());
        if (name.isEmpty()) {
            // A dangling id: the row already matches no bot, so exporting it unscoped is what
            // the stored state means. Say so instead of leaving a mystery in the next instance.
            log.warn("Config export: event hook '{}' is scoped to bot id {}, which does not exist "
                    + "— exporting it unscoped", endpoint.getName(), endpoint.getBotId());
            return;
        }
        node.putObject(FIELD).put("name", name.get());
    }

    /** Applies a declared {@code bot} reference; throws when it names a bot nothing declares. */
    static void apply(Object entity, JsonNode node,
                      Map<Class<?>, Function<String, Optional<Object>>> lookups) {
        if (!(entity instanceof EventHookEndpoint endpoint)) {
            return;
        }
        JsonNode reference = node.path(FIELD);
        if (!reference.isObject()) {
            return;
        }
        String name = reference.path("name").asString(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'bot' needs a name");
        }
        Function<String, Optional<Object>> lookup = lookups.get(Bot.class);
        Object resolved = lookup == null ? null : lookup.apply(name).orElse(null);
        if (!(resolved instanceof Bot bot) || bot.getId() == null) {
            throw new IllegalArgumentException("'bot' references Bot '" + name
                    + "', which the document does not declare");
        }
        endpoint.setBotId(bot.getId());
    }
}
