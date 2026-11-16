package org.remus.giteabot.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.springframework.stereotype.Component;

/**
 * Resolves the account the bot's Git integration authenticates as, i.e. who the bot is in a
 * webhook, through {@link RepositoryPlatform#getUser(String)}. Every {@link WebhookTranslator}
 * takes the bot's identity from here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotAccountResolver {

    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final BotService botService;

    /**
     * Returns the bot's account, or {@code null} when it cannot be determined. Translators then
     * ignore the event, because without the bot's identity its own comments could re-trigger
     * it. The failure is recorded as the bot's last error so a misconfigured integration is
     * visible in the admin UI.
     *
     * @param owner where to ask for the account, see {@link RepositoryPlatform#getUser(String)}:
     *              the organization for Azure DevOps, {@code null} for every other provider
     */
    public @Nullable RepositoryUser resolve(Bot bot, @Nullable String owner) {
        try {
            return repositoryPlatformFactory.getPlatform(bot.getGitIntegration()).getUser(owner);
        } catch (RuntimeException e) {
            String error = "Could not resolve the account of the Git integration '"
                    + bot.getGitIntegration().getName() + "': " + e.getMessage();
            log.error("[Bot '{}'] {}", bot.getName(), error);
            botService.recordError(bot, error);
            return null;
        }
    }
}
