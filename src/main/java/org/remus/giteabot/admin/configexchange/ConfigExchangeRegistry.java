package org.remus.giteabot.admin.configexchange;

import org.remus.giteabot.admin.AdminUser;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationRepository;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GitIntegrationRepository;
import org.remus.giteabot.admin.GitIntegrationService;
import org.remus.giteabot.eventhook.EventHookEndpoint;
import org.remus.giteabot.eventhook.EventHookEndpointRepository;
import org.remus.giteabot.eventhook.EventHookEndpointService;
import org.remus.giteabot.issueworkflow.IssueWorkflowRegistry;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetRepository;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationRepository;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.remus.giteabot.systemsettings.BotToolConfigurationService;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationRepository;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptRepository;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single list of importable configuration tables, in dependency order.
 *
 * <p>This class answers "which entities can be created from a document". Everything
 * else in the package iterates this list, so:</p>
 * <ul>
 *   <li>a new <b>field</b> on an entity costs nothing here,</li>
 *   <li>a new <b>entity type</b> costs exactly one entry,</li>
 *   <li>{@code ConfigCoverageTest} fails the build when an {@code @Entity} is neither
 *       listed here nor explicitly declared as runtime data.</li>
 * </ul>
 *
 * <p>Order is the import order: a row is only importable once everything it references
 * exists, so seeds and parents come first and {@code bots} last. Reads and saves go
 * through the owning service where one exists, which keeps password hashing, credential
 * encryption and domain validation in force during an import.</p>
 */
@Component
public class ConfigExchangeRegistry {

    private final List<ConfigTable<?>> tables;
    private final Map<Class<?>, Function<String, Optional<Object>>> lookups;

    public ConfigExchangeRegistry(AdminUserRepository adminUserRepository,
                                  SystemPromptService systemPromptService,
                                  SystemPromptRepository systemPromptRepository,
                                  BotToolConfigurationService botToolConfigurationService,
                                  BotToolConfigurationRepository botToolConfigurationRepository,
                                  McpConfigurationService mcpConfigurationService,
                                  McpConfigurationRepository mcpConfigurationRepository,
                                  DeploymentTargetService deploymentTargetService,
                                  DeploymentTargetRepository deploymentTargetRepository,
                                  AiIntegrationService aiIntegrationService,
                                  AiIntegrationRepository aiIntegrationRepository,
                                  GitIntegrationService gitIntegrationService,
                                  GitIntegrationRepository gitIntegrationRepository,
                                  WorkflowConfigurationService workflowConfigurationService,
                                  WorkflowConfigurationRepository workflowConfigurationRepository,
                                  BotService botService,
                                  BotRepository botRepository,
                                  EventHookEndpointService eventHookEndpointService,
                                  EventHookEndpointRepository eventHookEndpointRepository,
                                  PrWorkflowRegistry prWorkflowRegistry,
                                  IssueWorkflowRegistry issueWorkflowRegistry) {

        List<ConfigTable<?>> declared = new ArrayList<>();

        // Admin accounts: the document carries a plaintext password, which the importer
        // hashes through AdminService. The only non-pure entity (no save consumer).
        declared.add(new ConfigTable<>("adminUsers", AdminUser.class,
                AdminUser::getUsername,
                adminUserRepository::findAll,
                adminUserRepository::findByUsername,
                null,
                null,
                null,
                null));

        declared.add(new ConfigTable<>("systemPrompts", SystemPrompt.class,
                SystemPrompt::getName,
                systemPromptService::findAll,
                systemPromptRepository::findByName,
                systemPromptService::save,
                null,
                null,
                null));

        declared.add(new ConfigTable<>("botToolConfigurations", BotToolConfiguration.class,
                BotToolConfiguration::getName,
                botToolConfigurationService::findAll,
                botToolConfigurationRepository::findByName,
                botToolConfigurationService::save,
                null,
                null,
                null));

        declared.add(new ConfigTable<>("mcpConfigurations", McpConfiguration.class,
                McpConfiguration::getName,
                mcpConfigurationService::findAll,
                mcpConfigurationRepository::findByName,
                mcpConfigurationService::save,
                (configuration, node) -> put(node, "jsonContent",
                        mcpConfigurationService.getDecryptedJsonContent(configuration)),
                // The service always encrypts on save, so a row the document overrides has to
                // hold plaintext again — also the field the document does not declare.
                configuration -> configuration.setJsonContent(
                        mcpConfigurationService.getDecryptedJsonContent(configuration)),
                null));

        declared.add(new ConfigTable<>("deploymentTargets", DeploymentTarget.class,
                DeploymentTarget::getName,
                deploymentTargetService::findAll,
                deploymentTargetRepository::findByName,
                deploymentTargetService::save,
                null,
                null,
                null));

        declared.add(new ConfigTable<>("aiIntegrations", AiIntegration.class,
                AiIntegration::getName,
                aiIntegrationService::findAll,
                aiIntegrationRepository::findByName,
                aiIntegrationService::save,
                (integration, node) -> put(node, "apiKey",
                        aiIntegrationService.decryptApiKey(integration)),
                integration -> integration.setApiKey(
                        aiIntegrationService.decryptApiKey(integration)),
                null));

        declared.add(new ConfigTable<>("gitIntegrations", GitIntegration.class,
                GitIntegration::getName,
                gitIntegrationService::findAll,
                gitIntegrationRepository::findByName,
                integration -> gitIntegrationService.save(integration, false),
                (integration, node) -> {
                    put(node, "token", gitIntegrationService.decryptToken(integration));
                    // Encrypted at rest like the token: exporting the stored value would hand the
                    // next instance ciphertext it cannot decrypt with its own APP_ENCRYPTION_KEY.
                    put(node, "sshPrivateKey", gitIntegrationService.decryptSshPrivateKey(integration));
                },
                // No decrypt step: GitIntegrationService keeps the stored ciphertext on a blank
                // field, so it never re-encrypts what it did not receive as plaintext.
                null,
                null));

        declared.add(new ConfigTable<>("workflowConfigurations", WorkflowConfiguration.class,
                WorkflowConfiguration::getName,
                // Both kinds: the no-arg findAll() is the PR-kind view, and a document without
                // the ISSUE rows cannot be re-imported — a bot references its issue configuration,
                // which then cannot be resolved.
                () -> {
                    List<WorkflowConfiguration> bothKinds = new ArrayList<>(
                            workflowConfigurationService.findAll(WorkflowConfigurationKind.PR));
                    bothKinds.addAll(workflowConfigurationService.findAll(WorkflowConfigurationKind.ISSUE));
                    return bothKinds;
                },
                workflowConfigurationRepository::findByName,
                workflowConfigurationService::save,
                null,
                null,
                configuration -> validateWorkflowKeys(configuration, prWorkflowRegistry,
                        issueWorkflowRegistry)));

        declared.add(new ConfigTable<>("bots", Bot.class,
                Bot::getName,
                botService::findAll,
                botRepository::findByName,
                botService::save,
                (bot, node) -> put(node, "webhookSigningSecret",
                        botService.getDecryptedWebhookSigningSecret(bot)),
                bot -> bot.setWebhookSigningSecret(
                        botService.getDecryptedWebhookSigningSecret(bot)),
                null));

        // Outgoing webhooks: the column does not make the name unique, so the first row of that
        // name is the one a re-import merges into — an existing endpoint is updated instead of
        // created again. The bot scope is a reference (EventHookEndpointScope): a dropped botId
        // would not just detach the endpoint, it would make it global.
        declared.add(new ConfigTable<>("eventHookEndpoints", EventHookEndpoint.class,
                EventHookEndpoint::getName,
                eventHookEndpointService::findAll,
                eventHookEndpointRepository::findFirstByNameOrderByIdAsc,
                endpoint -> eventHookEndpointService.save(endpoint,
                        endpoint.getSecret(), endpoint.getAuthorizationHeader(),
                        endpoint.getCustomHeaders()),
                (endpoint, node) -> {
                    put(node, "secret", eventHookEndpointService.decryptSecret(endpoint));
                    put(node, "authorizationHeader",
                            eventHookEndpointService.decryptAuthorizationHeader(endpoint));
                    put(node, "customHeaders",
                            eventHookEndpointService.decryptCustomHeaders(endpoint));
                },
                // The service encrypts whatever it is handed, so the stored ciphertext has to
                // be plaintext before it is merged over and saved again.
                endpoint -> {
                    endpoint.setSecret(eventHookEndpointService.decryptSecret(endpoint));
                    endpoint.setAuthorizationHeader(
                            eventHookEndpointService.decryptAuthorizationHeader(endpoint));
                    endpoint.setCustomHeaders(
                            eventHookEndpointService.decryptCustomHeaders(endpoint));
                },
                null));

        this.tables = List.copyOf(declared);
        this.lookups = buildLookups(declared);
    }

    /** Tables in import order. */
    public List<ConfigTable<?>> tables() {
        return tables;
    }

    /** Name lookup per entity type, used to resolve {@code @ManyToOne} references. */
    public Map<Class<?>, Function<String, Optional<Object>>> lookups() {
        return Collections.unmodifiableMap(lookups);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Function<String, Optional<Object>>> buildLookups(
            List<ConfigTable<?>> declared) {
        Map<Class<?>, Function<String, Optional<Object>>> map = new HashMap<>();
        for (ConfigTable<?> table : declared) {
            if (table.findByName() == null) {
                continue;
            }
            map.put(table.type(),
                    name -> (Optional<Object>) table.findByName().apply(name));
        }
        return map;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    /**
     * Fails the import on a workflow key that is unknown or belongs to the other
     * configuration kind — a typo would otherwise silently disable a workflow the
     * operator believes is enabled.
     */
    private static void validateWorkflowKeys(WorkflowConfiguration configuration,
                                             PrWorkflowRegistry prWorkflowRegistry,
                                             IssueWorkflowRegistry issueWorkflowRegistry) {
        WorkflowConfigurationKind kind = configuration.getKind() == null
                ? WorkflowConfigurationKind.PR
                : configuration.getKind();
        for (WorkflowSelection selection : configuration.getSelectedWorkflows()) {
            String key = selection.getWorkflowKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("workflowConfigurations['"
                        + configuration.getName() + "'] contains a selection without workflowKey");
            }
            boolean known = kind == WorkflowConfigurationKind.ISSUE
                    ? issueWorkflowRegistry.find(key).isPresent()
                    : prWorkflowRegistry.find(key).isPresent();
            if (known) {
                continue;
            }
            boolean otherKind = kind == WorkflowConfigurationKind.ISSUE
                    ? prWorkflowRegistry.find(key).isPresent()
                    : issueWorkflowRegistry.find(key).isPresent();
            throw new IllegalArgumentException(otherKind
                    ? "workflow '" + key + "' is a "
                            + (kind == WorkflowConfigurationKind.ISSUE ? "pull-request" : "issue")
                            + " workflow; its configuration belongs to the other kind"
                    : "unknown " + kind + " workflow '" + key + "'");
        }
    }
}
