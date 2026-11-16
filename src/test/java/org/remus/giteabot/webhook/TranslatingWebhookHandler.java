package org.remus.giteabot.webhook;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.event.WebhookTranslator;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Runs a raw webhook through a {@link WebhookTranslator} and a real {@link WebhookEventDispatcher},
 * as {@link UnifiedWebhookController} does, for tests that check which {@link BotWebhookService}
 * handler a provider's webhook reaches.
 *
 * @param eventHeader the header carrying the provider's event type; unused for Azure DevOps,
 *                    which sends it in the body
 */
public record TranslatingWebhookHandler(WebhookTranslator translator, String eventHeader,
                                        WebhookEventDispatcher dispatcher) {

    /**
     * @param botWebhookService a mock; its whitelist check is stubbed to let every caller
     *                          through, since the dispatcher asks it before an automatic review,
     *                          and its "review again" detection runs for real, so a comment is
     *                          routed by what it says, as in production. Tests must not stub
     *                          {@code isReviewAgainRequest} again: re-stubbing a real-method
     *                          stub invokes it with the matchers' nulls.
     */
    public TranslatingWebhookHandler(WebhookTranslator translator, String eventHeader,
                                     BotWebhookService botWebhookService) {
        this(translator, eventHeader, new WebhookEventDispatcher(allowingEveryCaller(botWebhookService)));
    }

    private static BotWebhookService allowingEveryCaller(BotWebhookService botWebhookService) {
        lenient().when(botWebhookService.isCallerAllowed(any(), any())).thenReturn(true);
        lenient().when(botWebhookService.isReviewAgainRequest(any(), any())).thenCallRealMethod();
        return botWebhookService;
    }

    public ResponseEntity<String> handleWebhook(Bot bot, String eventType, Map<String, Object> payload) {
        return dispatcher.dispatch(bot, translator.translate(bot, Map.of(eventHeader, eventType), payload));
    }

    public ResponseEntity<String> handleWebhook(Bot bot, Map<String, Object> payload) {
        return dispatcher.dispatch(bot, translator.translate(bot, Map.of(), payload));
    }
}
