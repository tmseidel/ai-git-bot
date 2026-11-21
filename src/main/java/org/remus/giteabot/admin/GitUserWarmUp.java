package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Resolves the account of every Git integration once the application is ready, so the admin UI
 * can show the bot's username before the first webhook arrives. Runs in the background, since an
 * unreachable provider would otherwise delay startup by its timeout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitUserWarmUp {

    private final GitIntegrationService gitIntegrationService;
    private final RepositoryPlatformFactory repositoryPlatformFactory;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        for (GitIntegration integration : gitIntegrationService.findAll()) {
            try {
                repositoryPlatformFactory.getPlatform(integration).getUser(null);
            } catch (RuntimeException e) {
                log.warn("Could not resolve the account of Git integration '{}' at startup: {}",
                        integration.getName(), e.getMessage());
            }
        }
    }
}
