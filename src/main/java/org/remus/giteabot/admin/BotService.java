package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BotService {

    private final BotRepository botRepository;
    private final BotToolConfigurationRepository botToolConfigurationRepository;

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

    /**
     * Parses the bot's {@link Bot#getUserWhitelist() user-whitelist} blob
     * into a case-insensitive lookup set of trimmed, lower-cased
     * usernames. Accepts both newline and comma separators (mixed
     * allowed) so operators can enter the list in whichever style they
     * prefer in the admin UI.
     *
     * <p>Returns an empty set when the whitelist is {@code null} or
     * blank — callers that treat an empty set as "everyone allowed"
     * preserve the unrestricted historical behaviour. The bot entity
     * intentionally holds no logic for this (JPA entities stay anaemic);
     * all whitelist semantics live here in the service layer.</p>
     */
    @Transactional(readOnly = true)
    public Set<String> getAllowedUsernames(Bot bot) {
        if (bot == null) {
            return Collections.emptySet();
        }
        String raw = bot.getUserWhitelist();
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split("[,\\r\\n]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Membership check against an already-computed allowed-username set.
     * Extracted so {@link BotWebhookService#isCallerAllowed} can reuse
     * the set it already holds without calling {@link #getAllowedUsernames}
     * a second time.
     *
     * <p>Empty {@code allowed} ⇒ everyone is permitted (unrestricted).
     * {@code null} / blank {@code username} ⇒ blocked when a whitelist
     * is configured.</p>
     */
    public boolean isUsernameInSet(Set<String> allowed, String username) {
        if (allowed.isEmpty()) {
            return true;
        }
        if (username == null || username.isBlank()) {
            return false;
        }
        return allowed.contains(username.trim().toLowerCase(Locale.ROOT));
    }
}
