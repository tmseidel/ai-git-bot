package org.remus.giteabot.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Unified webhook controller for all Git providers.
 * <p>
 * All webhooks are received at a single endpoint: {@code /api/webhook/{webhookSecret}}.
 * The bot is looked up by the webhook secret and the provider signature is verified.
 * The {@link WebhookTranslator} for the bot's {@link RepositoryType} then classifies the
 * request into a provider-neutral {@link WebhookEvent}, and the shared
 * {@link WebhookEventDispatcher} decides what the bot does with it — so trigger rules
 * are the same for every provider.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class UnifiedWebhookController {

    private final BotService botService;
    private final List<WebhookTranslator> translators;
    private final WebhookEventDispatcher dispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Unified webhook endpoint. Routes to the appropriate handler based on
     * the bot's Git integration type.
     *
     * <p>The raw body is required (not a pre-parsed map) so the provider
     * signature can be verified over the exact bytes before any JSON parsing
     * happens.</p>
     *
     * @param webhookSecret the bot's unique webhook secret (URL path)
     * @param headers       all request headers (for signature verification)
     * @param rawBody       the raw webhook payload bytes
     * @return response indicating the result of webhook processing
     */
    @PostMapping("/{webhookSecret}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable String webhookSecret,
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {

        return botService.findByWebhookSecret(webhookSecret)
                .map(bot -> {
                    if (!bot.isEnabled()) {
                        log.debug("Bot '{}' is disabled, ignoring webhook", bot.getName());
                        return ResponseEntity.ok("bot disabled");
                    }
                    WebhookTranslator translator = translatorFor(bot.getGitIntegration().getProviderType());
                    Map<String, String> caseInsensitiveHeaders = new LinkedCaseInsensitiveMap<>();
                    caseInsensitiveHeaders.putAll(headers);
                    // Optional second factor: the provider's signature over the raw body. Without
                    // a signing secret configured, the URL path secret alone authenticates.
                    String signingSecret = botService.getDecryptedWebhookSigningSecret(bot);
                    if (signingSecret != null && !signingSecret.isBlank()
                            && !translator.verifySignature(signingSecret, caseInsensitiveHeaders, rawBody)) {
                        log.warn("Invalid webhook signature for bot '{}' from {}",
                                bot.getName(), caseInsensitiveHeaders.getOrDefault("x-forwarded-for", "?"));
                        return ResponseEntity.status(401).body("invalid signature");
                    }
                    Map<String, Object> payload;
                    try {
                        payload = objectMapper.readValue(rawBody, new TypeReference<>() {});
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body("invalid JSON payload");
                    }
                    botService.incrementWebhookCallCount(bot);
                    log.info("Webhook received for bot '{}' (provider={}, git={})",
                            bot.getName(),
                            bot.getAiIntegration().getProviderType(),
                            bot.getGitIntegration().getName());
                    WebhookEvent event = translator.translate(bot, caseInsensitiveHeaders, payload);
                    return dispatcher.dispatch(bot, event);
                })
                .orElseGet(() -> {
                    log.warn("No bot found for webhook secret: {}...",
                            webhookSecret.substring(0, Math.min(8, webhookSecret.length())));
                    return ResponseEntity.notFound().build();
                });
    }

    /** The translator for the provider; every provider has one, so a miss is a wiring bug. */
    private WebhookTranslator translatorFor(RepositoryType providerType) {
        return translators.stream()
                .filter(t -> t.providerType() == providerType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WebhookTranslator registered for " + providerType));
    }
}
