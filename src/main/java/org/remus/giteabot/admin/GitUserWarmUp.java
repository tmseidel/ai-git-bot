package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Resolves the Git user of every integration once the application is ready, so the admin UI can
 * show the bot's username before the first webhook event arrives. Runs in the background, since
 * an unreachable provider would otherwise delay startup by its timeout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitUserWarmUp {

    private final GitIntegrationService gitIntegrationService;
    private final GitUserService gitUserService;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        for (GitIntegration integration : gitIntegrationService.findAll()) {
            try {
                gitUserService.getGitUser(integration);
            } catch (RuntimeException e) {
                log.warn("Could not resolve the Git user at startup: {}", e.getMessage());
            }
        }
    }
}
