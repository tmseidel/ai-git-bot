package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class BotService {

    private final BotRepository botRepository;
    private final BotToolConfigurationRepository botToolConfigurationRepository;

    public BotService(BotRepository botRepository,
                      BotToolConfigurationRepository botToolConfigurationRepository) {
        this.botRepository = botRepository;
        this.botToolConfigurationRepository = botToolConfigurationRepository;
    }

    @Transactional(readOnly = true)
    public List<Bot> findAll() {
        return botRepository.findAllWithIntegrations();
    }

    @Transactional(readOnly = true)
    public Optional<Bot> findById(Long id) {
        return botRepository.findByIdWithIntegrations(id);
    }

    @Transactional(readOnly = true)
    public Optional<Bot> findByWebhookSecret(String secret) {
        return botRepository.findByWebhookSecret(secret);
    }

    public Bot save(Bot bot) {
        if (bot.getWebhookSecret() == null) {
            bot.setWebhookSecret(UUID.randomUUID().toString());
        }
        if (bot.getBotType() == BotType.WRITER) {
            bot.setAgentEnabled(false);
        }
        if (bot.getToolConfiguration() == null) {
            // Defensive fallback: assign the auto-generated default tool
            // configuration so the mandatory FK is always satisfied. In
            // production this branch only fires for callers that bypass the
            // BotController (integration tests, scripts). The default is
            // bootstrapped by DefaultBotToolConfigurationInitializer on
            // application startup.
            botToolConfigurationRepository.findByDefaultEntryTrue()
                    .ifPresent(bot::setToolConfiguration);
        }
        return botRepository.save(bot);
    }

    public void deleteById(Long id) {
        botRepository.deleteById(id);
    }

    public void incrementWebhookCallCount(Bot bot) {
        bot.setWebhookCallCount(bot.getWebhookCallCount() + 1);
        bot.setLastWebhookAt(Instant.now());
        botRepository.save(bot);
    }

    public void recordError(Bot bot, String errorMessage) {
        bot.setLastErrorMessage(errorMessage);
        bot.setLastErrorAt(Instant.now());
        botRepository.save(bot);
    }
}
