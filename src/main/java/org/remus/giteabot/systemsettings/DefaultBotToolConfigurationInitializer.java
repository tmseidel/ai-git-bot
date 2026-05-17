package org.remus.giteabot.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ensures that a single, non-deletable default {@link BotToolConfiguration}
 * exists at startup and always contains every currently registered built-in
 * tool.
 *
 * <p>Runs after Flyway (handled by Spring Boot's default ordering) and before
 * the web layer becomes ready. Idempotent: on subsequent boots it only adds
 * newly registered built-in tools to the default configuration and never
 * touches any other configuration.</p>
 *
 * <p>This component does not edit user-created configurations. Custom
 * configurations keep their selection across upgrades; admins decide whether
 * to opt into newly added tools.</p>
 */
@Slf4j
@Configuration
public class DefaultBotToolConfigurationInitializer {

    static final String DEFAULT_CONFIGURATION_NAME = "Default";

    @Bean
    public ApplicationRunner defaultBotToolConfigurationRunner(
            BotToolConfigurationRepository configurationRepository,
            BotToolSelectionRepository selectionRepository,
            BuiltinToolRegistry builtinToolRegistry,
            TransactionTemplate transactionTemplate) {
        DefaultBotToolConfigurationBootstrap bootstrap = new DefaultBotToolConfigurationBootstrap(
                configurationRepository, selectionRepository, builtinToolRegistry);
        return args -> transactionTemplate.executeWithoutResult(status -> bootstrap.ensureDefault());
    }

    /**
     * Extracted as a plain class so it can be unit-tested without a Spring
     * context. The {@link Transactional} boundary is owned by the caller
     * ({@link ApplicationRunner} above or the test harness).
     */
    static class DefaultBotToolConfigurationBootstrap implements Ordered {

        private final BotToolConfigurationRepository configurationRepository;
        private final BotToolSelectionRepository selectionRepository;
        private final BuiltinToolRegistry builtinToolRegistry;

        DefaultBotToolConfigurationBootstrap(BotToolConfigurationRepository configurationRepository,
                                             BotToolSelectionRepository selectionRepository,
                                             BuiltinToolRegistry builtinToolRegistry) {
            this.configurationRepository = configurationRepository;
            this.selectionRepository = selectionRepository;
            this.builtinToolRegistry = builtinToolRegistry;
        }

        void ensureDefault() {
            BotToolConfiguration defaultConfiguration = configurationRepository.findByDefaultEntryTrue()
                    .orElseGet(this::createDefaultConfiguration);

            List<BuiltinToolRegistry.BuiltinTool> tools = builtinToolRegistry.builtinTools();
            Set<String> existingNames = new HashSet<>();
            for (BotToolSelection row : selectionRepository.findByConfigurationId(defaultConfiguration.getId())) {
                existingNames.add(row.getToolName());
            }

            int added = 0;
            for (BuiltinToolRegistry.BuiltinTool tool : tools) {
                if (existingNames.contains(tool.name())) {
                    continue;
                }
                BotToolSelection row = new BotToolSelection();
                row.setConfiguration(defaultConfiguration);
                row.setToolName(tool.name());
                row.setToolKind(tool.kind().name());
                selectionRepository.save(row);
                added++;
            }
            if (added > 0) {
                log.info("Added {} new built-in tool(s) to default tool configuration '{}'",
                        added, defaultConfiguration.getName());
            }
        }

        private BotToolConfiguration createDefaultConfiguration() {
            BotToolConfiguration configuration = new BotToolConfiguration();
            configuration.setName(DEFAULT_CONFIGURATION_NAME);
            configuration.setDefaultEntry(true);
            BotToolConfiguration saved = configurationRepository.save(configuration);
            log.info("Created default bot tool configuration '{}' (id={})", saved.getName(), saved.getId());
            return saved;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 100;
        }
    }
}
