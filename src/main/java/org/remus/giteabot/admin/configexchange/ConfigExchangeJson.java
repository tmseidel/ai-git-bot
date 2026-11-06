package org.remus.giteabot.admin.configexchange;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.remus.giteabot.admin.AdminUser;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionParam;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolSelection;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpSelectedTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jackson setup for the instance configuration document.
 *
 * <p>The document is the entity model itself — no parallel DTO — so the entities
 * must be serializable. Three kinds of members are cut out through mixins, which
 * keeps the entities free of Jackson annotations:</p>
 *
 * <ul>
 *   <li><b>Back-references</b> ({@code selection → configuration} and friends).
 *       Without them Jackson recurses forever.</li>
 *   <li><b>Derived accessors</b> that look like state but are computed, including
 *       {@code AiIntegration.isEnableNativeToolCalling()} — the <em>inverse</em> of
 *       {@code useLegacyToolCalling}. If both reached the document, deserialization
 *       would set the same fact twice and the result would depend on property
 *       order.</li>
 *   <li><b>{@code AdminUser.passwordHash}</b>, which must never leave an instance.</li>
 * </ul>
 *
 * <p>{@link #clean(JsonNode)} removes the remaining runtime noise (ids, versions,
 * timestamps, nulls) from an exported node. Import strips the same fields
 * reflectively, so a hand-written document cannot reintroduce them.</p>
 */
public final class ConfigExchangeJson {

    /**
     * Entity members that are persistence bookkeeping or statistics, never
     * configuration. Shared by the export cleaner and {@link EntityIdentity}.
     */
    static final List<String> RUNTIME_FIELDS = List.of(
            "id", "lockVersion", "createdAt", "updatedAt", "deletionPending",
            "sshRemoteKeyId", "sshRemoteKeyOwnerId", "sshRemoteKeyTitle",
            "webhookCallCount", "aiTokensSent", "aiTokensReceived",
            "lastWebhookAt", "lastAiCallAt", "lastErrorMessage", "lastErrorAt",
            "botId");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addMixIn(WorkflowSelection.class, WorkflowSelectionMixin.class)
            .addMixIn(WorkflowSelectionParam.class, WorkflowSelectionParamMixin.class)
            .addMixIn(BotToolSelection.class, BotToolSelectionMixin.class)
            .addMixIn(McpSelectedTool.class, McpSelectedToolMixin.class)
            .addMixIn(Bot.class, BotMixin.class)
            .addMixIn(GitIntegration.class, GitIntegrationMixin.class)
            .addMixIn(AiIntegration.class, AiIntegrationMixin.class)
            .addMixIn(AdminUser.class, AdminUserMixin.class)
            .build();

    private ConfigExchangeJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Removes runtime bookkeeping fields and {@code null} values from a node tree, in
     * place. Applied to every exported row (nested selections included), so the
     * document only contains values an operator could actually set.
     */
    public static void clean(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (RUNTIME_FIELDS.contains(field.getKey()) || field.getValue().isNull()) {
                    toRemove.add(field.getKey());
                } else {
                    clean(field.getValue());
                }
            }
            toRemove.forEach(object::remove);
        } else if (node != null && node.isArray()) {
            node.forEach(ConfigExchangeJson::clean);
        }
    }

    /** Cuts the {@code WorkflowSelection → WorkflowConfiguration} back-reference and the derived map. */
    abstract static class WorkflowSelectionMixin {
        @JsonIgnore
        abstract WorkflowConfiguration getConfiguration();

        /**
         * Computed from {@code params}; exporting it too would ship the same fact twice and put a
         * key in the document that an operator can edit without any effect.
         */
        @JsonIgnore
        abstract Map<String, String> getParamsMap();
    }

    /** Cuts the {@code WorkflowSelectionParam → WorkflowSelection} back-reference. */
    abstract static class WorkflowSelectionParamMixin {
        @JsonIgnore
        abstract WorkflowSelection getSelection();
    }

    /** Cuts the {@code BotToolSelection → BotToolConfiguration} back-reference. */
    abstract static class BotToolSelectionMixin {
        @JsonIgnore
        abstract BotToolConfiguration getConfiguration();
    }

    /** Cuts the {@code McpSelectedTool → McpConfiguration} back-reference. */
    abstract static class McpSelectedToolMixin {
        @JsonIgnore
        abstract McpConfiguration getMcpConfiguration();
    }

    /** Hides the derived webhook path; the secret is the stored state. */
    abstract static class BotMixin {
        @JsonIgnore
        abstract String getWebhookPath();
    }

    /** Hides the derived SSH-tracking predicate. */
    abstract static class GitIntegrationMixin {
        @JsonIgnore
        abstract boolean hasManagedSshKeyTracking();
    }

    /** Hides the inverse accessor of {@code useLegacyToolCalling}, in both directions. */
    abstract static class AiIntegrationMixin {
        @JsonIgnore
        abstract boolean isEnableNativeToolCalling();

        @JsonIgnore
        abstract void setEnableNativeToolCalling(boolean enableNativeToolCalling);
    }

    /** Never export a password hash. */
    abstract static class AdminUserMixin {
        @JsonIgnore
        abstract String getPasswordHash();
    }
}
