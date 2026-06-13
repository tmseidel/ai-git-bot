package org.remus.giteabot.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.bitbucket.BitbucketWebhookHandler;
import org.remus.giteabot.gitea.GiteaWebhookHandler;
import org.remus.giteabot.github.GitHubWebhookHandler;
import org.remus.giteabot.gitlab.GitLabWebhookHandler;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Unified webhook controller that routes incoming webhooks to the appropriate
 * provider-specific handler based on the bot's configured Git integration type.
 * <p>
 * All webhooks are received at a single endpoint: {@code /api/webhook/{webhookSecret}}.
 * The bot is looked up by the webhook secret, and based on its {@link RepositoryType},
 * the request is delegated to the appropriate handler:
 * <ul>
 *   <li>{@link RepositoryType#GITEA} → {@link GiteaWebhookHandler}</li>
 *   <li>{@link RepositoryType#GITHUB} → {@link GitHubWebhookHandler}</li>
 *   <li>{@link RepositoryType#BITBUCKET} → {@link BitbucketWebhookHandler}</li>
 *   <li>{@link RepositoryType#GITLAB} → {@link GitLabWebhookHandler}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class UnifiedWebhookController {

    private final BotService botService;
    private final GiteaWebhookHandler giteaHandler;
    private final GitHubWebhookHandler gitHubHandler;
    private final BitbucketWebhookHandler bitbucketHandler;
    private final GitLabWebhookHandler gitLabHandler;

    /**
     * Unified webhook endpoint. Routes to the appropriate handler based on
     * the bot's Git integration type.
     *
     * @param webhookSecret the bot's unique webhook secret (URL path)
     * @param xGitHubEvent  GitHub event type header (optional)
     * @param xGitLabEvent  GitLab event type header (optional)
     * @param xEventKey     Bitbucket event key header (optional)
     * @param payload       the raw webhook payload
     * @return response indicating the result of webhook processing
     */
    @PostMapping("/{webhookSecret}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable String webhookSecret,
            @RequestHeader(value = "X-GitHub-Event", required = false) String xGitHubEvent,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String xGitLabEvent,
            @RequestHeader(value = "X-Event-Key", required = false) String xEventKey,
            @RequestBody Map<String, Object> payload) {

        return botService.findByWebhookSecret(webhookSecret)
                .map(bot -> {
                    if (!bot.isEnabled()) {
                        log.debug("Bot '{}' is disabled, ignoring webhook", bot.getName());
                        return ResponseEntity.ok("bot disabled");
                    }
                    botService.incrementWebhookCallCount(bot);
                    return routeToHandler(bot, xGitHubEvent, xGitLabEvent, xEventKey, payload);
                })
                .orElseGet(() -> {
                    log.warn("No bot found for webhook secret: {}...",
                            webhookSecret.substring(0, Math.min(8, webhookSecret.length())));
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Routes the webhook to the appropriate handler based on the bot's Git integration type.
     */
    private ResponseEntity<String> routeToHandler(Bot bot,
                                                   String xGitHubEvent,
                                                   String xGitLabEvent,
                                                   String xEventKey,
                                                   Map<String, Object> payload) {
        RepositoryType providerType = bot.getGitIntegration().getProviderType();
        log.info("Webhook received for bot '{}' (provider={}, git={})",
                bot.getName(),
                bot.getAiIntegration().getProviderType(),
                bot.getGitIntegration().getName());

        return switch (providerType) {
            case GITEA -> giteaHandler.handleWebhook(bot, payload);
            case GITHUB -> gitHubHandler.handleWebhook(bot, xGitHubEvent, payload);
            case BITBUCKET -> bitbucketHandler.handleWebhook(bot, xEventKey, payload);
            case GITLAB -> gitLabHandler.handleWebhook(bot, xGitLabEvent, payload);
        };
    }
}

