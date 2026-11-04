package org.remus.giteabot.admin.configexchange;

import jakarta.persistence.ManyToOne;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AdminService;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.Map;

/**
 * Reads and writes the instance configuration document.
 *
 * <p>The document is a projection of the entity model: one array per importable table,
 * entities serialized with their own field names, {@code @ManyToOne} references
 * reduced to {@code {"name": …}}. That is why there is no DTO and no schema version — a
 * new entity field flows through both directions without a code change, and an
 * unknown key is simply ignored on import.</p>
 *
 * <p>Import semantics (see {@code doc/DEPLOYMENT.md}): validate, strip identity,
 * resolve references, then save through the owning service. A row whose name already
 * exists is overridden field by field instead of created again — either a Flyway seed or a
 * row an earlier import created, which is what makes importing the same document twice
 * create nothing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigExchangeService {

    private static final ObjectMapper MAPPER = ConfigExchangeJson.mapper();

    private final ConfigExchangeRegistry registry;
    private final AdminService adminService;
    private final SystemPromptService systemPromptService;
    private final BotRepository botRepository;

    /**
     * Applies every section of the document in registry order.
     *
     * @return the number of rows created, for the boot log
     * @throws IllegalArgumentException on the first invalid entry, naming section and
     *         index; nothing after it is applied
     */
    @Transactional
    public int importDocument(JsonNode root) {
        int created = 0;
        Map<Class<?>, Function<String, Optional<Object>>> lookups = registry.lookups();
        Set<String> declared = new HashSet<>();
        for (ConfigTable<?> table : registry.tables()) {
            JsonNode section = root.path(table.jsonKey());
            if (!section.isArray()) {
                continue;
            }
            created += importSection(table, section, lookups, declared);
        }
        return created;
    }

    private <T> int importSection(ConfigTable<T> table, JsonNode section,
                                  Map<Class<?>, Function<String, Optional<Object>>> lookups,
                                  Set<String> declared) {
        int created = 0;
        int index = 0;
        for (JsonNode node : section) {
            int position = index++;
            try {
                if (table.save() == null) {
                    created += importAdminUser(node);
                } else if (importOne(table, node, lookups, declared)) {
                    created++;
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        table.jsonKey() + "[" + position + "]: " + e.getMessage(), e);
            }
        }
        return created;
    }

    private <T> boolean importOne(ConfigTable<T> table, JsonNode node,
                                  Map<Class<?>, Function<String, Optional<Object>>> lookups,
                                  Set<String> declared) {
        T entity = MAPPER.treeToValue(node, table.type());
        EntityIdentity.strip(entity);
        AssociationResolver.resolve(entity, lookups);
        EventHookEndpointScope.apply(entity, node, lookups);
        applyDefaults(table.type(), entity);
        AssociationResolver.linkChildren(entity);
        if (table.validate() != null) {
            table.validate().accept(entity);
        }

        String name = table.name().apply(entity);
        Optional<T> existing = table.findByName() == null || name == null
                ? Optional.empty()
                : table.findByName().apply(name);
        if (existing.isEmpty()) {
            table.save().accept(entity);
            remember(declared, table, name);
            log.debug("Config import: {} '{}' created", table.jsonKey(), name);
            return true;
        }

        if (!remember(declared, table, name)) {
            log.info("Config import: {} '{}' is declared more than once — keeping the first entry",
                    table.jsonKey(), name);
            return false;
        }

        T target = existing.get();
        if (table.decryptStoredCredentials() != null) {
            // The service encrypts whatever it is handed, so a credential the document does not
            // declare has to be plaintext again before the row is saved a second time.
            table.decryptStoredCredentials().accept(target);
        }
        if (EntityIdentity.isDefaultEntry(target)) {
            log.warn("Config import: overriding the seeded default {} '{}' from the document",
                    table.jsonKey(), name);
        }
        EntityMerge.apply(entity, target, node);
        // The bot scope is an internal id, which the merge leaves alone: set what the document
        // declares on the row itself.
        EventHookEndpointScope.apply(target, node, lookups);
        table.save().accept(target);
        log.info("Config import: {} '{}' overridden by the document", table.jsonKey(), name);
        return false;
    }

    /** @return {@code false} when the document already declared this row earlier in the run */
    private static boolean remember(Set<String> declared, ConfigTable<?> table, String name) {
        return name == null || declared.add(table.jsonKey() + ":" + name);
    }

    /**
     * The mandatory default system prompt is not part of the document, so a bot without
     * one gets the seeded default. The tool configuration needs no help: {@code
     * BotService.save} assigns the default itself when the field is empty.
     */
    private void applyDefaults(Class<?> type, Object entity) {
        if (type == Bot.class && ((Bot) entity).getSystemPrompt() == null) {
            ((Bot) entity).setSystemPrompt(systemPromptService.findDefault()
                    .orElseThrow(() -> new IllegalStateException("No default system prompt "
                            + "exists; migration V3 did not run")));
        }
    }

    /**
     * Admin accounts are the one row type whose document form is not the entity: the
     * password arrives in plaintext and must be hashed, so the generic save path is
     * bypassed for {@link org.remus.giteabot.admin.AdminUser}.
     *
     * <p>A missing password is not an error: an <em>exported</em> document only carries
     * the username, because hashes never leave the instance. Such an entry is skipped
     * with a warning and the instance asks for the account at {@code /setup}.</p>
     */
    private int importAdminUser(JsonNode node) {
        String username = node.path("username").asString(null);
        String password = node.path("password").asString(null);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (password == null || password.isBlank()) {
            log.warn("Config import: adminUsers '{}' has no password (exported documents never "
                    + "carry one) — create the account at /setup", username);
            return 0;
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
        if (adminService.findByUsername(username).isPresent()) {
            log.info("Config import: adminUsers '{}' already exists — keeping it", username);
            return 0;
        }
        adminService.createAdmin(username, password);
        return 1;
    }

    /** Every table as a document — what {@code GET /config/export} returns. */
    @Transactional(readOnly = true)
    public ObjectNode export() {
        ObjectNode root = MAPPER.createObjectNode();
        for (ConfigTable<?> table : registry.tables()) {
            writeSection(table, root);
        }
        return root;
    }

    private <T> void writeSection(ConfigTable<T> table, ObjectNode root) {
        ArrayNode items = root.putArray(table.jsonKey());
        for (T entity : table.readAll().get()) {
            ObjectNode node = MAPPER.valueToTree(entity);
            flattenAssociations(node, entity);
            EventHookEndpointScope.export(entity, node, this::botName);
            if (table.exportSecrets() != null) {
                table.exportSecrets().accept(entity, node);
            }
            ConfigExchangeJson.clean(node);
            items.add(node);
        }
    }

    /** Bot id → name, for the reference a document carries instead of an internal id. */
    private Optional<String> botName(Long botId) {
        return botRepository.findById(botId).map(Bot::getName);
    }

    /**
     * Replaces a serialized {@code @ManyToOne} object with {@code {"name": …}}.
     *
     * <p>Without this an exported bot would carry a full copy of its integrations,
     * which would also bypass the per-table secret handling (the nested copy still
     * holds ciphertext).</p>
     */
    private void flattenAssociations(ObjectNode node, Object entity) {
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(ManyToOne.class)) {
                continue;
            }
            Object reference = EntityIdentity.read(entity, field);
            if (reference == null) {
                node.remove(field.getName());
                continue;
            }
            ObjectNode nameOnly = MAPPER.createObjectNode();
            nameOnly.put("name", AssociationResolver.nameOf(reference));
            node.set(field.getName(), nameOnly);
        }
    }
}
