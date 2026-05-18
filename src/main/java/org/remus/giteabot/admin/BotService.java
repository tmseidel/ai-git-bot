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
            // Defensive fallback for callers that bypass the BotController
            // (integration tests, scripts): assign the Default tool
            // configuration so the mandatory FK is always satisfied. The
            // Default row and its initial built-in tool selections are
            // created by Flyway migration V12.
            botToolConfigurationRepository.findByDefaultEntryTrue()
                    .ifPresent(bot::setToolConfiguration);
        }
        if (bot.getToolConfiguration() == null) {
            // Fail fast with a clear domain error rather than letting the
            // NOT NULL / FK constraint surface as an opaque
            // DataIntegrityViolationException at flush time.
            throw new IllegalStateException(
                    "No tool configuration assigned and no default tool configuration exists. "
                            + "Ensure Flyway migration V12 has run, or assign a BotToolConfiguration "
                            + "explicitly before saving the bot.");
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
